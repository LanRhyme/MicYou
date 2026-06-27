use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{SampleFormat, StreamConfig, OutputCallbackInfo};
use ringbuf::{HeapRb, Producer};
use rubato::{Resampler, Async, FixedAsync, PolynomialDegree};
use rubato::audioadapter_buffers::owned::InterleavedOwned;
use rubato::audioadapter::{Adapter, AdapterMut};
use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};

pub struct RubatoResampler {
    resampler: Async<f32>,
    input_buffer: InterleavedOwned<f32>,
    chunk_size: usize,
}

impl RubatoResampler {
    pub fn new(in_rate: u32, out_rate: u32, channels: usize) -> Self {
        let chunk_size = 480; // Match typical audio frame size
        // Use polynomial interpolation - much faster than sinc, good enough quality
        let resampler = Async::<f32>::new_poly(
            out_rate as f64 / in_rate as f64,
            2.0,
            PolynomialDegree::Cubic,
            chunk_size,
            channels,
            FixedAsync::Input,
        ).unwrap();

        let input_buffer = InterleavedOwned::<f32>::new(0.0f32, channels, chunk_size);

        Self {
            resampler,
            input_buffer,
            chunk_size,
        }
    }

    pub fn resample(&mut self, input: &[f32], channels: usize) -> Vec<f32> {
        let in_frames = input.len() / channels;

        // For very small inputs or mismatched sizes, fall back to simple passthrough
        // to avoid the overhead of the resampler
        if in_frames <= 2 || in_frames > self.chunk_size {
            return input.to_vec();
        }

        // Zero out only the unused part of the input buffer to avoid stale data
        for frame in in_frames..self.chunk_size {
            for ch in 0..channels {
                self.input_buffer.write_sample(ch, frame, &0.0);
            }
        }

        // Fill the pre-allocated input buffer
        for (i, &sample) in input.iter().enumerate() {
            let frame = i / channels;
            let ch = i % channels;
            if frame < self.chunk_size {
                self.input_buffer.write_sample(ch, frame, &sample);
            }
        }

        // Process using the convenience method
        match self.resampler.process(&self.input_buffer, 0, None) {
            Ok(output_buffer) => {
                let out_frames = output_buffer.frames();
                let mut output = Vec::with_capacity(out_frames * channels);
                for frame in 0..out_frames {
                    for ch in 0..channels {
                        if let Some(sample) = output_buffer.read_sample(ch, frame) {
                            output.push(sample);
                        }
                    }
                }
                output
            }
            Err(e) => {
                eprintln!("Resample error: {}", e);
                input.to_vec()
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

fn map_channels(input: &[f32], in_channels: usize, out_channels: usize) -> Vec<f32> {
    if in_channels == out_channels {
        return input.to_vec();
    }
    
    let mut output = Vec::with_capacity((input.len() / in_channels) * out_channels);
    let in_frames = input.len() / in_channels;
    
    for i in 0..in_frames {
        let in_idx = i * in_channels;
        for c in 0..out_channels {
            let src_c = c.min(in_channels - 1);
            output.push(input[in_idx + src_c]);
        }
    }
    output
}

pub struct AudioOutputManager {
    stream: Option<cpal::Stream>,
    producer: Option<Producer<f32, Arc<HeapRb<f32>>>>,
    resampler: Option<RubatoResampler>,
    device_sample_rate: u32,
    device_channels: usize,
}

impl AudioOutputManager {
    pub fn new() -> Self {
        Self {
            stream: None,
            producer: None,
            resampler: None,
            device_sample_rate: 48000,
            device_channels: 2,
        }
    }

    pub fn start(&mut self, target_device: Option<String>) -> Result<(), Box<dyn std::error::Error>> {
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
                eprintln!("Could not find exact device: {}, falling back to default.", target);
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
            self.resampler = Some(RubatoResampler::new(48000, self.device_sample_rate, self.device_channels));
        } else {
            self.resampler = None;
        }

        // Initialize a ring buffer for ~800ms of audio — generous headroom to prevent underruns
        let buffer_size = (self.device_sample_rate as usize * self.device_channels * 4) / 5;
        let ring_buffer = HeapRb::<f32>::new(buffer_size.max(16384));
        let (producer, mut consumer) = ring_buffer.split();

        self.producer = Some(producer);

        let stream_config: StreamConfig = config.clone().into();
        let err_fn = |err| eprintln!("an error occurred on stream: {}", err);

        let stream = match config.sample_format() {
            SampleFormat::F32 => {
                let underrun_counter = Arc::new(AtomicU32::new(0));
                device.build_output_stream(
                &stream_config,
                move |data: &mut [f32], _: &OutputCallbackInfo| {
                    for sample in data.iter_mut() {
                        match consumer.pop() {
                            Some(s) => {
                                *sample = s;
                                underrun_counter.store(0, Ordering::Relaxed);
                            }
                            None => {
                                // Soft fade to silence on underrun instead of hard cut
                                let count = underrun_counter.fetch_add(1, Ordering::Relaxed);
                                let fade = (1.0 - count as f32 * 0.01).max(0.0);
                                *sample *= fade;
                            }
                        }
                    }
                },
                err_fn,
                None,
            )?},
            SampleFormat::I16 => {
                let underrun_counter = Arc::new(AtomicU32::new(0));
                device.build_output_stream(
                &stream_config,
                move |data: &mut [i16], _: &OutputCallbackInfo| {
                    for sample in data.iter_mut() {
                        let f_sample = match consumer.pop() {
                            Some(s) => {
                                underrun_counter.store(0, Ordering::Relaxed);
                                s
                            }
                            None => {
                                let count = underrun_counter.fetch_add(1, Ordering::Relaxed);
                                let fade = (1.0 - count as f32 * 0.01).max(0.0);
                                0.0 * fade // Soft fade on underrun
                            }
                        };
                        // TPDF dithering for f32→i16 conversion — reduces quantization noise
                        let dither: f32 = (rand_f32() - 0.5 + rand_f32() - 0.5) * (1.0 / 32768.0);
                        let dithered = f_sample + dither;
                        *sample = (dithered * 32768.0).clamp(-32768.0, 32767.0) as i16;
                    }
                },
                err_fn,
                None,
            )?},
            _ => return Err("Unsupported sample format".into()),
        };

        stream.play()?;
        self.stream = Some(stream);

        Ok(())
    }

    pub fn push_audio_data(&mut self, data: &[f32], input_channels: usize) {
        let mapped = map_channels(data, input_channels, self.device_channels);
        
        let final_data = if let Some(resampler) = &mut self.resampler {
            resampler.resample(&mapped, self.device_channels)
        } else {
            mapped
        };

        if let Some(producer) = &mut self.producer {
            producer.push_slice(&final_data);
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
