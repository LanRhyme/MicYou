//! Sound effect mixer for the plugin `audio.play` capability
//!
//! Short sound effects (soundpads) are mixed into the virtual microphone
//! output stream instead of being played on a separate system device
//! This way the effect is heard by the remote peer (and the user when
//! monitoring is on) exactly like real microphone audio
//!
//! The mixer lives inside `AudioOutputManager` which is owned by a single
//! device thread, so `add` and `mix_into` are never called concurrently

/// One queued sound effect playback
#[derive(Debug)]
pub struct SoundEffect {
    samples: Vec<f32>,
    pos: usize,
    gain: f32,
}

/// Multi-voice mixer of short one-shot effects
#[derive(Debug, Default)]
pub struct SoundMixer {
    effects: Vec<SoundEffect>,
}

impl SoundMixer {
    pub fn new() -> Self {
        Self {
            effects: Vec::new(),
        }
    }

    /// Queue a mono effect for playback, starting on the next mix call
    pub fn add(&mut self, samples: Vec<f32>, gain: f32) {
        if samples.is_empty() {
            return;
        }
        self.effects.push(SoundEffect {
            samples,
            pos: 0,
            gain: gain.clamp(0.0, 4.0),
        });
    }

    /// Whether any effect is still playing
    pub fn is_playing(&self) -> bool {
        !self.effects.is_empty()
    }

    /// Number of queued effects
    pub fn len(&self) -> usize {
        self.effects.len()
    }

    pub fn is_empty(&self) -> bool {
        self.effects.is_empty()
    }

    /// Mix all playing mono effects into `data` (interleaved, `channels` per
    /// frame), advancing each effect's play position
    /// Effects that finish are removed
    pub fn mix_into(&mut self, data: &mut [f32], channels: usize) {
        if self.effects.is_empty() || channels == 0 {
            return;
        }
        let frames = data.len() / channels;
        if frames == 0 {
            return;
        }
        let mut finished: Vec<usize> = Vec::new();
        for (idx, fx) in self.effects.iter_mut().enumerate() {
            let remaining = fx.samples.len().saturating_sub(fx.pos);
            if remaining == 0 {
                finished.push(idx);
                continue;
            }
            let n = remaining.min(frames);
            let gain = fx.gain;
            for f in 0..n {
                let v = fx.samples[fx.pos + f] * gain;
                for ch in 0..channels {
                    data[f * channels + ch] += v;
                }
            }
            fx.pos += n;
            if fx.pos >= fx.samples.len() {
                finished.push(idx);
            }
        }
        // Remove finished effects from the back so indices stay valid
        for idx in finished.into_iter().rev() {
            self.effects.remove(idx);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mix_into_stereo_data() {
        let mut mixer = SoundMixer::new();
        // One second of a 0.5-amplitude DC tone (100 samples mono)
        let samples: Vec<f32> = vec![0.5; 100];
        mixer.add(samples, 1.0);

        // 50 frames of stereo silence -> effect mixes first 50 samples
        let mut data = vec![0.0f32; 100];
        mixer.mix_into(&mut data, 2);
        for i in 0..50 {
            assert_eq!(data[i * 2], 0.5);
            assert_eq!(data[i * 2 + 1], 0.5);
        }
        assert!(mixer.is_playing());
        // Remaining 50 samples still queued
        assert_eq!(mixer.len(), 1);
    }

    #[test]
    fn effect_finishes_and_is_removed() {
        let mut mixer = SoundMixer::new();
        mixer.add(vec![0.1f32; 10], 1.0);
        let mut data = vec![0.0f32; 10]; // 10 frames mono
        mixer.mix_into(&mut data, 1);
        assert!(!mixer.is_playing());
        assert!(mixer.is_empty());
    }

    #[test]
    fn gain_is_applied_and_clamped() {
        let mut mixer = SoundMixer::new();
        mixer.add(vec![1.0f32; 4], 0.5);
        let mut data = vec![0.0f32; 4];
        mixer.mix_into(&mut data, 1);
        assert!((data[0] - 0.5).abs() < 1e-6);

        let mut mixer = SoundMixer::new();
        mixer.add(vec![1.0f32; 4], 99.0); // clamps to 4.0
        let mut data = vec![0.0f32; 4];
        mixer.mix_into(&mut data, 1);
        assert!((data[0] - 4.0).abs() < 1e-6);
    }

    #[test]
    fn longer_effect_than_frame_spans_frames() {
        let mut mixer = SoundMixer::new();
        mixer.add(vec![0.2f32; 100], 1.0);
        // First call: 10 frames
        let mut d1 = vec![0.0f32; 10];
        mixer.mix_into(&mut d1, 1);
        assert_eq!(d1[0], 0.2);
        // Second call: 10 more frames, effect position advanced
        let mut d2 = vec![0.0f32; 10];
        mixer.mix_into(&mut d2, 1);
        assert_eq!(d2[0], 0.2);
        assert_eq!(mixer.len(), 1);
        assert_eq!(mixer.effects[0].pos, 20);
    }
}
