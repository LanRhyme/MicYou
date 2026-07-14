#[cfg(feature = "dsp")]
use nnnoiseless::DenoiseState;

/// RNNoise frame size in samples (10ms at 48kHz).
pub const RNNOISE_FRAME_SIZE: usize = 480;

/// RNNoise-based noise suppressor using `nnnoiseless`.
#[cfg(feature = "dsp")]
pub struct RnnoiseDenoiser {
    denoiser: Box<DenoiseState<'static>>,
    ns_buffer: Vec<f32>,
}

#[cfg(feature = "dsp")]
impl RnnoiseDenoiser {
    pub fn new() -> Self {
        Self {
            denoiser: DenoiseState::new(),
            ns_buffer: Vec::with_capacity(RNNOISE_FRAME_SIZE * 2),
        }
    }

    /// Process a single channel of audio through RNNoise.
    ///
    /// `intensity` 0..100 controls wet/dry mix.
    pub fn process(&mut self, data: &mut Vec<f32>, intensity: f32) {
        let mix = (intensity / 100.0).clamp(0.0, 1.0);
        let input_len = data.len();
        self.ns_buffer.extend_from_slice(data);

        let mut output = Vec::with_capacity(input_len);

        while self.ns_buffer.len() >= RNNOISE_FRAME_SIZE {
            let frame: Vec<f32> = self.ns_buffer.drain(..RNNOISE_FRAME_SIZE).collect();

            let input_frame: Vec<f32> = frame.iter().map(|s| s * 32767.0).collect();
            let mut output_frame = vec![0.0f32; RNNOISE_FRAME_SIZE];

            let _vad_prob = self.denoiser.process_frame(&mut output_frame, &input_frame);

            for i in 0..RNNOISE_FRAME_SIZE {
                let clean = output_frame[i] / 32767.0;
                let original = frame[i];
                output.push(original * (1.0 - mix) + clean * mix);
            }
        }

        for sample in self.ns_buffer.drain(..) {
            output.push(sample);
        }

        output.truncate(input_len);
        while output.len() < input_len {
            output.push(0.0);
        }

        *data = output;
    }
}

#[cfg(not(feature = "dsp"))]
pub struct RnnoiseDenoiser;

#[cfg(not(feature = "dsp"))]
impl RnnoiseDenoiser {
    pub fn new() -> Self {
        Self
    }
    pub fn process(&mut self, _data: &mut Vec<f32>, _intensity: f32) {}
}
