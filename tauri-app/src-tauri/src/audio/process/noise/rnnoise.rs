#[cfg(feature = "dsp")]
use nnnoiseless::DenoiseState;

const RNNOISE_FRAME_SIZE: usize = 480;

#[cfg(feature = "dsp")]
pub struct RnnoiseDenoiser {
    denoiser_left: Box<DenoiseState<'static>>,
    denoiser_right: Box<DenoiseState<'static>>,
    buffer_left: Vec<f32>,
    buffer_right: Vec<f32>,
}

#[cfg(feature = "dsp")]
impl RnnoiseDenoiser {
    pub fn new() -> Self {
        Self {
            denoiser_left: DenoiseState::new(),
            denoiser_right: DenoiseState::new(),
            buffer_left: Vec::with_capacity(RNNOISE_FRAME_SIZE * 2),
            buffer_right: Vec::with_capacity(RNNOISE_FRAME_SIZE * 2),
        }
    }

    pub fn process(&mut self, data: &mut Vec<f32>, channels: usize, intensity: f32) {
        if data.is_empty() || channels == 0 {
            return;
        }

        if channels >= 2 {
            let frames = data.len() / 2;
            let mut left: Vec<f32> = Vec::with_capacity(frames);
            let mut right: Vec<f32> = Vec::with_capacity(frames);
            for i in 0..frames {
                left.push(data[i * 2]);
                right.push(data[i * 2 + 1]);
            }

            Self::process_single(&mut left, &mut self.buffer_left, &mut self.denoiser_left, intensity);
            Self::process_single(&mut right, &mut self.buffer_right, &mut self.denoiser_right, intensity);

            data.clear();
            for i in 0..frames {
                data.push(left[i]);
                data.push(right[i]);
            }
        } else {
            Self::process_single(data, &mut self.buffer_left, &mut self.denoiser_left, intensity);
        }
    }

    fn process_single(
        data: &mut Vec<f32>,
        buffer: &mut Vec<f32>,
        denoiser: &mut DenoiseState<'static>,
        intensity: f32,
    ) {
        let mix = (intensity / 100.0).clamp(0.0, 1.0);
        let input_len = data.len();
        buffer.extend_from_slice(data);

        let mut output = Vec::with_capacity(input_len);

        while buffer.len() >= RNNOISE_FRAME_SIZE {
            let frame: Vec<f32> = buffer.drain(..RNNOISE_FRAME_SIZE).collect();
            let input_frame: Vec<f32> = frame.iter().map(|s| s * 32767.0).collect();
            let mut output_frame = vec![0.0f32; RNNOISE_FRAME_SIZE];

            let _vad_prob = denoiser.process_frame(&mut output_frame, &input_frame);

            for i in 0..RNNOISE_FRAME_SIZE {
                let clean = output_frame[i] / 32767.0;
                let original = frame[i];
                output.push(original * (1.0 - mix) + clean * mix);
            }
        }

        for sample in buffer.drain(..) {
            output.push(sample);
        }

        output.truncate(input_len);
        while output.len() < input_len {
            output.push(0.0);
        }

        *data = output;
    }
}
