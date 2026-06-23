/// Port of UlunasProcessor.kt - ONNX Runtime AI denoiser.
/// Uses a 960-sample STFT frame with 480-sample hop, running ONNX inference per frame.
pub struct UlunasProcessor {
    session: ort::session::Session,
    frame_size: usize,   // 960
    hop_length: usize,   // 480
    window: Vec<f32>,
    ola_gain: f32,
    previous: Vec<f32>,
    ola_accumulator: Vec<f32>,
    state_data: Vec<Vec<f32>>,
    state_shapes: Vec<Vec<usize>>,
}

impl UlunasProcessor {
    pub fn new(model_path: &str) -> Result<Self, Box<dyn std::error::Error>> {
        let frame_size = 960;
        let hop_length = 480;

        let session = ort::session::Session::builder()?
            .with_intra_threads(1)?
            .with_inter_threads(1)?
            .commit_from_file(model_path)?;

        let window = Self::hanning_window(frame_size);
        let ola_gain = Self::calc_ola_gain(&window, hop_length);

        let state_shapes: Vec<Vec<usize>> = vec![
            vec![1, 1, 2, 121], vec![1, 24, 1, 61], vec![1, 24, 1, 31],
            vec![1, 1, 24], vec![1, 1, 48], vec![1, 1, 48],
            vec![1, 1, 64], vec![1, 1, 32], vec![1, 31, 16],
            vec![1, 31, 16], vec![1, 24, 1, 31], vec![1, 12, 1, 31],
            vec![1, 12, 2, 61], vec![1, 1, 64], vec![1, 1, 48],
            vec![1, 1, 48], vec![1, 1, 24], vec![1, 1, 2],
        ];

        let state_data: Vec<Vec<f32>> = state_shapes
            .iter()
            .map(|shape| vec![0.0f32; shape.iter().product()])
            .collect();

        Ok(Self {
            session,
            frame_size,
            hop_length,
            window,
            ola_gain,
            previous: vec![0.0; hop_length],
            ola_accumulator: vec![0.0; frame_size],
            state_data,
            state_shapes,
        })
    }

    fn hanning_window(size: usize) -> Vec<f32> {
        (0..size)
            .map(|i| {
                let v = 0.5 - 0.5 * (2.0 * std::f64::consts::PI * i as f64 / (size - 1) as f64).cos();
                v.sqrt() as f32
            })
            .collect()
    }

    fn calc_ola_gain(window: &[f32], hop_length: usize) -> f32 {
        let mut sum_sq = 0.0_f32;
        for i in 0..hop_length {
            let w1 = window[i];
            let w2 = window[i + hop_length];
            sum_sq += w1 * w1 + w2 * w2;
        }
        let avg = sum_sq / hop_length as f32;
        if avg > 0.001 { 1.0 / avg.sqrt() } else { 1.0 }
    }

    pub fn process(&mut self, input: &[f32]) -> Vec<f32> {
        if input.len() != self.hop_length {
            return input.to_vec();
        }

        let frame_size = self.frame_size;
        let hop_length = self.hop_length;
        let spec_size = frame_size / 2 + 1;

        let mut fft_buffer = vec![0.0_f32; frame_size];
        fft_buffer[..hop_length].copy_from_slice(&self.previous);
        fft_buffer[hop_length..].copy_from_slice(input);
        self.previous.copy_from_slice(input);

        for i in 0..frame_size {
            fft_buffer[i] *= self.window[i];
        }

        use rustfft::FftPlanner;
        use rustfft::num_complex::Complex;

        let mut planner = FftPlanner::new();
        let fft = planner.plan_fft_forward(frame_size);

        let mut complex_buf: Vec<Complex<f32>> = fft_buffer
            .iter()
            .map(|&v| Complex::new(v, 0.0))
            .collect();
        fft.process(&mut complex_buf);

        let mut spec_flat = vec![0.0f32; spec_size * 2];
        for i in 0..spec_size {
            spec_flat[i * 2] = complex_buf[i].re;
            spec_flat[i * 2 + 1] = complex_buf[i].im;
        }

        let spec_shape = vec![1, spec_size, 1, 2];
        let val_spec = ort::value::Value::from_array((spec_shape, spec_flat)).unwrap();

        let val_states: Vec<_> = self.state_data.iter().zip(self.state_shapes.iter())
            .map(|(data, shape)| ort::value::Value::from_array((shape.clone(), data.clone())).unwrap())
            .collect();

        let outputs = match self.session.run(ort::inputs![
            &val_spec,
            &val_states[0], &val_states[1], &val_states[2], &val_states[3],
            &val_states[4], &val_states[5], &val_states[6], &val_states[7],
            &val_states[8], &val_states[9], &val_states[10], &val_states[11],
            &val_states[12], &val_states[13], &val_states[14], &val_states[15],
            &val_states[16], &val_states[17]
        ]) {
            Ok(o) => o,
            Err(e) => {
                eprintln!("ONNX inference failed: {}", e);
                return input.to_vec();
            }
        };

        if let Ok(output_tensor) = outputs[0].try_extract_tensor::<f32>() {
            let output_data = output_tensor.1;
            for i in 0..spec_size {
                complex_buf[i] = Complex::new(output_data[i * 2], output_data[i * 2 + 1]);
            }
            for i in spec_size..frame_size {
                complex_buf[i] = complex_buf[frame_size - i].conj();
            }
        }

        for i in 1..outputs.len().min(19) {
            if let Ok(state_tensor) = outputs[i].try_extract_tensor::<f32>() {
                let state_data = state_tensor.1;
                if i - 1 < self.state_data.len() && state_data.len() == self.state_data[i - 1].len() {
                    self.state_data[i - 1].copy_from_slice(state_data);
                }
            }
        }

        let ifft = planner.plan_fft_inverse(frame_size);
        ifft.process(&mut complex_buf);
        let scale = 1.0 / frame_size as f32;
        for i in 0..frame_size {
            fft_buffer[i] = complex_buf[i].re * scale * self.window[i];
        }

        for i in 0..frame_size {
            self.ola_accumulator[i] += fft_buffer[i];
        }

        let mut output = vec![0.0_f32; hop_length];
        for i in 0..hop_length {
            output[i] = self.ola_accumulator[i] * self.ola_gain;
        }

        for i in 0..frame_size - hop_length {
            self.ola_accumulator[i] = self.ola_accumulator[i + hop_length];
        }
        for i in frame_size - hop_length..frame_size {
            self.ola_accumulator[i] = 0.0;
        }

        output
    }
}
