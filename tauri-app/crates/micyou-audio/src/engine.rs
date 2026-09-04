/*
 * MicYou — Turns your Android device into a high-quality PC microphone.
 * Copyright (C) 2026 LanRhyme <https://github.com/LanRhyme/MicYou>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version, with the MicYou Plugin Exception.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{OutputCallbackInfo, SampleFormat, StreamConfig};
use ringbuf::{HeapRb, Producer};
use rubato::audioadapter::{Adapter, AdapterMut};
use rubato::audioadapter_buffers::owned::InterleavedOwned;
use rubato::{Async, FixedAsync, PolynomialDegree, Resampler};
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::Arc;

const BUFFER_HEADROOM_MS: usize = 300;
const MS_PER_SECOND: usize = 1000;
const MIN_BUFFER_SIZE: usize = 16384;

pub struct RubatoResampler {
    resampler: Async<f32>,
    input_buffer: InterleavedOwned<f32>,
    chunk_size: usize,
}

impl RubatoResampler {
    pub fn new(
        in_rate: u32,
        out_rate: u32,
        channels: usize,
    ) -> Result<Self, rubato::ResamplerConstructionError> {
        let chunk_size = 480; // Match typical audio frame size
                              // Use polynomial interpolation - much faster than sinc, good enough quality
        let resampler = Async::<f32>::new_poly(
            out_rate as f64 / in_rate as f64,
            2.0,
            PolynomialDegree::Cubic,
            chunk_size,
            channels,
            FixedAsync::Input,
        )?;

        let input_buffer = InterleavedOwned::<f32>::new(0.0f32, channels, chunk_size);

        Ok(Self {
            resampler,
            input_buffer,
            chunk_size,
        })
    }

    pub fn resample(&mut self, input: &[f32], channels: usize, output: &mut Vec<f32>) {
        output.clear();
        let capacity = (input.len() as f64
            * (self.resampler.output_frames_max() as f64 / self.chunk_size as f64))
            .ceil() as usize;
        output.reserve(capacity);
        let mut offset = 0;

        while offset < input.len() {
            let chunk_input =
                &input[offset..(offset + self.chunk_size * channels).min(input.len())];
            offset += chunk_input.len();

            let in_frames = chunk_input.len() / channels;

            for frame in 0..self.chunk_size {
                for ch in 0..channels {
                    if frame < in_frames {
                        self.input_buffer.write_sample(
                            ch,
                            frame,
                            &chunk_input[frame * channels + ch],
                        );
                    } else {
                        self.input_buffer.write_sample(ch, frame, &0.0);
                    }
                }
            }

            match self.resampler.process(&self.input_buffer, 0, None) {
                Ok(output_buffer) => {
                    let out_frames = output_buffer.frames();
                    let expected_out_frames = (in_frames as f64
                        * (out_frames as f64 / self.chunk_size as f64))
                        .round() as usize;
                    for frame in 0..expected_out_frames.min(out_frames) {
                        for ch in 0..channels {
                            if let Some(sample) = output_buffer.read_sample(ch, frame) {
                                output.push(sample);
                            }
                        }
                    }
                }
                Err(e) => {
                    eprintln!("Resample error: {}", e);
                    output.extend_from_slice(chunk_input);
                }
            }
        }
    }
}

/// Fast PRNG for TPDF dithering (xorshift32), returns [0, 1) range.
fn rand_f32() -> f32 {
    use std::cell::Cell;
    thread_local! {
        static STATE: Cell<u32> = const { Cell::new(12345) };
    }
    STATE.with(|s| {
        let mut x = s.get();
        x ^= x << 13;
        x ^= x >> 17;
        x ^= x << 5;
        s.set(x);
        (x & 0x007FFFFF) as f32 / 8388608.0
    })
}

fn map_channels(input: &[f32], in_channels: usize, out_channels: usize, output: &mut Vec<f32>) {
    output.clear();
    if in_channels == 0 || out_channels == 0 {
        return;
    }
    if in_channels == out_channels {
        output.extend_from_slice(input);
        return;
    }

    let in_frames = input.len() / in_channels;
    output.reserve(in_frames * out_channels);

    for i in 0..in_frames {
        let in_idx = i * in_channels;
        for c in 0..out_channels {
            let src_c = c.min(in_channels - 1);
            output.push(input[in_idx + src_c]);
        }
    }
}

pub struct AudioOutputManager {
    stream: Option<cpal::Stream>,
    producer: Option<Producer<f32, Arc<HeapRb<f32>>>>,
    resampler: Option<RubatoResampler>,
    device_sample_rate: u32,
    device_channels: usize,
    buffer_headroom_ms: usize,
    channel_map_buffer: Vec<f32>,
    resample_buffer: Vec<f32>,

    /// Sound effects from plugins mixed into the virtual mic output
    mixer: crate::mixer::SoundMixer,
    #[allow(dead_code)]
    monitor_stream: Option<cpal::Stream>,
    #[allow(dead_code)]
    monitor_producer: Option<Producer<f32, Arc<HeapRb<f32>>>>,
    #[allow(dead_code)]
    monitor_resampler: Option<RubatoResampler>,
    #[allow(dead_code)]
    monitor_device_sample_rate: u32,
    #[allow(dead_code)]
    monitor_device_channels: usize,
    #[allow(dead_code)]
    monitor_channel_map_buffer: Vec<f32>,
    #[allow(dead_code)]
    monitor_resample_buffer: Vec<f32>,
    is_monitoring: bool,
    #[cfg(target_os = "linux")]
    pw_loopback_child: Option<std::process::Child>,
}

impl Default for AudioOutputManager {
    fn default() -> Self {
        Self::new()
    }
}

impl AudioOutputManager {
    pub fn new() -> Self {
        Self {
            stream: None,
            producer: None,
            resampler: None,
            device_sample_rate: 48000,
            device_channels: 2,
            buffer_headroom_ms: BUFFER_HEADROOM_MS,
            channel_map_buffer: Vec::new(),
            resample_buffer: Vec::new(),

            monitor_stream: None,
            monitor_producer: None,
            monitor_resampler: None,
            monitor_device_sample_rate: 48000,
            monitor_device_channels: 2,
            monitor_channel_map_buffer: Vec::new(),
            monitor_resample_buffer: Vec::new(),
            is_monitoring: false,
            mixer: crate::mixer::SoundMixer::new(),
            #[cfg(target_os = "linux")]
            pw_loopback_child: None,
        }
    }

    pub fn set_monitoring(&mut self, enabled: bool) {
        if self.is_monitoring == enabled {
            return;
        }
        self.is_monitoring = enabled;
        if enabled {
            self.start_monitor_loopback();
        } else {
            self.stop_monitor_loopback();
        }
    }

    fn start_monitor_loopback(&mut self) {
        #[cfg(target_os = "linux")]
        {
            if self.pw_loopback_child.is_some() {
                return;
            }
            if let Ok(child) = std::process::Command::new("pw-loopback")
                .arg("--capture-props={\"node.target\": \"MicYouVirtualSink\", \"media.class\": \"Stream/Input/Audio\", \"stream.capture.sink\": true}")
                .arg("--playback-props={\"media.class\": \"Stream/Output/Audio\"}")
                .spawn()
            {
                log::info!("[Audio] PipeWire monitor loopback started (pid: {})", child.id());
                self.pw_loopback_child = Some(child);
            } else {
                log::warn!("[Audio] Failed to start PipeWire monitor loopback");
            }
        }

        #[cfg(not(target_os = "linux"))]
        {
            if self.monitor_stream.is_some() {
                return;
            }
            let host = cpal::default_host();
            let device = match host.default_output_device() {
                Some(dev) => dev,
                None => {
                    log::warn!("[Audio] No default output device available for monitoring");
                    return;
                }
            };

            let config = match device.default_output_config() {
                Ok(c) => c,
                Err(e) => {
                    log::error!("[Audio] Failed to get monitor output config: {}", e);
                    return;
                }
            };

            self.monitor_device_sample_rate = config.sample_rate().0;
            self.monitor_device_channels = config.channels() as usize;

            if self.monitor_device_sample_rate != 48000 {
                self.monitor_resampler = RubatoResampler::new(
                    48000,
                    self.monitor_device_sample_rate,
                    self.monitor_device_channels,
                )
                .ok();
            } else {
                self.monitor_resampler = None;
            }

            let buffer_size = (self.monitor_device_sample_rate as usize
                * self.monitor_device_channels
                * self.buffer_headroom_ms)
                / MS_PER_SECOND;
            let ring_buffer = HeapRb::<f32>::new(buffer_size.max(MIN_BUFFER_SIZE));
            let (producer, mut consumer) = ring_buffer.split();

            self.monitor_producer = Some(producer);

            let stream_config: StreamConfig = config.clone().into();
            let err_fn = |err| log::error!("[Audio] Error on monitor stream: {}", err);

            let stream = match config.sample_format() {
                SampleFormat::F32 => {
                    let underrun_counter = Arc::new(AtomicU32::new(0));
                    let mut last_sample = 0.0f32;
                    device.build_output_stream(
                        &stream_config,
                        move |data: &mut [f32], _: &OutputCallbackInfo| {
                            for sample in data.iter_mut() {
                                match consumer.pop() {
                                    Some(s) => {
                                        *sample = s;
                                        last_sample = s;
                                        underrun_counter.store(0, Ordering::Relaxed);
                                    }
                                    None => {
                                        let count =
                                            underrun_counter.fetch_add(1, Ordering::Relaxed);
                                        let fade = (1.0 - count as f32 * 0.01).max(0.0);
                                        *sample = last_sample * fade;
                                    }
                                }
                            }
                        },
                        err_fn,
                        None,
                    )
                }
                SampleFormat::I16 => {
                    let underrun_counter = Arc::new(AtomicU32::new(0));
                    let mut last_sample = 0.0f32;
                    device.build_output_stream(
                        &stream_config,
                        move |data: &mut [i16], _: &OutputCallbackInfo| {
                            for sample in data.iter_mut() {
                                let f_sample = match consumer.pop() {
                                    Some(s) => {
                                        underrun_counter.store(0, Ordering::Relaxed);
                                        last_sample = s;
                                        s
                                    }
                                    None => {
                                        let count =
                                            underrun_counter.fetch_add(1, Ordering::Relaxed);
                                        let fade = (1.0 - count as f32 * 0.01).max(0.0);
                                        last_sample * fade
                                    }
                                };
                                let dither: f32 =
                                    (rand_f32() - 0.5 + rand_f32() - 0.5) * (1.0 / 32768.0);
                                let dithered = f_sample + dither;
                                *sample = (dithered * 32768.0).clamp(-32768.0, 32767.0) as i16;
                            }
                        },
                        err_fn,
                        None,
                    )
                }
                _ => return,
            };

            if let Ok(st) = stream {
                if st.play().is_ok() {
                    self.monitor_stream = Some(st);
                    log::info!("[Audio] Audio monitor stream started successfully");
                }
            }
        }
    }

    fn stop_monitor_loopback(&mut self) {
        #[cfg(target_os = "linux")]
        {
            if let Some(mut child) = self.pw_loopback_child.take() {
                let _ = child.kill();
                let _ = child.wait();
                log::info!("[Audio] PipeWire monitor loopback stopped");
            }
        }

        #[cfg(not(target_os = "linux"))]
        {
            self.monitor_stream = None;
            self.monitor_producer = None;
            self.monitor_resampler = None;
        }
    }

    pub fn start(
        &mut self,
        target_device: Option<String>,
        buffer_headroom_ms: usize,
    ) -> Result<(), Box<dyn std::error::Error>> {
        let host = cpal::default_host();

        let device = if let Some(target) = target_device.clone() {
            let mut matched_device = None;
            if let Ok(devices) = host.output_devices() {
                for dev in devices {
                    if let Ok(name) = dev.name() {
                        if name == target {
                            matched_device = Some(dev);
                            break;
                        }
                    }
                }
            }
            if matched_device.is_none() {
                eprintln!(
                    "Could not find exact device: {}, falling back to default.",
                    target
                );
            }
            matched_device.or_else(|| host.default_output_device())
        } else {
            // Auto-detect virtual audio devices by platform
            #[cfg(target_os = "windows")]
            {
                let mut cable_device = None;
                if let Ok(devices) = host.output_devices() {
                    for dev in devices {
                        if let Ok(name) = dev.name() {
                            if name.to_lowercase().contains("cable input") {
                                cable_device = Some(dev);
                                break;
                            }
                        }
                    }
                }
                if cable_device.is_none() {
                    log::warn!("[Audio] VB-Cable not detected, falling back to default device. Note: Playing microphone audio through physical speakers may cause acoustic feedback.");
                }
                cable_device.or_else(|| host.default_output_device())
            }
            #[cfg(target_os = "macos")]
            {
                let mut blackhole_device = None;
                if let Ok(devices) = host.output_devices() {
                    for dev in devices {
                        if let Ok(name) = dev.name() {
                            if name.to_lowercase().contains("blackhole") {
                                blackhole_device = Some(dev);
                                break;
                            }
                        }
                    }
                }
                if blackhole_device.is_none() {
                    log::warn!("[Audio] BlackHole not detected, falling back to default device. Note: Playing microphone audio through physical speakers may cause acoustic feedback.");
                }
                blackhole_device.or_else(|| host.default_output_device())
            }
            #[cfg(not(any(target_os = "windows", target_os = "macos")))]
            {
                host.default_output_device()
            }
        };

        let device = device.ok_or("No output device available")?;

        let config = device.default_output_config()?;
        self.device_sample_rate = config.sample_rate().0;
        self.device_channels = config.channels() as usize;

        if self.device_sample_rate != 48000 {
            self.resampler = Some(RubatoResampler::new(
                48000,
                self.device_sample_rate,
                self.device_channels,
            )?);
        } else {
            self.resampler = None;
        }

        // Initialize a ring buffer with configurable headroom — larger values
        // tolerate jitter better but add latency, smaller values reduce latency
        self.buffer_headroom_ms = buffer_headroom_ms.clamp(100, 1200);
        let buffer_size =
            (self.device_sample_rate as usize * self.device_channels * self.buffer_headroom_ms)
                / MS_PER_SECOND;
        let ring_buffer = HeapRb::<f32>::new(buffer_size.max(MIN_BUFFER_SIZE));
        let (producer, mut consumer) = ring_buffer.split();

        self.producer = Some(producer);

        let stream_config: StreamConfig = config.clone().into();
        let err_fn = |err| eprintln!("an error occurred on stream: {}", err);

        let stream = match config.sample_format() {
            SampleFormat::F32 => {
                let underrun_counter = Arc::new(AtomicU32::new(0));
                let mut last_sample = 0.0f32;
                device.build_output_stream(
                    &stream_config,
                    move |data: &mut [f32], _: &OutputCallbackInfo| {
                        for sample in data.iter_mut() {
                            match consumer.pop() {
                                Some(s) => {
                                    *sample = s;
                                    last_sample = s;
                                    underrun_counter.store(0, Ordering::Relaxed);
                                }
                                None => {
                                    // Soft fade to silence on underrun instead of hard cut
                                    let count = underrun_counter.fetch_add(1, Ordering::Relaxed);
                                    let fade = (1.0 - count as f32 * 0.01).max(0.0);
                                    *sample = last_sample * fade;
                                }
                            }
                        }
                    },
                    err_fn,
                    None,
                )?
            }
            SampleFormat::I16 => {
                let underrun_counter = Arc::new(AtomicU32::new(0));
                let mut last_sample = 0.0f32;
                device.build_output_stream(
                    &stream_config,
                    move |data: &mut [i16], _: &OutputCallbackInfo| {
                        for sample in data.iter_mut() {
                            let f_sample = match consumer.pop() {
                                Some(s) => {
                                    underrun_counter.store(0, Ordering::Relaxed);
                                    last_sample = s;
                                    s
                                }
                                None => {
                                    let count = underrun_counter.fetch_add(1, Ordering::Relaxed);
                                    let fade = (1.0 - count as f32 * 0.01).max(0.0);
                                    last_sample * fade // Soft fade on underrun
                                }
                            };
                            // TPDF dithering for f32→i16 conversion — reduces quantization noise
                            let dither: f32 =
                                (rand_f32() - 0.5 + rand_f32() - 0.5) * (1.0 / 32768.0);
                            let dithered = f_sample + dither;
                            *sample = (dithered * 32768.0).clamp(-32768.0, 32767.0) as i16;
                        }
                    },
                    err_fn,
                    None,
                )?
            }
            _ => return Err("Unsupported sample format".into()),
        };

        stream.play()?;
        self.stream = Some(stream);

        // If monitoring was enabled prior to start, start monitor stream
        if self.is_monitoring {
            self.start_monitor_loopback();
        }

        Ok(())
    }

    /// Whether the cpal output stream is currently open.
    pub fn is_open(&self) -> bool {
        self.stream.is_some()
    }

    /// Close the output stream while keeping the instance alive so it can be
    /// re-opened later. Used when the app process is exiting so the persistent
    /// virtual device is torn down without dropping the whole manager.
    pub fn close(&mut self) {
        self.stop_monitor_loopback();
        self.stream = None;
        self.producer = None;
        self.resampler = None;
    }

    pub fn push_audio_data(&mut self, data: &[f32], input_channels: usize) {
        // Plugin sound effects are mixed into the virtual mic stream so the
        // remote peer hears them exactly like microphone audio
        if !self.mixer.is_empty() && !data.is_empty() && input_channels > 0 {
            let mut mixed = data.to_vec();
            self.mixer.mix_into(&mut mixed, input_channels);
            map_channels(
                &mixed,
                input_channels,
                self.device_channels,
                &mut self.channel_map_buffer,
            );
        } else {
            map_channels(
                data,
                input_channels,
                self.device_channels,
                &mut self.channel_map_buffer,
            );
        }

        if let Some(resampler) = &mut self.resampler {
            resampler.resample(
                &self.channel_map_buffer,
                self.device_channels,
                &mut self.resample_buffer,
            );
            if let Some(producer) = &mut self.producer {
                producer.push_slice(&self.resample_buffer);
            }
        } else {
            if let Some(producer) = &mut self.producer {
                producer.push_slice(&self.channel_map_buffer);
            }
        }

        if self.is_monitoring {
            #[cfg(not(target_os = "linux"))]
            {
                if let Some(producer) = &mut self.monitor_producer {
                    map_channels(
                        data,
                        input_channels,
                        self.monitor_device_channels,
                        &mut self.monitor_channel_map_buffer,
                    );
                    if let Some(resampler) = &mut self.monitor_resampler {
                        resampler.resample(
                            &self.monitor_channel_map_buffer,
                            self.monitor_device_channels,
                            &mut self.monitor_resample_buffer,
                        );
                        producer.push_slice(&self.monitor_resample_buffer);
                    } else {
                        producer.push_slice(&self.monitor_channel_map_buffer);
                    }
                }
            }
        }
    }

    /// Queue a mono sound effect mixed into the virtual mic output
    pub fn push_sound_effect(&mut self, samples: Vec<f32>, gain: f32) {
        self.mixer.add(samples, gain);
    }

    pub fn queued_samples(&self) -> usize {
        if let Some(producer) = &self.producer {
            producer.len()
        } else {
            0
        }
    }
}

impl Drop for AudioOutputManager {
    fn drop(&mut self) {
        self.stop_monitor_loopback();
    }
}
