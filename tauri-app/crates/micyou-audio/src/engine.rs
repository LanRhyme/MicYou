use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{OutputCallbackInfo, SampleFormat, StreamConfig};
use ringbuf::{HeapRb, Producer};
use rubato::audioadapter::{Adapter, AdapterMut};
use rubato::audioadapter_buffers::owned::InterleavedOwned;
use rubato::{Async, FixedAsync, PolynomialDegree, Resampler};
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::Arc;

const BUFFER_HEADROOM_MS: usize = 800;
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
                    log::error!(target: "audio", "Resample error: {}", e);
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
        static STATE: Cell<u32> = Cell::new(12345);
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
    channel_map_buffer: Vec<f32>,
    resample_buffer: Vec<f32>,
}

impl AudioOutputManager {
    pub fn new() -> Self {
        Self {
            stream: None,
            producer: None,
            resampler: None,
            device_sample_rate: 48000,
            device_channels: 2,
            channel_map_buffer: Vec::new(),
            resample_buffer: Vec::new(),
        }
    }

    pub fn start(
        &mut self,
        target_device: Option<String>,
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
                log::warn!(target: "audio", "Could not find exact device: {}, falling back to default.", target);
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

        // Initialize a ring buffer for ~800ms of audio — generous headroom to prevent underruns
        let buffer_size =
            (self.device_sample_rate as usize * self.device_channels * BUFFER_HEADROOM_MS)
                / MS_PER_SECOND;
        let ring_buffer = HeapRb::<f32>::new(buffer_size.max(MIN_BUFFER_SIZE));
        let (producer, mut consumer) = ring_buffer.split();

        self.producer = Some(producer);

        let stream_config: StreamConfig = config.clone().into();
        let err_fn = |err| log::error!(target: "audio", "an error occurred on stream: {}", err);

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

        Ok(())
    }

    pub fn push_audio_data(&mut self, data: &[f32], input_channels: usize) {
        map_channels(
            data,
            input_channels,
            self.device_channels,
            &mut self.channel_map_buffer,
        );

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
    }

    pub fn queued_samples(&self) -> usize {
        if let Some(producer) = &self.producer {
            producer.len()
        } else {
            0
        }
    }
}
