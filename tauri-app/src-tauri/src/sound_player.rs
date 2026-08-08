//! Sound playback for the plugin `audio.play` capability
//!
//! Sound effects are mixed into the virtual microphone output stream
//! (`AudioOutputHandle` -> `AudioOutputManager` mixer) so the remote peer
//! hears them exactly like real mic audio, and the user hears them through
//! monitoring
//! No separate cpal stream is ever opened, so playback can never stall or
//! deadlock the audio stack

use std::sync::Arc;

/// Plays WAV files into the virtual microphone output
pub struct SoundPlayer {
    output: Arc<crate::audio_output::AudioOutputHandle>,
}

impl SoundPlayer {
    /// Create a shared player bound to the persistent output device handle
    pub fn new(output: Arc<crate::audio_output::AudioOutputHandle>) -> Arc<Self> {
        Arc::new(Self { output })
    }

    /// Queue a WAV file for playback, returning once parsing succeeds
    /// Mixing happens on the output device thread; this never blocks
    pub fn play_wav(&self, path: &str) -> micyou_plugin::PluginResult<()> {
        let (samples, _sample_rate) = parse_wav(path)
            .map_err(|e| micyou_plugin::PluginError::Runtime(format!("wav parse: {e}")))?;
        if samples.is_empty() {
            return Err(micyou_plugin::PluginError::Runtime("empty wav data".into()));
        }
        self.output.push_sound(samples, 1.0);
        Ok(())
    }
}

/// Parse a RIFF/WAVE file into mono f32 samples (multi-channel is averaged)
fn parse_wav(path: &str) -> Result<(Vec<f32>, u32), String> {
    let bytes = std::fs::read(path).map_err(|e| e.to_string())?;
    if bytes.len() < 12 || &bytes[0..4] != b"RIFF" || &bytes[8..12] != b"WAVE" {
        return Err("not a RIFF/WAVE file".into());
    }
    let mut offset = 12usize;
    let mut sample_rate = 0u32;
    let mut channels = 0u16;
    let mut bits = 0u16;
    let mut data: Vec<u8> = Vec::new();
    while offset + 8 <= bytes.len() {
        let id = &bytes[offset..offset + 4];
        let size = u32::from_le_bytes(bytes[offset + 4..offset + 8].try_into().unwrap()) as usize;
        let body = offset + 8;
        if body + size > bytes.len() {
            break;
        }
        match id {
            b"fmt " if size >= 16 => {
                channels = u16::from_le_bytes(bytes[body + 2..body + 4].try_into().unwrap());
                sample_rate = u32::from_le_bytes(bytes[body + 4..body + 8].try_into().unwrap());
                bits = u16::from_le_bytes(bytes[body + 14..body + 16].try_into().unwrap());
            }
            b"data" => {
                data = bytes[body..body + size].to_vec();
            }
            _ => {}
        }
        offset = body + size + (size & 1);
    }
    if sample_rate == 0 || channels == 0 || data.is_empty() {
        return Err("missing fmt or data chunk".into());
    }
    let bytes_per_sample = (bits / 8) as usize;
    if bytes_per_sample == 0 {
        return Err(format!("unsupported bits_per_sample {bits}"));
    }
    let frames = data.len() / (bytes_per_sample * channels as usize);
    let mut samples = Vec::with_capacity(frames);
    for frame in 0..frames {
        let mut sum = 0f64;
        for ch in 0..channels as usize {
            let idx = (frame * channels as usize + ch) * bytes_per_sample;
            let raw = &data[idx..idx + bytes_per_sample];
            let value = match bits {
                16 => i16::from_le_bytes([raw[0], raw[1]]) as f64 / 32768.0,
                32 => f32::from_le_bytes([raw[0], raw[1], raw[2], raw[3]]) as f64,
                8 => (raw[0] as f64 - 128.0) / 128.0,
                _ => return Err(format!("unsupported bits_per_sample {bits}")),
            };
            sum += value;
        }
        samples.push((sum / channels as f64) as f32);
    }
    Ok((samples, sample_rate))
}
