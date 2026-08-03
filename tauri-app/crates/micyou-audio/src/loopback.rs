use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};

use ringbuf::{HeapRb, Rb};

use crate::AecFailure;

const RING_BUF_SEC: usize = 2;
const TARGET_RATE: u32 = 48000;
const MAX_REFERENCE_LAG_SAMPLES: usize = TARGET_RATE as usize * 300 / 1000;

/// Cross-platform speaker loopback capture for AEC far-end reference.
///
/// - Windows: WASAPI loopback on default render device (no virtual device needed)
/// - macOS: cpal input from BlackHole
/// - Linux: pw-record on the default physical playback sink monitor
pub struct LoopbackCapture {
    active: Arc<AtomicBool>,
    buffer: Arc<Mutex<HeapRb<f32>>>,
    failure: Arc<Mutex<Option<AecFailure>>>,
    thread: Mutex<Option<std::thread::JoinHandle<()>>>,
}

impl LoopbackCapture {
    pub fn new() -> Self {
        Self {
            active: Arc::new(AtomicBool::new(false)),
            buffer: Arc::new(Mutex::new(HeapRb::new(TARGET_RATE as usize * RING_BUF_SEC))),
            failure: Arc::new(Mutex::new(None)),
            thread: Mutex::new(None),
        }
    }

    pub fn start(&self) -> Result<(), AecFailure> {
        if self.active.load(Ordering::Relaxed) {
            return Ok(());
        }

        // A previous stop may have requested shutdown while the capture
        // thread is still winding down. Join it before reusing the shared
        // state; otherwise it can observe the new `true` flag and keep
        // running alongside the new capture thread.
        let previous_thread = lock(&self.thread).take();
        if let Some(thread) = previous_thread {
            let _ = thread.join();
        }

        *lock(&self.failure) = None;
        lock(&self.buffer).clear();
        self.active.store(true, Ordering::Relaxed);

        let active = self.active.clone();
        let buffer = self.buffer.clone();
        let failure = self.failure.clone();

        let spawned = std::thread::Builder::new()
            .name("SpeakerLoopback".into())
            .spawn(move || {
                #[cfg(target_os = "windows")]
                wasapi_loopback_thread(active, buffer, failure);
                #[cfg(target_os = "linux")]
                pipewire_loopback_thread(active, buffer, failure);
                #[cfg(target_os = "macos")]
                cpal_capture_thread(active, buffer, failure);
                #[cfg(not(any(target_os = "windows", target_os = "linux", target_os = "macos")))]
                {
                    set_failure(&failure, AecFailure::ReferenceLost);
                    active.store(false, Ordering::Relaxed);
                }
            });

        match spawned {
            Ok(thread) => {
                *lock(&self.thread) = Some(thread);
                Ok(())
            }
            Err(error) => {
                log::error!("[Loopback] Failed to spawn capture thread: {error}");
                self.active.store(false, Ordering::Relaxed);
                set_failure(&self.failure, AecFailure::ReferenceLost);
                Err(AecFailure::ReferenceLost)
            }
        }
    }

    pub fn stop(&self) {
        self.active.store(false, Ordering::Relaxed);

        // Wait for the capture stream to be dropped before the next start or
        // before the audio worker exits. This prevents overlapping cpal/WASAPI
        // streams and makes stop a complete lifecycle transition.
        let thread = lock(&self.thread).take();
        if let Some(thread) = thread {
            let _ = thread.join();
        }
    }

    /// Stop capture and discard stream-local state before a new transport session.
    pub fn reset_session(&self) {
        self.stop();
        *lock(&self.failure) = None;
        lock(&self.buffer).clear();
    }

    /// Read n_samples from the loopback buffer, consuming them.
    /// Returns None if insufficient data.
    pub fn read(&self, n_samples: usize) -> Option<Vec<f32>> {
        let mut buf = lock(&self.buffer);
        if buf.len() < n_samples {
            return None;
        }

        // If the near-end stream paused while playback continued, discard old
        // reference audio instead of preserving a permanent multi-second lag.
        let stale_samples = buf
            .len()
            .saturating_sub(MAX_REFERENCE_LAG_SAMPLES + n_samples);
        for _ in 0..stale_samples {
            buf.pop();
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

    /// Takes the most recent capture failure so one failed attempt is handled once.
    pub fn take_failure_reason(&self) -> Option<AecFailure> {
        lock(&self.failure).take()
    }
}

impl Default for LoopbackCapture {
    fn default() -> Self {
        Self::new()
    }
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn set_failure(failure: &Mutex<Option<AecFailure>>, reason: AecFailure) {
    *lock(failure) = Some(reason);
}

// ─── Helper: downmix + resample + push to buffer ─────────────────────────

#[cfg(any(target_os = "windows", target_os = "macos"))]
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
fn wasapi_loopback_thread(
    active: Arc<AtomicBool>,
    buffer: Arc<Mutex<HeapRb<f32>>>,
    failure: Arc<Mutex<Option<AecFailure>>>,
) {
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
            device_rate,
            channels
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
                    push_to_buffer(&f32_samples, channels, device_rate, &resampler, &buffer);
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
        set_failure(&failure, AecFailure::ReferenceLost);
    }
    active.store(false, Ordering::Relaxed);
}

// ─── Linux: PipeWire capture from the physical playback sink ──────────────

#[cfg(target_os = "linux")]
fn pipewire_loopback_thread(
    active: Arc<AtomicBool>,
    buffer: Arc<Mutex<HeapRb<f32>>>,
    failure: Arc<Mutex<Option<AecFailure>>>,
) {
    use std::io::Read;
    use std::process::{Command, Stdio};

    // Capture the monitor of the current default playback sink. MicYouVirtualMic
    // contains MicYou's own microphone output and is therefore not a valid AEC
    // far-end reference.
    let mut child = match Command::new("pw-record")
        .args([
            "--target",
            "@DEFAULT_AUDIO_SINK@",
            "--properties",
            "{ stream.capture.sink = true node.name = MicYouAecCapture }",
            "--latency",
            "10ms",
            "--rate",
            "48000",
            "--channels",
            "1",
            "--format",
            "f32",
            "--raw",
            "-",
        ])
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
    {
        Ok(child) => child,
        Err(error) => {
            log::error!("[Loopback] Failed to start pw-record: {}", error);
            set_failure(&failure, AecFailure::PipeWireUnavailable);
            active.store(false, Ordering::Relaxed);
            return;
        }
    };

    let Some(mut stdout) = child.stdout.take() else {
        log::error!("[Loopback] pw-record stdout is unavailable");
        let _ = child.kill();
        let _ = child.wait();
        set_failure(&failure, AecFailure::ReferenceLost);
        active.store(false, Ordering::Relaxed);
        return;
    };

    let reader_active = active.clone();
    let reader_buffer = buffer.clone();
    let reader_failure = failure.clone();
    let reader = std::thread::Builder::new()
        .name("PipeWireReferenceReader".into())
        .spawn(move || {
            let mut read_buffer = [0_u8; 4096];
            let mut pending = Vec::with_capacity(read_buffer.len() + 3);

            loop {
                match stdout.read(&mut read_buffer) {
                    Ok(0) => {
                        if reader_active.load(Ordering::Relaxed) {
                            set_failure(&reader_failure, AecFailure::ReferenceLost);
                            reader_active.store(false, Ordering::Relaxed);
                        }
                        break;
                    }
                    Ok(count) => {
                        pending.extend_from_slice(&read_buffer[..count]);
                        let complete_bytes = pending.len() / 4 * 4;
                        if complete_bytes == 0 {
                            continue;
                        }

                        if let Ok(mut ring) = reader_buffer.lock() {
                            for bytes in pending[..complete_bytes].chunks_exact(4) {
                                ring.push_overwrite(f32::from_ne_bytes([
                                    bytes[0], bytes[1], bytes[2], bytes[3],
                                ]));
                            }
                        }
                        pending.drain(..complete_bytes);
                    }
                    Err(error) => {
                        if reader_active.load(Ordering::Relaxed) {
                            log::error!("[Loopback] Failed to read PipeWire reference: {}", error);
                            set_failure(&reader_failure, AecFailure::ReferenceLost);
                            reader_active.store(false, Ordering::Relaxed);
                        }
                        break;
                    }
                }
            }
        });

    let reader = match reader {
        Ok(reader) => reader,
        Err(error) => {
            log::error!("[Loopback] Failed to spawn PipeWire reader: {}", error);
            let _ = child.kill();
            let _ = child.wait();
            set_failure(&failure, AecFailure::ReferenceLost);
            active.store(false, Ordering::Relaxed);
            return;
        }
    };

    log::info!("[Loopback] PipeWire default playback monitor started");
    while active.load(Ordering::Relaxed) {
        match child.try_wait() {
            Ok(Some(status)) => {
                log::error!("[Loopback] pw-record exited unexpectedly: {}", status);
                set_failure(&failure, AecFailure::ReferenceLost);
                active.store(false, Ordering::Relaxed);
                break;
            }
            Ok(None) => std::thread::sleep(std::time::Duration::from_millis(20)),
            Err(error) => {
                log::error!("[Loopback] Failed to inspect pw-record: {}", error);
                set_failure(&failure, AecFailure::ReferenceLost);
                active.store(false, Ordering::Relaxed);
                break;
            }
        }
    }

    if child.try_wait().ok().flatten().is_none() {
        let _ = child.kill();
    }
    let _ = child.wait();
    let _ = reader.join();
    active.store(false, Ordering::Relaxed);
    log::info!("[Loopback] PipeWire default playback monitor stopped");
}

// ─── macOS: cpal capture from BlackHole ──────────────────────────────────

#[cfg(target_os = "macos")]
fn cpal_capture_thread(
    active: Arc<AtomicBool>,
    buffer: Arc<Mutex<HeapRb<f32>>>,
    failure: Arc<Mutex<Option<AecFailure>>>,
) {
    use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};

    let host = cpal::default_host();

    let device = {
        let mut found = None;
        if let Ok(devices) = host.input_devices() {
            'outer: for dev in devices {
                if let Ok(name) = dev.name() {
                    let lower = name.to_lowercase();
                    let matches = lower.contains("blackhole");
                    if matches {
                        log::info!("[Loopback] Found virtual device: '{}'", name);
                        found = Some(dev);
                        break 'outer;
                    }
                }
            }
        }
        match found {
            Some(d) => d,
            None => {
                log::error!(
                    "[Loopback] No virtual audio device found. \
                     Install BlackHole (macOS) or start MicYou PipeWire routing (Linux)."
                );
                set_failure(&failure, AecFailure::VirtualSourceMissing);
                active.store(false, Ordering::Relaxed);
                return;
            }
        }
    };

    let config = match device.default_input_config() {
        Ok(c) => c,
        Err(e) => {
            log::error!("[Loopback] Failed to get input config: {}", e);
            set_failure(&failure, AecFailure::ReferenceLost);
            active.store(false, Ordering::Relaxed);
            return;
        }
    };

    let channels = config.channels() as usize;
    let device_rate = config.sample_rate().0;
    let sample_format = config.sample_format();

    log::info!(
        "[Loopback] cpal capture started: {}Hz {}ch",
        device_rate,
        channels
    );

    let resampler = if device_rate != TARGET_RATE {
        match crate::engine::RubatoResampler::new(device_rate, TARGET_RATE, 1) {
            Ok(r) => Some(Arc::new(Mutex::new(r))),
            Err(e) => {
                log::error!("[Loopback] Failed to create resampler: {}", e);
                set_failure(&failure, AecFailure::ReferenceLost);
                None
            }
        }
    } else {
        None
    };

    let stream_active = active.clone();
    let stream_failure = failure.clone();
    let err_fn = move |err: cpal::StreamError| {
        log::error!("[Loopback] Stream error: {}", err);
        set_failure(&stream_failure, AecFailure::ReferenceLost);
        stream_active.store(false, Ordering::Relaxed);
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
                push_to_buffer(
                    &f32_data,
                    channels,
                    device_rate,
                    &resampler_clone,
                    &buf_clone,
                );
            },
            err_fn,
            None,
        ),
        fmt => {
            log::error!("[Loopback] Unsupported sample format: {:?}", fmt);
            set_failure(&failure, AecFailure::ReferenceLost);
            active.store(false, Ordering::Relaxed);
            return;
        }
    };

    match stream_result {
        Ok(stream) => {
            if let Err(e) = stream.play() {
                log::error!("[Loopback] Failed to start stream: {}", e);
                set_failure(&failure, AecFailure::ReferenceLost);
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
            set_failure(&failure, AecFailure::ReferenceLost);
            active.store(false, Ordering::Relaxed);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn capture_failure_is_consumed_once() {
        let capture = LoopbackCapture::new();
        set_failure(&capture.failure, AecFailure::VirtualSourceMissing);

        assert_eq!(
            capture.take_failure_reason(),
            Some(AecFailure::VirtualSourceMissing)
        );
        assert_eq!(capture.take_failure_reason(), None);
    }

    #[test]
    fn reference_reads_follow_requested_near_end_frame_count() {
        let capture = LoopbackCapture::new();
        {
            let mut buffer = capture.buffer.lock().unwrap();
            for sample in 0..700 {
                buffer.push(sample as f32).unwrap();
            }
        }

        let reference = capture.read(660).unwrap();
        assert_eq!(reference.len(), 660);
        assert_eq!(reference.first().copied(), Some(0.0));
        assert_eq!(reference.last().copied(), Some(659.0));
        assert!(capture.read(480).is_none());
        assert_eq!(capture.buffer.lock().unwrap().len(), 40);
    }

    #[test]
    fn reference_read_discards_excessive_lag() {
        let capture = LoopbackCapture::new();
        let sample_count = MAX_REFERENCE_LAG_SAMPLES + 960;
        {
            let mut buffer = capture.buffer.lock().unwrap();
            for sample in 0..sample_count {
                buffer.push(sample as f32).unwrap();
            }
        }

        let reference = capture.read(480).unwrap();

        assert_eq!(reference.first(), Some(&480.0));
        assert_eq!(reference.last(), Some(&959.0));
        assert_eq!(
            capture.buffer.lock().unwrap().len(),
            MAX_REFERENCE_LAG_SAMPLES
        );
    }

    #[test]
    fn reset_session_clears_buffer_and_stale_failure() {
        let capture = LoopbackCapture::new();
        capture.buffer.lock().unwrap().push(1.0).unwrap();
        set_failure(&capture.failure, AecFailure::ReferenceLost);

        capture.reset_session();

        assert_eq!(capture.buffer.lock().unwrap().len(), 0);
        assert_eq!(capture.take_failure_reason(), None);
        assert!(!capture.is_active());
    }
}
