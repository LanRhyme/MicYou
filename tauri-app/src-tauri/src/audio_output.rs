use std::sync::mpsc::{self, Sender};
use std::sync::Arc;

/// Persistent audio output device thread.
///
/// `cpal::Stream` is deliberately `!Send + !Sync`, so the
/// `AudioOutputManager` can never live inside the shared `ServerState`.
/// Instead a dedicated thread owns it for the whole process lifetime and
/// receives commands over an mpsc channel. The device is opened at app
/// startup (GUI) or on the first server start (CLI/TUI) and only closed when
/// the process exits — server stop and phone connect/disconnect never tear it
/// down.
enum AudioOutputCommand {
    Open(Option<String>, usize, Sender<bool>),
    Push(Vec<f32>, usize),
    SetMonitoring(bool),
    Queued(Sender<usize>),
    Shutdown,
}

pub struct AudioOutputHandle {
    tx: Sender<AudioOutputCommand>,
}

impl Default for AudioOutputHandle {
    fn default() -> Self {
        let (tx, rx) = mpsc::channel::<AudioOutputCommand>();
        std::thread::spawn(move || {
            let mut manager = micyou_audio::AudioOutputManager::new();
            loop {
                match rx.recv() {
                    Ok(AudioOutputCommand::Open(device, buffer_ms, reply)) => {
                        let ok = if manager.is_open() {
                            true
                        } else {
                            match manager.start(device, buffer_ms) {
                                Ok(()) => {
                                    log::info!("[Audio] Output device opened");
                                    true
                                }
                                Err(e) => {
                                    eprintln!("[Audio] Failed to open output device: {}", e);
                                    false
                                }
                            }
                        };
                        let _ = reply.send(ok);
                    }
                    Ok(AudioOutputCommand::Push(data, channels)) => {
                        manager.push_audio_data(&data, channels);
                    }
                    Ok(AudioOutputCommand::SetMonitoring(enabled)) => {
                        manager.set_monitoring(enabled);
                    }
                    Ok(AudioOutputCommand::Queued(reply)) => {
                        let _ = reply.send(manager.queued_samples());
                    }
                    Ok(AudioOutputCommand::Shutdown) | Err(_) => {
                        manager.close();
                        break;
                    }
                }
            }
        });
        Self { tx }
    }
}

impl AudioOutputHandle {
    /// Spawn the persistent device thread and return a shared handle.
    pub fn spawn() -> Arc<Self> {
        Arc::new(Self::default())
    }

    /// Blocking open of the output device. Idempotent: returns immediately if
    /// the stream is already open.
    pub fn ensure_open(&self, device: Option<String>, buffer_ms: usize) -> bool {
        let (reply_tx, reply_rx) = mpsc::channel();
        if self
            .tx
            .send(AudioOutputCommand::Open(device, buffer_ms, reply_tx))
            .is_err()
        {
            return false;
        }
        reply_rx.recv().unwrap_or(false)
    }

    /// Push decoded PCM into the output ring buffer. The channel is unbounded,
    /// so this never blocks or drops audio while the device thread lives.
    pub fn push(&self, data: Vec<f32>, channels: usize) {
        let _ = self.tx.send(AudioOutputCommand::Push(data, channels));
    }

    pub fn set_monitoring(&self, enabled: bool) {
        let _ = self.tx.send(AudioOutputCommand::SetMonitoring(enabled));
    }

    /// Samples currently queued in the output ring buffer.
    pub fn queued_samples(&self) -> usize {
        let (reply_tx, reply_rx) = mpsc::channel();
        if self.tx.send(AudioOutputCommand::Queued(reply_tx)).is_err() {
            return 0;
        }
        reply_rx.recv().unwrap_or(0)
    }

    /// Close the output stream and stop the device thread. Only called when
    /// the process is exiting.
    pub fn shutdown(&self) {
        let _ = self.tx.send(AudioOutputCommand::Shutdown);
    }
}
