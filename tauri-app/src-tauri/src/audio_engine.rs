use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{SampleFormat, StreamConfig, OutputCallbackInfo};
use ringbuf::{HeapRb, Producer};
use std::sync::Arc;

struct SimpleResampler {
    ratio: f32,
    fractional_pos: f32,
    history: Vec<Vec<f32>>,
}

impl SimpleResampler {
    fn new(in_rate: u32, out_rate: u32, channels: usize) -> Self {
        Self {
            ratio: in_rate as f32 / out_rate as f32,
            fractional_pos: 0.0,
            history: vec![vec![0.0; channels]; 3],
        }
    }

    fn resample(&mut self, input: &[f32], channels: usize) -> Vec<f32> {
        let mut output = Vec::new();
        let in_frames = input.len() / channels;
        
        let get_sample = |hist: &Vec<Vec<f32>>, inp: &[f32], index: isize, c: usize| -> f32 {
            if index < 0 {
                let h_idx = index + 3;
                if h_idx < 0 {
                    0.0
                } else {
                    hist[h_idx as usize][c]
                }
            } else if index < in_frames as isize {
                inp[(index as usize) * channels + c]
            } else {
                inp[(in_frames - 1) * channels + c]
            }
        };

        while self.fractional_pos < in_frames as f32 {
            let index = self.fractional_pos as isize;
            let t = self.fractional_pos - index as f32;
            
            for c in 0..channels {
                let p0 = get_sample(&self.history, input, index - 1, c);
                let p1 = get_sample(&self.history, input, index, c);
                let p2 = get_sample(&self.history, input, index + 1, c);
                let p3 = get_sample(&self.history, input, index + 2, c);
                
                let a0 = -0.5 * p0 + 1.5 * p1 - 1.5 * p2 + 0.5 * p3;
                let a1 = p0 - 2.5 * p1 + 2.0 * p2 - 0.5 * p3;
                let a2 = -0.5 * p0 + 0.5 * p2;
                let a3 = p1;
                
                let out_sample = a0 * t * t * t + a1 * t * t + a2 * t + a3;
                output.push(out_sample);
            }
            
            self.fractional_pos += self.ratio;
        }
        
        self.fractional_pos -= in_frames as f32;
        if in_frames > 0 {
            for i in 0..3 {
                let idx = in_frames as isize - 3 + i as isize;
                for c in 0..channels {
                    self.history[i][c] = get_sample(&self.history, input, idx, c);
                }
            }
        }
        
        output
    }
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
    resampler: Option<SimpleResampler>,
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
            // Auto-detect VB-CABLE on Windows if default
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
            #[cfg(not(target_os = "windows"))]
            {
                host.default_output_device()
            }
        };

        let device = device.ok_or("No output device available")?;
        
        let config = device.default_output_config()?;
        self.device_sample_rate = config.sample_rate().0;
        self.device_channels = config.channels() as usize;

        if self.device_sample_rate != 48000 {
            self.resampler = Some(SimpleResampler::new(48000, self.device_sample_rate, self.device_channels));
        } else {
            self.resampler = None;
        }

        // Initialize a ring buffer for 1 second of audio
        let ring_buffer = HeapRb::<f32>::new(self.device_sample_rate as usize * self.device_channels);
        let (producer, mut consumer) = ring_buffer.split();

        self.producer = Some(producer);

        let stream_config: StreamConfig = config.clone().into();
        let err_fn = |err| eprintln!("an error occurred on stream: {}", err);

        let stream = match config.sample_format() {
            SampleFormat::F32 => device.build_output_stream(
                &stream_config,
                move |data: &mut [f32], _: &OutputCallbackInfo| {
                    for sample in data.iter_mut() {
                        *sample = consumer.pop().unwrap_or(0.0);
                    }
                },
                err_fn,
                None,
            )?,
            SampleFormat::I16 => device.build_output_stream(
                &stream_config,
                move |data: &mut [i16], _: &OutputCallbackInfo| {
                    for sample in data.iter_mut() {
                        let f_sample = consumer.pop().unwrap_or(0.0);
                        *sample = (f_sample * i16::MAX as f32) as i16;
                    }
                },
                err_fn,
                None,
            )?,
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
