use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use ringbuf::{HeapRb, Rb};

const RING_BUF_SEC: usize = 2;
const TARGET_RATE: u32 = 48000;

/// Cross-platform speaker loopback capture for AEC far-end reference.
///
/// - Windows: WASAPI loopback on default render device (no virtual device needed)
/// - macOS: cpal input from BlackHole
/// - Linux: cpal input from PipeWire virtual mic
pub struct LoopbackCapture {
    active: Arc<AtomicBool>,
    buffer: Arc<Mutex<HeapRb<f32>>>,
}

impl LoopbackCapture {
    pub fn new() -> Self {
        Self {
            active: Arc::new(AtomicBool::new(false)),
            buffer: Arc::new(Mutex::new(HeapRb::new(TARGET_RATE as usize * RING_BUF_SEC))),
        }
    }

    pub fn start(&self) -> bool {
        if self.active.load(Ordering::Relaxed) {
            return true;
        }
        self.active.store(true, Ordering::Relaxed);

        let active = self.active.clone();
        let buffer = self.buffer.clone();

        std::thread::Builder::new()
            .name("SpeakerLoopback".into())
            .spawn(move || {
                #[cfg(target_os = "windows")]
                wasapi_loopback_thread(active, buffer);
                #[cfg(not(target_os = "windows"))]
                cpal_capture_thread(active, buffer);
            })
            .is_ok()
    }

    pub fn stop(&self) {
        self.active.store(false, Ordering::Relaxed);
    }

    /// Read n_samples from the loopback buffer, consuming them.
    /// Returns None if insufficient data.
    pub fn read(&self, n_samples: usize) -> Option<Vec<f32>> {
        let mut buf = self.buffer.lock().ok()?;
        if buf.len() < n_samples {
            return None;
        }
        let mut out = Vec::with_capacity(n_samples);
        for _ in 0..n_samples {
            out.push(buf.pop().unwrap());
        }
        Some(out)
    }

    pub fn is_active(&self) -> bool {
        self.active.load(Ordering::Relaxed)
    }
}

// ─── Helper: downmix + resample + push to buffer ─────────────────────────

fn push_to_buffer(
    data: &[f32],
    channels: usize,
    _device_rate: u32,
    resampler: &Option<Arc<Mutex<crate::engine::RubatoResampler>>>,
    buffer: &Mutex<HeapRb<f32>>,
) {
    // Downmix to mono
    let mono: Vec<f32> = if channels > 1 {
        data.chunks(channels)
            .map(|frame| frame.iter().sum::<f32>() / channels as f32)
            .collect()
    } else {
        data.to_vec()
    };

    // Resample to 48kHz if needed
    let resampled = if let Some(ref r) = resampler {
        if let Ok(mut resampler) = r.lock() {
            let mut out = Vec::new();
            resampler.resample(&mono, 1, &mut out);
            out
        } else {
            mono
        }
    } else {
        mono
    };

    if let Ok(mut buf) = buffer.lock() {
        for &s in &resampled {
            buf.push_overwrite(s);
        }
    }
}

// ─── Windows: WASAPI loopback on default render device ────────────────────
//
// The trick: get the default render (speaker) device, then initialize its
// IAudioClient with Direction::Capture + ShareMode::Shared.  The wasapi crate
// automatically adds AUDCLNT_STREAMFLAGS_LOOPBACK in this combination
// (see api.rs line 832-835).

#[cfg(target_os = "windows")]
fn wasapi_loopback_thread(active: Arc<AtomicBool>, buffer: Arc<Mutex<HeapRb<f32>>>) {
    use std::collections::VecDeque;
    use wasapi::*;

    let result = (|| -> Result<(), Box<dyn std::error::Error>> {
        initialize_mta().ok()?;

        let device = get_default_device(&Direction::Render)?;
        let device_name = device.get_friendlyname().unwrap_or_default();
        log::info!("[Loopback] WASAPI: capturing from '{}'", device_name);

        let mut audio_client = device.get_iaudioclient()?;
        let mix_format = audio_client.get_mixformat()?;
        let channels = mix_format.get_nchannels() as usize;
        let device_rate = mix_format.get_samplespersec();

        log::info!(
            "[Loopback] WASAPI device format: {}Hz {}ch",
            device_rate, channels
        );

        let (_def_time, min_time) = audio_client.get_periods()?;

        // Direction::Capture on a Render device = loopback (auto loopback flag)
        audio_client.initialize_client(
            &mix_format,
            min_time,
            &Direction::Capture,
            &ShareMode::Shared,
            true,
        )?;

        let h_event = audio_client.set_get_eventhandle()?;
        let capture_client = audio_client.get_audiocaptureclient()?;

        // Create resampler if device is not 48kHz
        let resampler = if device_rate != TARGET_RATE {
            match crate::engine::RubatoResampler::new(device_rate, TARGET_RATE, 1) {
                Ok(r) => Some(Arc::new(Mutex::new(r))),
                Err(e) => {
                    log::error!("[Loopback] Failed to create resampler: {}", e);
                    None
                }
            }
        } else {
            None
        };

        audio_client.start_stream()?;
        log::info!("[Loopback] WASAPI loopback started");

        let bytes_per_frame = mix_format.get_blockalign() as usize;
        let mut total_frames: u64 = 0;

        while active.load(Ordering::Relaxed) {
            let available = audio_client.get_available_space_in_frames()?;
            if available == 0 {
                h_event.wait_for_event(100)?;
                continue;
            }

            let mut deque: VecDeque<u8> =
                VecDeque::with_capacity(available as usize * bytes_per_frame);
            let _flags = capture_client.read_from_device_to_deque(&mut deque)?;

            if !deque.is_empty() {
                let slice = deque.make_contiguous();
                let f32_samples: Vec<f32> = slice
                    .chunks_exact(4)
                    .map(|b| f32::from_le_bytes([b[0], b[1], b[2], b[3]]))
                    .collect();

                if !f32_samples.is_empty() {
                    push_to_buffer(
                        &f32_samples, channels, device_rate, &resampler, &buffer,
                    );
                    total_frames += (f32_samples.len() / channels) as u64;
                }
            }

            h_event.wait_for_event(100)?;
        }

        audio_client.stop_stream()?;
        log::info!(
            "[Loopback] WASAPI loopback stopped, {} frames captured",
            total_frames
        );
        Ok(())
    })();

    if let Err(e) = result {
        log::error!("[Loopback] WASAPI error: {}", e);
    }
    active.store(false, Ordering::Relaxed);
}

// ─── macOS/Linux: cpal capture from virtual audio device ──────────────────

#[cfg(not(target_os = "windows"))]
fn cpal_capture_thread(active: Arc<AtomicBool>, buffer: Arc<Mutex<HeapRb<f32>>>) {
    use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};

    let host = cpal::default_host();

    // Find virtual audio device by name hint
    let hints: &[&str] = &[
        "monitor",      // Linux: PulseAudio/PipeWire monitor sources
        "blackhole",    // macOS
        "pipewire",     // Linux fallback
        "virtual",      // Linux fallback
    ];

    let device = {
        let mut found = None;
        if let Ok(devices) = host.input_devices() {
            'outer: for dev in devices {
                if let Ok(name) = dev.name() {
                    let lower = name.to_lowercase();
                    for hint in hints {
                        if lower.contains(hint) {
                            log::info!("[Loopback] Found virtual device: '{}'", name);
                            found = Some(dev);
                            break 'outer;
                        }
                    }
                }
            }
        }
        match found {
            Some(d) => d,
            None => {
                log::error!(
                    "[Loopback] No virtual audio device found. \
                     Install BlackHole (macOS) or PipeWire (Linux)."
                );
                active.store(false, Ordering::Relaxed);
                return;
            }
        }
    };

    let config = match device.default_input_config() {
        Ok(c) => c,
        Err(e) => {
            log::error!("[Loopback] Failed to get input config: {}", e);
            active.store(false, Ordering::Relaxed);
            return;
        }
    };

    let channels = config.channels() as usize;
    let device_rate = config.sample_rate().0;
    let sample_format = config.sample_format();

    log::info!(
        "[Loopback] cpal capture started: {}Hz {}ch",
        device_rate, channels
    );

    let resampler = if device_rate != TARGET_RATE {
        match crate::engine::RubatoResampler::new(device_rate, TARGET_RATE, 1) {
            Ok(r) => Some(Arc::new(Mutex::new(r))),
            Err(e) => {
                log::error!("[Loopback] Failed to create resampler: {}", e);
                None
            }
        }
    } else {
        None
    };

    let err_fn = |err: cpal::StreamError| {
        log::error!("[Loopback] Stream error: {}", err);
    };

    let buf_clone = buffer.clone();
    let active_clone = active.clone();
    let resampler_clone = resampler.clone();

    let stream_result = match sample_format {
        cpal::SampleFormat::F32 => device.build_input_stream(
            &config.into(),
            move |data: &[f32], _: &cpal::InputCallbackInfo| {
                push_to_buffer(data, channels, device_rate, &resampler_clone, &buf_clone);
            },
            err_fn,
            None,
        ),
        cpal::SampleFormat::I16 => device.build_input_stream(
            &config.into(),
            move |data: &[i16], _: &cpal::InputCallbackInfo| {
                let f32_data: Vec<f32> = data.iter().map(|&s| s as f32 / 32768.0).collect();
                push_to_buffer(&f32_data, channels, device_rate, &resampler_clone, &buf_clone);
            },
            err_fn,
            None,
        ),
        fmt => {
            log::error!("[Loopback] Unsupported sample format: {:?}", fmt);
            active.store(false, Ordering::Relaxed);
            return;
        }
    };

    match stream_result {
        Ok(stream) => {
            if let Err(e) = stream.play() {
                log::error!("[Loopback] Failed to start stream: {}", e);
                active.store(false, Ordering::Relaxed);
                return;
            }

            while active_clone.load(Ordering::Relaxed) {
                std::thread::sleep(std::time::Duration::from_millis(100));
            }

            drop(stream);
            log::info!("[Loopback] Stopped");
        }
        Err(e) => {
            log::error!("[Loopback] Failed to build stream: {}", e);
            active.store(false, Ordering::Relaxed);
        }
    }
}
