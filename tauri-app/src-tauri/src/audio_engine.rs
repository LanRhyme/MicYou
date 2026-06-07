use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{SampleFormat, StreamConfig, OutputCallbackInfo};
use ringbuf::{HeapRb, Producer};
use std::sync::Arc;

pub struct AudioOutputManager {
    stream: Option<cpal::Stream>,
    producer: Option<Producer<f32, Arc<HeapRb<f32>>>>,
}

impl AudioOutputManager {
    pub fn new() -> Self {
        Self {
            stream: None,
            producer: None,
        }
    }

    pub fn start(&mut self) -> Result<(), Box<dyn std::error::Error>> {
        let host = cpal::default_host();
        let device = host.default_output_device().ok_or("No output device available")?;
        
        let config = device.default_output_config()?;
        let sample_rate = config.sample_rate();
        let channels = config.channels() as usize;

        // Initialize a ring buffer for 1 second of audio
        let ring_buffer = HeapRb::<f32>::new(48000 * channels);
        let (producer, mut consumer) = ring_buffer.split();

        self.producer = Some(producer);

        let stream_config: StreamConfig = config.clone().into();

        let err_fn = |err| eprintln!("an error occurred on stream: {}", err);

        let stream = match config.sample_format() {
            SampleFormat::F32 => device.build_output_stream(
                stream_config.clone(),
                move |data: &mut [f32], _: &OutputCallbackInfo| {
                    for sample in data.iter_mut() {
                        *sample = consumer.pop().unwrap_or(0.0);
                    }
                },
                err_fn,
                None,
            )?,
            SampleFormat::I16 => device.build_output_stream(
                stream_config.clone(),
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

    pub fn push_audio_data(&mut self, data: &[f32]) {
        if let Some(producer) = &mut self.producer {
            producer.push_slice(data);
        }
    }
}
