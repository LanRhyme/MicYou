use std::sync::{Arc, RwLock};
use std::path::PathBuf;

#[cfg(feature = "dsp")]
use nnnoiseless::DenoiseState;

#[derive(Debug, Clone, serde::Deserialize, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EqualizerConfig {
    pub enabled: bool,
    pub pre_amp: f32,
    pub gains: Vec<f32>, // 10 bands
}

impl Default for EqualizerConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            pre_amp: 0.0,
            gains: vec![0.0; 10],
        }
    }
}

/// Audio DSP settings, synced from the frontend.
#[derive(Debug, Clone, serde::Deserialize, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AudioDspSettings {
    pub gain: f32,           // dB, -50 to +50
    pub ns_enabled: bool,
    pub ns_type: String,     // "RNNoise", "Ulunas", "Speexdsp", "None"
    pub ns_intensity: f32,   // 0..100
    pub dereverb_enabled: bool,
    pub dereverb_level: f32, // 0..100
    pub agc_enabled: bool,
    pub agc_target: f32,     // 0..32767
    pub agc_attack: f32,     // raw slider value 1..100, maps to 0.001..0.1
    pub agc_decay: f32,      // raw slider value 1..100, maps to 0.0001..0.01
    pub vad_enabled: bool,
    pub vad_threshold: f32,  // dB, -100..0
    
    #[serde(default)]
    pub processing_chain: Vec<String>,
    #[serde(default)]
    pub equalizer: EqualizerConfig,
}

impl Default for AudioDspSettings {
    fn default() -> Self {
        Self {
            gain: 0.0,
            ns_enabled: false,
            ns_type: "RNNoise".to_string(),
            ns_intensity: 50.0,
            dereverb_enabled: false,
            dereverb_level: 50.0,
            agc_enabled: false,
            agc_target: 16000.0,
            agc_attack: 50.0,
            agc_decay: 50.0,
            vad_enabled: false,
            vad_threshold: -40.0,
            processing_chain: vec![
                "NoiseReduction".to_string(),
                "Dereverb".to_string(),
                "Equalizer".to_string(),
                "Amplifier".to_string(),
                "AGC".to_string(),
                "VAD".to_string(),
            ],
            equalizer: EqualizerConfig::default(),
        }
    }
}

// ─── Ulunas ONNX Processor ─────────────────────────────────────────────────

/// Port of UlunasProcessor.kt - ONNX Runtime AI denoiser
#[cfg(feature = "noise-suppression")]
struct UlunasProcessor {
    session: ort::session::Session,
    frame_size: usize,   // 960
    hop_length: usize,   // 480
    window: Vec<f32>,
    ola_gain: f32,
    previous: Vec<f32>,
    ola_accumulator: Vec<f32>,
    state_data: Vec<Vec<f32>>,
    state_shapes: Vec<Vec<usize>>,
    fft_forward: std::sync::Arc<dyn rustfft::Fft<f32>>,
    fft_inverse: std::sync::Arc<dyn rustfft::Fft<f32>>,
}

#[cfg(feature = "noise-suppression")]
impl UlunasProcessor {
    fn new(model_path: &str) -> Result<Self, Box<dyn std::error::Error>> {
        let frame_size = 960;
        let hop_length = 480;

        let session = ort::session::Session::builder()?
            .with_intra_threads(1)?
            .with_inter_threads(1)?
            .commit_from_file(model_path)?;

        let window = Self::hanning_window(frame_size);
        let ola_gain = Self::calc_ola_gain(&window, hop_length);

        use rustfft::FftPlanner;
        let mut planner = FftPlanner::new();
        let fft_forward = planner.plan_fft_forward(frame_size);
        let fft_inverse = planner.plan_fft_inverse(frame_size);

        // State shapes
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
            fft_forward,
            fft_inverse,
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

    fn process(&mut self, input: &[f32]) -> Vec<f32> {
        if input.len() != self.hop_length {
            return input.to_vec();
        }

        let frame_size = self.frame_size;
        let hop_length = self.hop_length;
        let spec_size = frame_size / 2 + 1;

        // Build frame: previous + current
        let mut fft_buffer = vec![0.0_f32; frame_size];
        fft_buffer[..hop_length].copy_from_slice(&self.previous);
        fft_buffer[hop_length..].copy_from_slice(input);
        self.previous.copy_from_slice(input);

        // Apply window
        for i in 0..frame_size {
            fft_buffer[i] *= self.window[i];
        }

        // FFT using cached planner
        use rustfft::num_complex::Complex;

        let mut complex_buf: Vec<Complex<f32>> = fft_buffer
            .iter()
            .map(|&v| Complex::new(v, 0.0))
            .collect();
        self.fft_forward.process(&mut complex_buf);

        // Convert to model input format: flat vec for [1, spec_size, 1, 2]
        let mut spec_flat = vec![0.0f32; spec_size * 2];
        for i in 0..spec_size {
            spec_flat[i * 2] = complex_buf[i].re;
            spec_flat[i * 2 + 1] = complex_buf[i].im;
        }

        // Convert to ort Values using (shape, data) tuple
        let spec_shape = vec![1, spec_size, 1, 2];
        let val_spec = ort::value::Value::from_array((spec_shape, spec_flat)).unwrap();
        
        let val_states: Vec<_> = self.state_data.iter().zip(self.state_shapes.iter())
            .map(|(data, shape)| ort::value::Value::from_array((shape.clone(), data.clone())).unwrap())
            .collect();

        // Run inference
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

        // Extract output spectrum
        if let Ok(output_tensor) = outputs[0].try_extract_tensor::<f32>() {
            let output_data = output_tensor.1; // (Shape, slice)
            for i in 0..spec_size {
                complex_buf[i] = Complex::new(output_data[i * 2], output_data[i * 2 + 1]);
            }
            for i in spec_size..frame_size {
                complex_buf[i] = complex_buf[frame_size - i].conj();
            }
        }

        // Update states from outputs 1..18
        for i in 1..outputs.len().min(19) {
            if let Ok(state_tensor) = outputs[i].try_extract_tensor::<f32>() {
                let state_data = state_tensor.1;
                if i - 1 < self.state_data.len() && state_data.len() == self.state_data[i - 1].len() {
                    self.state_data[i - 1].copy_from_slice(state_data);
                }
            }
        }

        // IFFT
        self.fft_inverse.process(&mut complex_buf);
        let scale = 1.0 / frame_size as f32;
        for i in 0..frame_size {
            fft_buffer[i] = complex_buf[i].re * scale * self.window[i];
        }

        // OLA
        for i in 0..frame_size {
            self.ola_accumulator[i] += fft_buffer[i];
        }

        let mut output = vec![0.0_f32; hop_length];
        for i in 0..hop_length {
            output[i] = self.ola_accumulator[i] * self.ola_gain;
        }

        // Shift accumulator
        for i in 0..frame_size - hop_length {
            self.ola_accumulator[i] = self.ola_accumulator[i + hop_length];
        }
        for i in frame_size - hop_length..frame_size {
            self.ola_accumulator[i] = 0.0;
        }

        output
    }
}

// ─── Speexdsp-style spectral subtraction noise suppression ──────────────────

/// A simple spectral subtraction noise suppressor inspired by Speex's approach.
/// Uses FFT to estimate noise floor and subtract it from the signal.
#[cfg(feature = "dsp")]
struct SpeexStyleNS {
    frame_size: usize,
    noise_estimate: Vec<f32>, // Running noise floor estimate per frequency bin
    adaptation_rate: f32,
    fft_forward: std::sync::Arc<dyn rustfft::Fft<f32>>,
    fft_inverse: std::sync::Arc<dyn rustfft::Fft<f32>>,
}

#[cfg(feature = "dsp")]
impl SpeexStyleNS {
    fn new() -> Self {
        use rustfft::FftPlanner;
        let frame_size = 480;
        let mut planner = FftPlanner::new();
        let fft_forward = planner.plan_fft_forward(frame_size);
        let fft_inverse = planner.plan_fft_inverse(frame_size);
        Self {
            frame_size,
            noise_estimate: vec![0.0; frame_size / 2 + 1],
            adaptation_rate: 0.02,
            fft_forward,
            fft_inverse,
        }
    }

    fn process(&mut self, data: &mut [f32], intensity: f32) {
        use rustfft::num_complex::Complex;

        let len = data.len();
        if len < self.frame_size {
            return;
        }

        let num_frames = len / self.frame_size;
        let mix = (intensity / 100.0).clamp(0.0, 1.0);
        let spec_size = self.frame_size / 2 + 1;

        for frame_idx in 0..num_frames {
            let offset = frame_idx * self.frame_size;
            let frame = &data[offset..offset + self.frame_size];

            let mut complex: Vec<Complex<f32>> = frame
                .iter()
                .map(|&s| Complex::new(s, 0.0))
                .collect();

            self.fft_forward.process(&mut complex);

            // Compute magnitude and update noise estimate
            for i in 0..spec_size {
                let mag = complex[i].norm();
                self.noise_estimate[i] = self.noise_estimate[i] * (1.0 - self.adaptation_rate)
                    + mag * self.adaptation_rate;
            }

            // Spectral subtraction
            for i in 0..spec_size {
                let mag = complex[i].norm();
                let phase = complex[i].arg();
                let noise = self.noise_estimate[i] * mix * 2.0;
                let clean_mag = (mag - noise).max(mag * 0.05);
                complex[i] = Complex::from_polar(clean_mag, phase);
                if i > 0 && i < self.frame_size - i {
                    complex[self.frame_size - i] = complex[i].conj();
                }
            }

            self.fft_inverse.process(&mut complex);

            let scale = 1.0 / self.frame_size as f32;
            for i in 0..self.frame_size {
                data[offset + i] = complex[i].re * scale;
            }
        }
    }
}


/// DPDFNet ONNX Runtime denoiser
#[cfg(feature = "noise-suppression")]
struct DpdfnetProcessor {
    session: ort::session::Session,
    frame_size: usize,
    hop_length: usize,
    window: Vec<f32>,
    ola_gain: f32,
    previous: Vec<f32>,
    ola_accumulator: Vec<f32>,
    state_data: Vec<f32>,
    fft_forward: std::sync::Arc<dyn rustfft::Fft<f32>>,
    fft_inverse: std::sync::Arc<dyn rustfft::Fft<f32>>,
}

#[cfg(feature = "noise-suppression")]
impl DpdfnetProcessor {
    fn new(model_path: &str) -> Result<Self, Box<dyn std::error::Error>> {
        let frame_size = 960;
        let hop_length = 480;

        let session = ort::session::Session::builder()?
            .with_intra_threads(1)?
            .with_inter_threads(1)?
            .commit_from_file(model_path)?;

        let window = Self::vorbis_window(frame_size);
        let ola_gain = Self::calc_ola_gain(&window, hop_length);

        use rustfft::FftPlanner;
        let mut planner = FftPlanner::new();
        let fft_forward = planner.plan_fft_forward(frame_size);
        let fft_inverse = planner.plan_fft_inverse(frame_size);

        let mut state_data = vec![0.0f32; 56436];
        // Note: we inject erb_norm_init and spec_norm_init
        let erb_init: [f32; 481] = [-32.5573006,-34.8212013,-38.8535995,-39.8786011,-40.7434998,-41.5264015,-42.2550011,-42.4084015,-42.4309998,-42.6459999,-43.0335007,-43.6703987,-44.5702019,-45.3494987,-46.0084991,-46.4555016,-46.6585999,-46.5344009,-46.6155014,-46.8083992,-46.9929008,-47.160099,-47.2527008,-47.3922005,-47.6403008,-47.924099,-48.1502991,-48.3008995,-48.2300987,-48.3321991,-48.7560005,-48.9982986,-48.9334984,-48.9662018,-49.1459007,-49.3419991,-49.7210999,-50.0116005,-49.9384995,-49.9581985,-50.2630005,-50.5990982,-50.6968002,-50.6801987,-50.6985016,-50.7848015,-51.0158997,-51.2048988,-51.1635017,-51.1142998,-51.2252998,-51.3586998,-51.4637985,-51.6249008,-51.6959,-51.6421013,-51.7896004,-52.1039009,-52.2228012,-52.2448997,-52.3748016,-52.4832993,-52.6427994,-52.8335991,-52.9245987,-52.9567986,-53.0166016,-53.0936012,-53.2230988,-53.3219986,-53.2919006,-53.2598,-53.3250999,-53.4057007,-53.4346008,-53.4701004,-53.5049019,-53.5446014,-53.6279984,-53.7321014,-53.8563004,-53.9211998,-53.9846992,-54.0430984,-54.2167015,-54.4015999,-54.4896011,-54.5848007,-54.6638985,-54.7624016,-54.9357986,-55.1197014,-55.2275009,-55.3156013,-55.4165001,-55.5399017,-55.6763992,-55.7394981,-55.7476997,-55.8479004,-55.9743996,-56.0429993,-56.0859985,-56.1640015,-56.2363014,-56.2627983,-56.3778,-56.4760017,-56.5116005,-56.5653,-56.6613998,-56.7071991,-56.7462997,-56.8340988,-56.8720016,-56.9146996,-56.9864998,-57.0185013,-57.0088005,-57.0421982,-57.1057014,-57.1573982,-57.1939011,-57.2075996,-57.2134018,-57.2274017,-57.2743988,-57.3180008,-57.2798004,-57.3123016,-57.3424988,-57.3012009,-57.2733994,-57.283699,-57.2924004,-57.2481003,-57.232399,-57.2853012,-57.2801018,-57.2275009,-57.2240982,-57.2266998,-57.2223015,-57.2299004,-57.2212982,-57.1935005,-57.2167015,-57.2694016,-57.2691994,-57.2359009,-57.2661018,-57.2831993,-57.2845001,-57.2923012,-57.3002014,-57.3082008,-57.297699,-57.3480988,-57.3689003,-57.3639984,-57.3757019,-57.4231987,-57.4438019,-57.4617004,-57.5147018,-57.5293999,-57.5080986,-57.5382996,-57.5932007,-57.5671005,-57.5514984,-57.5581017,-57.5630989,-57.5514984,-57.5555,-57.5937996,-57.604599,-57.6049995,-57.6176987,-57.6236,-57.6321983,-57.6557007,-57.7024994,-57.7336998,-57.7678986,-57.8209991,-57.8595009,-57.8639984,-57.8880005,-57.9267006,-57.9714012,-57.9995003,-58.0164986,-58.0381012,-58.0719986,-58.1155014,-58.1525002,-58.153801,-58.1753006,-58.2076988,-58.2277985,-58.2361984,-58.2209015,-58.2060013,-58.229599,-58.2728004,-58.3093987,-58.3506012,-58.3813019,-58.4140015,-58.4445992,-58.4496994,-58.4720993,-58.4972,-58.5252991,-58.5301018,-58.5327988,-58.5626984,-58.5960007,-58.6232986,-58.6604004,-58.7172012,-58.7705994,-58.8209,-58.8838997,-58.9435005,-59.0027008,-59.0625992,-59.1240005,-59.2067986,-59.2783012,-59.3370018,-59.4001999,-59.4317017,-59.4729996,-59.4850006,-59.5219002,-59.5662003,-59.5397987,-59.5387001,-59.5881996,-59.6119003,-59.6049004,-59.6074982,-59.6520004,-59.6764984,-59.6948013,-59.7326012,-59.7966995,-59.8498993,-59.9053001,-59.9388008,-60.0228996,-60.0928001,-60.1237984,-60.1692009,-60.2615013,-60.3148994,-60.3410988,-60.4096985,-60.4845009,-60.5007019,-60.5396996,-60.6091003,-60.655899,-60.6968002,-60.7422981,-60.8142014,-60.8642998,-60.9090004,-60.9342003,-60.975399,-61.0166016,-61.0443993,-61.0889015,-61.1498985,-61.1810989,-61.2333984,-61.2779007,-61.3123016,-61.3727989,-61.4356003,-61.462101,-61.5222015,-61.5875015,-61.6325989,-61.6533012,-61.723999,-61.7653008,-61.7825012,-61.8326988,-61.8722992,-61.9087982,-61.9166985,-61.9361992,-62.0017014,-62.0457993,-62.0654984,-62.0833015,-62.118,-62.1346016,-62.1609993,-62.168499,-62.2173004,-62.2715988,-62.3036003,-62.3412018,-62.3841019,-62.4318008,-62.473999,-62.5171013,-62.5457001,-62.5201988,-62.6277008,-62.7108002,-62.7737007,-62.8414001,-62.8911018,-62.9369011,-62.9721985,-62.9966011,-63.0275993,-63.0891991,-63.1491013,-63.1747017,-63.2263985,-63.2770996,-63.2966995,-63.3073006,-63.3485985,-63.3913002,-63.4314003,-63.4603004,-63.4844017,-63.5415993,-63.5943985,-63.6228981,-63.6585007,-63.7060013,-63.7358017,-63.7644997,-63.7970009,-63.8261986,-63.8632011,-63.9071999,-63.9406013,-63.9934006,-64.0588989,-64.0915985,-64.1280975,-64.1837006,-64.2362976,-64.2770004,-64.2934036,-64.3225021,-64.3868027,-64.4234009,-64.4572983,-64.4972,-64.5550995,-64.5867996,-64.6232986,-64.6847992,-64.7298965,-64.7549973,-64.8106003,-64.8852005,-64.9567032,-65.0360031,-65.0944977,-65.1731033,-65.2444,-65.2854996,-65.3230972,-65.376503,-65.446701,-65.5100021,-65.5836029,-65.6743011,-65.7611008,-65.8354034,-65.8935013,-65.9760971,-66.0544968,-66.1243973,-66.1969986,-66.2873993,-66.3671036,-66.4253006,-66.4988022,-66.5821991,-66.6647034,-66.7598038,-66.8442993,-66.9459,-67.020401,-67.1024017,-67.1903992,-67.2890015,-67.3919983,-67.4990005,-67.6559982,-67.7911987,-67.8871002,-67.9999008,-68.1219025,-68.2042999,-68.2932968,-68.3825989,-68.481102,-68.6007004,-68.7118988,-68.8220978,-68.9301987,-69.0493011,-69.1750031,-69.2770996,-69.3843994,-69.4990005,-69.6035995,-69.7136002,-69.8386002,-69.952301,-70.0627975,-70.1745987,-70.2901993,-70.3977966,-70.479599,-70.5335007,-70.518898,-70.4241028,-70.2811966,-70.1092987,-70.0510025,-70.1993027,-70.3490982,-70.5081024,-70.7475967,-71.0036011,-71.245903,-71.5072021,-71.7031021,-71.9048004,-72.1479034,-72.330101,-72.6034012,-72.8438034,-73.2155991,-73.5852966,-73.7624969,-74.0639038,-74.5064011,-74.935997,-75.3741989,-75.7762985,-76.1246033,-76.4624023,-76.7764969,-77.1316986,-77.5189972,-77.895401,-78.2605972,-78.6847992,-79.1474991,-79.6326981,-80.1250992,-80.6212006,-81.1784973,-81.7838974,-82.3960037,-83.0165024,-83.6427994,-84.2643967,-84.9417038,-85.6692963,-86.3048019,-86.8884964,-87.5030975,-88.1544037,-88.7786026,-90.185997];
        let spec_init: [f32; 96] = [0.00478440011,0.00231450005,0.00113810005,0.00134099997,0.00162839994,0.0013601,0.00105990004,0.0010851,0.00119039998,0.00117479998,0.0011158,0.000999439973,0.000814169995,0.000670530018,0.000553709979,0.000488049991,0.000448050007,0.000449020008,0.000443669996,0.000412980007,0.000392420014,0.000385490013,0.000387500011,0.000386439991,0.00036546,0.00033961999,0.000316830003,0.000304479996,0.000307079987,0.000297469989,0.000265690003,0.00024229,0.000241679998,0.000239989997,0.000227390003,0.000212080005,0.000189109996,0.000172610002,0.000175180001,0.000171599997,0.000155529997,0.000142510005,0.000137519994,0.000135769995,0.000137990006,0.000136119997,0.000127740001,0.000123129998,0.000124929997,0.000125830004,0.000121500001,0.000116670002,0.000112189999,0.000107929998,0.000107959997,0.000110189998,0.00010533,9.50739995e-05,9.116e-05,9.1670001e-05,8.82609966e-05,8.54239988e-05,8.1250997e-05,7.65369987e-05,7.53679997e-05,7.46209989e-05,7.32940025e-05,7.22580007e-05,7.08430016e-05,6.93550028e-05,6.94550035e-05,7.04970007e-05,6.97089999e-05,6.81140009e-05,6.92169997e-05,6.90239976e-05,6.77240023e-05,6.77849966e-05,6.77150019e-05,6.6654e-05,6.41529987e-05,6.23899978e-05,6.19729981e-05,6.16990001e-05,6.04279994e-05,5.86610004e-05,5.72870013e-05,5.58620013e-05,5.53630016e-05,5.4701999e-05,5.28890014e-05,4.96190005e-05,4.83139993e-05,4.77659996e-05,4.66329984e-05,4.59580006e-05];
        state_data[0..481].copy_from_slice(&erb_init);
        state_data[481..(481+96)].copy_from_slice(&spec_init);

        Ok(Self {
            session,
            frame_size,
            hop_length,
            window,
            ola_gain,
            previous: vec![0.0; hop_length],
            ola_accumulator: vec![0.0; frame_size],
            state_data,
            fft_forward,
            fft_inverse,
        })
    }

    fn vorbis_window(size: usize) -> Vec<f32> {
        let size_h = size as f64 / 2.0;
        (0..size)
            .map(|i| {
                let s = (0.5 * std::f64::consts::PI * (i as f64 + 0.5) / size_h).sin();
                (0.5 * std::f64::consts::PI * s * s).sin() as f32
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

    fn process(&mut self, input: &[f32]) -> Vec<f32> {
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

        use rustfft::num_complex::Complex;
        let mut complex_buf: Vec<Complex<f32>> = fft_buffer.iter().map(|&v| Complex::new(v, 0.0)).collect();
        self.fft_forward.process(&mut complex_buf);

        let mut spec_flat = vec![0.0f32; spec_size * 2];
        for i in 0..spec_size {
            spec_flat[i * 2] = complex_buf[i].re;
            spec_flat[i * 2 + 1] = complex_buf[i].im;
        }

        let val_spec = ort::value::Value::from_array((vec![1, 1, spec_size, 2], spec_flat)).unwrap();
        let val_state = ort::value::Value::from_array((vec![56436], self.state_data.clone())).unwrap();

        if let Ok(outputs) = self.session.run(ort::inputs![&val_spec, &val_state]) {
            if let Ok(output_tensor) = outputs[0].try_extract_tensor::<f32>() {
                let output_data = output_tensor.1;
                for i in 0..spec_size {
                    complex_buf[i] = Complex::new(output_data[i * 2], output_data[i * 2 + 1]);
                }
                for i in spec_size..frame_size {
                    complex_buf[i] = complex_buf[frame_size - i].conj();
                }
            }
            if let Ok(state_tensor) = outputs[1].try_extract_tensor::<f32>() {
                self.state_data.copy_from_slice(state_tensor.1);
            }
        }

        self.fft_inverse.process(&mut complex_buf);
        let scale = 1.0 / frame_size as f32;
        for i in 0..frame_size {
            self.ola_accumulator[i] += complex_buf[i].re * scale * self.window[i];
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

// ─── Equalizer (10-band Biquad Peaking EQ) ──────────────────────────────────

struct BiquadFilter {
    a0: f64, a1: f64, a2: f64,
    b1: f64, b2: f64,
    x1: f64, x2: f64,
    y1: f64, y2: f64,
}

impl BiquadFilter {
    fn new() -> Self {
        Self {
            a0: 1.0, a1: 0.0, a2: 0.0,
            b1: 0.0, b2: 0.0,
            x1: 0.0, x2: 0.0,
            y1: 0.0, y2: 0.0,
        }
    }

    fn set_peaking_eq(&mut self, sample_rate: f64, center_freq: f64, q: f64, db_gain: f64) {
        let w0 = 2.0 * std::f64::consts::PI * center_freq / sample_rate;
        let alpha = w0.sin() / (2.0 * q);
        let a = 10.0_f64.powf(db_gain / 40.0);

        let b0_raw = 1.0 + alpha * a;
        let b1_raw = -2.0 * w0.cos();
        let b2_raw = 1.0 - alpha * a;
        let a0_raw = 1.0 + alpha / a;
        let a1_raw = -2.0 * w0.cos();
        let a2_raw = 1.0 - alpha / a;

        self.a0 = b0_raw / a0_raw;
        self.a1 = b1_raw / a0_raw;
        self.a2 = b2_raw / a0_raw;
        self.b1 = a1_raw / a0_raw;
        self.b2 = a2_raw / a0_raw;
    }

    fn process(&mut self, x: f64) -> f64 {
        let y = self.a0 * x + self.a1 * self.x1 + self.a2 * self.x2 - self.b1 * self.y1 - self.b2 * self.y2;
        self.x2 = self.x1;
        self.x1 = x;
        self.y2 = self.y1;
        self.y1 = y;
        y
    }
}

struct EqualizerEffect {
    filters_ch1: Vec<BiquadFilter>,
    filters_ch2: Vec<BiquadFilter>,
    pre_amp_gain: f32,
    frequencies: [f64; 10],
}

impl EqualizerEffect {
    fn new() -> Self {
        let mut eq = Self {
            filters_ch1: (0..10).map(|_| BiquadFilter::new()).collect(),
            filters_ch2: (0..10).map(|_| BiquadFilter::new()).collect(),
            pre_amp_gain: 1.0,
            frequencies: [31.25, 62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0],
        };
        eq.update_filters(&EqualizerConfig::default());
        eq
    }

    fn update_filters(&mut self, config: &EqualizerConfig) {
        self.pre_amp_gain = 10.0_f32.powf(config.pre_amp / 20.0);
        let sample_rate = 48000.0;
        for i in 0..10 {
            let gain = if i < config.gains.len() { config.gains[i] as f64 } else { 0.0 };
            self.filters_ch1[i].set_peaking_eq(sample_rate, self.frequencies[i], 1.0, gain);
            self.filters_ch2[i].set_peaking_eq(sample_rate, self.frequencies[i], 1.0, gain);
        }
    }

    fn process(&mut self, data: &mut [f32], channels: usize) {
        if channels == 1 {
            for sample in data.iter_mut() {
                let mut s = (*sample * self.pre_amp_gain) as f64;
                for i in 0..10 {
                    s = self.filters_ch1[i].process(s);
                }
                *sample = s as f32;
            }
        } else if channels == 2 {
            for (i, sample) in data.iter_mut().enumerate() {
                let mut s = (*sample * self.pre_amp_gain) as f64;
                if i % 2 == 0 {
                    for j in 0..10 { s = self.filters_ch1[j].process(s); }
                } else {
                    for j in 0..10 { s = self.filters_ch2[j].process(s); }
                }
                *sample = s as f32;
            }
        }
    }
}

// ─── Buffer Level Manager (replaces adaptive resampler) ─────────────────────

/// Manages playback timing by monitoring the output buffer level.
/// Instead of continuous resampling (which introduces interpolation artifacts),
/// this uses simple sample duplication/dropping only when the buffer drifts
/// significantly from the target level.
struct BufferLevelManager {
    underrun_count: u32,
    overrun_count: u32,
}

impl BufferLevelManager {
    fn new() -> Self {
        Self {
            underrun_count: 0,
            overrun_count: 0,
        }
    }

    fn process(&mut self, input: &[f32], channels: usize, queued_ms: f64, output: &mut Vec<f32>) {
        output.clear();
        if channels == 0 || input.is_empty() {
            output.extend_from_slice(input);
            return;
        }

        let frames = input.len() / channels;

        // Buffer critically low (< 15ms): duplicate last frame to prevent underrun
        if queued_ms < 15.0 {
            self.underrun_count += 1;
            self.overrun_count = 0;
            // Duplicate the last frame
            if frames >= 1 && self.underrun_count <= 3 {
                output.reserve(input.len() + channels);
                output.extend_from_slice(input);
                let last_frame_start = (frames - 1) * channels;
                for c in 0..channels {
                    output.push(input[last_frame_start + c]);
                }
                return;
            }
            output.extend_from_slice(input);
            return;
        }

        // Buffer critically high (> 300ms): drop first frame to prevent overflow
        if queued_ms > 300.0 {
            self.overrun_count += 1;
            self.underrun_count = 0;
            if frames > 2 && self.overrun_count <= 3 {
                output.extend_from_slice(&input[channels..]);
                return;
            }
            output.extend_from_slice(input);
            return;
        }

        // Normal range: pass through unchanged
        self.underrun_count = 0;
        self.overrun_count = 0;
        output.extend_from_slice(input);
    }
}

// ─── Main DSP Processor ─────────────────────────────────────────────────────

/// The main DSP processor. Operates on f32 PCM samples at 48kHz.
pub struct DspProcessor {
    settings: Arc<RwLock<AudioDspSettings>>,
    // RNNoise states - separate per channel to avoid RNN state cross-contamination
    #[cfg(feature = "dsp")]
    denoiser_left: Box<DenoiseState<'static>>,
    #[cfg(feature = "dsp")]
    denoiser_right: Box<DenoiseState<'static>>,
    #[cfg(feature = "dsp")]
    ns_buffer_left: Vec<f32>,
    #[cfg(feature = "dsp")]
    ns_buffer_right: Vec<f32>,
    // Ulunas ONNX processor - separate per channel
    #[cfg(feature = "noise-suppression")]
    ulunas_left: Option<UlunasProcessor>,
    #[cfg(feature = "noise-suppression")]
    ulunas_right: Option<UlunasProcessor>,
 
    #[cfg(feature = "noise-suppression")]
    ulunas_model_path: Option<PathBuf>,
    #[cfg(feature = "noise-suppression")]
    dpdfnet_model_path: Option<PathBuf>,
    #[cfg(feature = "noise-suppression")]
    dpdfnet_left: Option<DpdfnetProcessor>,
    #[cfg(feature = "noise-suppression")]
    dpdfnet_right: Option<DpdfnetProcessor>,
    // Speexdsp-style NS
    #[cfg(feature = "dsp")]
    speex_ns: SpeexStyleNS,
    // Equalizer
    equalizer: EqualizerEffect,
    // Adaptive Resampler
    // Buffer level manager (replaces adaptive resampler)
    buffer_manager: BufferLevelManager,
    // Dereverb state
    dereverb_buffer_left: Vec<f32>,
    dereverb_buffer_right: Vec<f32>,
    dereverb_index: usize,
    // AGC envelope follower
    agc_envelope: f32,
    // AGC smoothed gain (avoids sudden gain jumps causing pops)
    agc_smoothed_gain: f32,
    // VAD fade state (0.0 = muted, 1.0 = full)
    vad_fade: f32,
    // Spectrum snapshots
    raw_spectrum: Vec<f32>,
    processed_spectrum: Vec<f32>,
    // Frame accumulation buffer (align to 480-sample frames for noise reduction)
    accum_buffer: Vec<f32>,
    output_buffer: Vec<f32>,
    to_process_buf: Vec<f32>,
}

const RNNOISE_FRAME_SIZE: usize = 480;

impl DspProcessor {
    pub fn new(settings: Arc<RwLock<AudioDspSettings>>, _model_dir: Option<PathBuf>) -> Self {
        Self {
            settings: settings.clone(),
            #[cfg(feature = "noise-suppression")]
            ulunas_model_path: _model_dir.as_ref().map(|d| d.join("ulunas.onnx")),
            #[cfg(feature = "noise-suppression")]
            dpdfnet_model_path: _model_dir.as_ref().map(|d| d.join("dpdfnet.onnx")),
            #[cfg(feature = "dsp")]
            denoiser_left: DenoiseState::new(),
            #[cfg(feature = "dsp")]
            denoiser_right: DenoiseState::new(),
            #[cfg(feature = "dsp")]
            ns_buffer_left: Vec::with_capacity(RNNOISE_FRAME_SIZE * 2),
            #[cfg(feature = "dsp")]
            ns_buffer_right: Vec::with_capacity(RNNOISE_FRAME_SIZE * 2),
            #[cfg(feature = "noise-suppression")]
            ulunas_left: None,
            #[cfg(feature = "noise-suppression")]
            ulunas_right: None,
            #[cfg(feature = "noise-suppression")]
            dpdfnet_left: None,
            #[cfg(feature = "noise-suppression")]
            dpdfnet_right: None,
 
            #[cfg(feature = "dsp")]
            speex_ns: SpeexStyleNS::new(),
            equalizer: EqualizerEffect::new(),
            buffer_manager: BufferLevelManager::new(),
            dereverb_buffer_left: vec![0.0; 480],
            dereverb_buffer_right: vec![0.0; 480],
            dereverb_index: 0,
            agc_envelope: 0.0,
            agc_smoothed_gain: 1.0,
            vad_fade: 1.0,
            raw_spectrum: vec![0.0; 64],
            processed_spectrum: vec![0.0; 64],
            accum_buffer: Vec::new(),
            output_buffer: vec![0.0; 960],
            to_process_buf: Vec::new(),
        }
    }

    /// Process a chunk of f32 PCM audio in-place.
    /// Returns (raw_rms, processed_rms) for level metering.
    /// Internally accumulates to 480-sample aligned frames before noise reduction,
    /// matching the KMP AudioProcessorPipeline behavior.
    pub fn process(&mut self, data: &mut Vec<f32>, channels: usize, queued_ms: f64) -> (f32, f32) {
        if data.is_empty() {
            return (0.0, 0.0);
        }

        let raw_rms = compute_rms(data);
        self.compute_spectrum(data, true);

        // Frame accumulation: align to 480*channels samples before processing,
        // matching KMP's AudioProcessorPipeline behavior.
        // Noise reduction (RNNoise/Ulunas) requires exactly 480-sample frames.
        // Processing variable-size chunks through AGC/EQ causes artifacts.
        self.accum_buffer.extend_from_slice(data);
        let samples_per_frame = RNNOISE_FRAME_SIZE * channels.max(1);
        let frame_count = self.accum_buffer.len() / samples_per_frame;

        if frame_count == 0 {
            data.clear();
            return (raw_rms, 0.0);
        }

        let process_count = frame_count * samples_per_frame;
        self.to_process_buf.clear();
        self.to_process_buf.extend_from_slice(&self.accum_buffer[..process_count]);
        self.accum_buffer.drain(..process_count);

        let mut to_process = std::mem::take(&mut self.to_process_buf);

        let settings = self.settings.read().unwrap().clone();
        self.equalizer.update_filters(&settings.equalizer);

        for effect in &settings.processing_chain {
            match effect.as_str() {
                "NoiseReduction" => {
                    if settings.ns_enabled {
                        self.apply_noise_reduction(&mut to_process, channels.max(1), &settings);
                    }
                }
                "Dereverb" => {
                    if settings.dereverb_enabled {
                        self.apply_dereverb(&mut to_process, channels.max(1), settings.dereverb_level);
                    }
                }
                "Equalizer" => {
                    if settings.equalizer.enabled {
                        self.equalizer.process(&mut to_process, channels);
                    }
                }
                "Amplifier" => {
                    if settings.gain.abs() > 0.01 {
                        let gain_linear = 10.0_f32.powf(settings.gain / 20.0);
                        for sample in to_process.iter_mut() {
                            *sample *= gain_linear;
                        }
                    }
                }
                "AGC" => {
                    if settings.agc_enabled {
                        let attack_rate = settings.agc_attack / 1000.0;
                        let decay_rate = settings.agc_decay / 10000.0;
                        self.apply_agc(&mut to_process, settings.agc_target, attack_rate, decay_rate);
                    }
                }
                "VAD" => {
                    if settings.vad_enabled {
                        self.apply_vad(&mut to_process, settings.vad_threshold);
                    }
                }
                _ => {}
            }
        }

        self.buffer_manager.process(&to_process, channels, queued_ms, &mut self.output_buffer);

        // Soft clip — avoids harsh hard-clipping artifacts (crackling/pops)
        for sample in self.output_buffer.iter_mut() {
            *sample = soft_clip(*sample).clamp(-1.0, 1.0);
        }

        self.to_process_buf = to_process;

        data.clear();
        data.extend_from_slice(&self.output_buffer);

        let processed_rms = compute_rms(data);
        self.compute_spectrum(data, false);

        (raw_rms, processed_rms)
    }

    pub fn get_spectrums(&self) -> (Vec<f32>, Vec<f32>) {
        (self.raw_spectrum.clone(), self.processed_spectrum.clone())
    }

    // ── Noise Reduction Dispatcher ──────────────────────────────────────────

    fn apply_noise_reduction(&mut self, data: &mut Vec<f32>, channels: usize, settings: &AudioDspSettings) {
        match settings.ns_type.as_str() {
            #[cfg(feature = "dsp")]
            "RNNoise" => self.apply_rnnoise(data, channels, settings.ns_intensity),
            #[cfg(feature = "noise-suppression")]
            "Ulunas" => self.apply_ulunas(data, channels, settings.ns_intensity),
            #[cfg(feature = "noise-suppression")]
            "DPDFNet" => self.apply_dpdfnet(data, channels, settings.ns_intensity),
            #[cfg(feature = "dsp")]
            "Speexdsp" => self.apply_speex(data, channels, settings.ns_intensity),
            "Lightweight" => self.apply_lightweight(data, settings.ns_intensity),
            _ => {}
        }
    }

    // ── Lightweight (Noise Gate) ─────────────────────────────────────────────

    fn apply_lightweight(&mut self, data: &mut Vec<f32>, intensity: f32) {
        // A simple soft noise gate (expander) based on intensity
        // intensity 0..100 maps to a threshold of -60dB to -30dB
        let threshold_db = -60.0 + (intensity / 100.0) * 30.0;
        let threshold = 10.0_f32.powf(threshold_db / 20.0);
        
        let ratio = 2.0; // Expansion ratio
        
        for sample in data.iter_mut() {
            let abs_val = sample.abs();
            if abs_val < threshold && abs_val > 0.0 {
                // Apply soft downward expansion
                let gain = (abs_val / threshold).powf(ratio - 1.0);
                *sample *= gain;
            }
        }
    }

    // ── RNNoise (nnnoiseless) ───────────────────────────────────────────────

    #[cfg(feature = "dsp")]
    fn apply_rnnoise(&mut self, data: &mut Vec<f32>, channels: usize, intensity: f32) {
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

            // Process each channel with its own denoiser (no RNN state cross-contamination)
            Self::process_rnnoise_single_channel(&mut left, &mut self.ns_buffer_left, &mut self.denoiser_left, intensity);
            Self::process_rnnoise_single_channel(&mut right, &mut self.ns_buffer_right, &mut self.denoiser_right, intensity);

            data.clear();
            for i in 0..frames {
                data.push(left[i]);
                data.push(right[i]);
            }
        } else {
            Self::process_rnnoise_single_channel(data, &mut self.ns_buffer_left, &mut self.denoiser_left, intensity);
        }
    }

    #[cfg(feature = "dsp")]
    fn process_rnnoise_single_channel(
        data: &mut Vec<f32>,
        ns_buffer: &mut Vec<f32>,
        denoiser: &mut DenoiseState<'static>,
        intensity: f32,
    ) {
        let mix = (intensity / 100.0).clamp(0.0, 1.0);
        let input_len = data.len();
        ns_buffer.extend_from_slice(data);

        let mut output = Vec::with_capacity(input_len);

        while ns_buffer.len() >= RNNOISE_FRAME_SIZE {
            let frame: Vec<f32> = ns_buffer.drain(..RNNOISE_FRAME_SIZE).collect();

            let input_frame: Vec<f32> = frame.iter().map(|s| s * 32767.0).collect();
            let mut output_frame = vec![0.0f32; RNNOISE_FRAME_SIZE];

            let _vad_prob = denoiser.process_frame(&mut output_frame, &input_frame);

            for i in 0..RNNOISE_FRAME_SIZE {
                let clean = output_frame[i] / 32767.0;
                let original = frame[i];
                output.push(original * (1.0 - mix) + clean * mix);
            }
        }

        for sample in ns_buffer.drain(..) {
            output.push(sample);
        }

        output.truncate(input_len);
        while output.len() < input_len {
            output.push(0.0);
        }

        *data = output;
    }


    #[cfg(feature = "noise-suppression")]
    fn apply_dpdfnet(&mut self, data: &mut Vec<f32>, channels: usize, intensity: f32) {
        if self.dpdfnet_left.is_none() {
            if let Some(path) = &self.dpdfnet_model_path {
                if path.exists() {
                    if let Ok(proc) = DpdfnetProcessor::new(path.to_str().unwrap_or("")) {
                        self.dpdfnet_left = Some(proc);
                    }
                }
            }
        }
        if channels >= 2 && self.dpdfnet_right.is_none() {
            if let Some(path) = &self.dpdfnet_model_path {
                if path.exists() {
                    if let Ok(proc) = DpdfnetProcessor::new(path.to_str().unwrap_or("")) {
                        self.dpdfnet_right = Some(proc);
                    }
                }
            }
        }

        if channels >= 2 {
            let frames = data.len() / 2;
            let mut left = Vec::with_capacity(frames);
            let mut right = Vec::with_capacity(frames);
            for i in 0..frames {
                left.push(data[i * 2]);
                right.push(data[i * 2 + 1]);
            }
            Self::process_dpdfnet_single_channel(&mut left, &mut self.dpdfnet_left, intensity);
            Self::process_dpdfnet_single_channel(&mut right, &mut self.dpdfnet_right, intensity);
            data.clear();
            for i in 0..frames {
                data.push(left[i]);
                data.push(right[i]);
            }
        } else {
            Self::process_dpdfnet_single_channel(data, &mut self.dpdfnet_left, intensity);
        }
    }

    #[cfg(feature = "noise-suppression")]
    fn process_dpdfnet_single_channel(
        data: &mut Vec<f32>,
        dpdfnet: &mut Option<DpdfnetProcessor>,
        intensity: f32,
    ) {
        let mix = (intensity / 100.0).clamp(0.0, 1.0);
        
        if let Some(proc) = dpdfnet {
            let mut output = Vec::with_capacity(data.len());
            for chunk in data.chunks(480) {
                let clean = proc.process(chunk);
                for i in 0..chunk.len() {
                    let clean_sample = if i < clean.len() { clean[i] } else { chunk[i] };
                    output.push(chunk[i] * (1.0 - mix) + clean_sample * mix);
                }
            }
            data.copy_from_slice(&output);
        }
    }

    // ── Ulunas (ONNX) ──────────────────────────────────────────────────────

    #[cfg(feature = "noise-suppression")]
    fn apply_ulunas(&mut self, data: &mut Vec<f32>, channels: usize, intensity: f32) {
        // Lazy init for both channels
        if self.ulunas_left.is_none() {
            if let Some(path) = &self.ulunas_model_path {
                if path.exists() {
                    match UlunasProcessor::new(path.to_str().unwrap_or("")) {
                        Ok(proc) => {
                            log::info!("[DSP] Ulunas ONNX model loaded (L): {:?}", path);
                            self.ulunas_left = Some(proc);
                        }
                        Err(e) => {
                            log::error!("[DSP] Failed to load Ulunas model: {}", e);
                            self.apply_rnnoise(data, channels, intensity);
                            return;
                        }
                    }
                } else {
                    log::warn!("[DSP] Ulunas model not found at {:?}, falling back to RNNoise", path);
                    self.apply_rnnoise(data, channels, intensity);
                    return;
                }
            } else {
                self.apply_rnnoise(data, channels, intensity);
                return;
            }
        }
        if channels >= 2 && self.ulunas_right.is_none() {
            if let Some(path) = &self.ulunas_model_path {
                if path.exists() {
                    match UlunasProcessor::new(path.to_str().unwrap_or("")) {
                        Ok(proc) => {
                            log::info!("[DSP] Ulunas ONNX model loaded (R): {:?}", path);
                            self.ulunas_right = Some(proc);
                        }
                        Err(e) => {
                            log::error!("[DSP] Failed to load Ulunas model for R channel: {}", e);
                        }
                    }
                }
            }
        }

        if channels >= 2 {
            let frames = data.len() / 2;
            let mut left: Vec<f32> = Vec::with_capacity(frames);
            let mut right: Vec<f32> = Vec::with_capacity(frames);
            for i in 0..frames {
                left.push(data[i * 2]);
                right.push(data[i * 2 + 1]);
            }

            Self::process_ulunas_single_channel(&mut left, &mut self.ulunas_left, intensity);
            Self::process_ulunas_single_channel(&mut right, &mut self.ulunas_right, intensity);

            data.clear();
            for i in 0..frames {
                data.push(left[i]);
                data.push(right[i]);
            }
        } else {
            Self::process_ulunas_single_channel(data, &mut self.ulunas_left, intensity);
        }
    }

    #[cfg(feature = "noise-suppression")]
    fn process_ulunas_single_channel(
        data: &mut Vec<f32>,
        ulunas: &mut Option<UlunasProcessor>,
        intensity: f32,
    ) {
        let mix = (intensity / 100.0).clamp(0.0, 1.0);
        
        if let Some(proc) = ulunas {
            let mut output = Vec::with_capacity(data.len());
            for chunk in data.chunks(480) {
                let clean = proc.process(chunk);
                for i in 0..chunk.len() {
                    let clean_sample = if i < clean.len() { clean[i] } else { chunk[i] };
                    output.push(chunk[i] * (1.0 - mix) + clean_sample * mix);
                }
            }
            data.copy_from_slice(&output);
        }
    }

    #[cfg(feature = "dsp")]
    fn apply_speex(&mut self, data: &mut Vec<f32>, channels: usize, intensity: f32) {
        let input_len = data.len();

        // Stereo: separate channels, process each independently, re-interleave
        if channels >= 2 && input_len >= 2 {
            let frames = input_len / 2;
            let mut left: Vec<f32> = Vec::with_capacity(frames);
            let mut right: Vec<f32> = Vec::with_capacity(frames);
            for i in 0..frames {
                left.push(data[i * 2]);
                right.push(data[i * 2 + 1]);
            }

            self.speex_ns.process(&mut left, intensity);
            self.speex_ns.process(&mut right, intensity);

            // Re-interleave
            data.clear();
            for i in 0..frames {
                data.push(left[i]);
                data.push(right[i]);
            }
        } else {
            // Mono
            self.speex_ns.process(data, intensity);
        }
    }

    // ── Dereverb (delay-line comb filter, matching KMP DereverbEffect) ─────

    fn apply_dereverb(&mut self, data: &mut Vec<f32>, channels: usize, level: f32) {
        let mix = (level / 100.0).clamp(0.0, 1.0);
        if mix <= 0.0 || channels == 0 {
            return;
        }

        let delay = 480usize;

        // Ensure buffers are sized
        if self.dereverb_buffer_left.len() != delay {
            self.dereverb_buffer_left = vec![0.0; delay];
        }
        if self.dereverb_buffer_right.len() != delay {
            self.dereverb_buffer_right = vec![0.0; delay];
        }

        if channels == 1 {
            let buf = &mut self.dereverb_buffer_left;
            for sample in data.iter_mut() {
                let delayed = buf[self.dereverb_index];
                buf[self.dereverb_index] = *sample;
                *sample = (*sample - delayed * mix).clamp(-1.0, 1.0);
                self.dereverb_index += 1;
                if self.dereverb_index >= delay {
                    self.dereverb_index = 0;
                }
            }
        } else {
            let buf_l = &mut self.dereverb_buffer_left;
            let buf_r = &mut self.dereverb_buffer_right;
            let mut i = 0;
            while i + 1 < data.len() {
                let delayed_l = buf_l[self.dereverb_index];
                let delayed_r = buf_r[self.dereverb_index];
                buf_l[self.dereverb_index] = data[i];
                buf_r[self.dereverb_index] = data[i + 1];
                data[i] = (data[i] - delayed_l * mix).clamp(-1.0, 1.0);
                data[i + 1] = (data[i + 1] - delayed_r * mix).clamp(-1.0, 1.0);
                self.dereverb_index += 1;
                if self.dereverb_index >= delay {
                    self.dereverb_index = 0;
                }
                i += 2;
            }
        }
    }

    // ── AGC ─────────────────────────────────────────────────────────────────

    fn apply_agc(&mut self, data: &mut Vec<f32>, target: f32, attack: f32, decay: f32) {
        let target_linear = target / 32767.0;
        // Noise gate threshold: below this level, don't apply AGC gain
        // (prevents amplifying hiss/noise floor during speech pauses)
        let gate_threshold = 0.005_f32; // ~ -46dB

        for sample in data.iter_mut() {
            let abs_sample = sample.abs();
            if abs_sample > self.agc_envelope {
                self.agc_envelope += attack * (abs_sample - self.agc_envelope);
            } else {
                self.agc_envelope += decay * (abs_sample - self.agc_envelope);
            }
            if self.agc_envelope > gate_threshold {
                let desired_gain = target_linear / self.agc_envelope;
                let clamped_gain = desired_gain.clamp(0.1, 5.0);
                // Smooth gain transition to avoid pops (exponential moving average)
                let smooth_factor = 0.005_f32;
                self.agc_smoothed_gain += smooth_factor * (clamped_gain - self.agc_smoothed_gain);
                *sample *= self.agc_smoothed_gain;
            } else {
                // Below noise gate: smoothly reduce gain toward unity
                let smooth_factor = 0.002_f32;
                self.agc_smoothed_gain += smooth_factor * (1.0 - self.agc_smoothed_gain);
                *sample *= self.agc_smoothed_gain;
            }
        }
    }

    // ── VAD ─────────────────────────────────────────────────────────────────

    fn apply_vad(&mut self, data: &mut Vec<f32>, threshold_db: f32) {
        let rms = compute_rms(data);
        let rms_db = if rms > 1e-10 { 20.0 * rms.log10() } else { -100.0 };
        let target_fade = if rms_db >= threshold_db { 1.0 } else { 0.0 };
        let fade_speed = if target_fade > self.vad_fade { 0.1 } else { 0.02 };
        self.vad_fade += fade_speed * (target_fade - self.vad_fade);
        self.vad_fade = self.vad_fade.clamp(0.0, 1.0);
        for sample in data.iter_mut() {
            *sample *= self.vad_fade;
        }
    }

    // ── Spectrum ─────────────────────────────────────────────────────────────

    fn compute_spectrum(&mut self, data: &[f32], is_raw: bool) {
        let bands = 64;
        let target = if is_raw { &mut self.raw_spectrum } else { &mut self.processed_spectrum };
        if target.len() != bands { target.resize(bands, 0.0); }
        if data.is_empty() {
            for v in target.iter_mut() { *v = 0.0; }
            return;
        }

        const BANDS: usize = 64;
        static BAND_LIMITS: std::sync::OnceLock<[f32; BANDS + 1]> = std::sync::OnceLock::new();
        let limits = BAND_LIMITS.get_or_init(|| {
            let mut array = [0.0; BANDS + 1];
            for i in 0..=BANDS {
                array[i] = (i as f32 / BANDS as f32).powf(1.5);
            }
            array
        });

        for (band_idx, band_val) in target.iter_mut().enumerate() {
            let start = limits[band_idx] * data.len() as f32;
            let end = limits[band_idx + 1] * data.len() as f32;
            let start = start as usize;
            let end = (end as usize).min(data.len());
            if start >= end { *band_val *= 0.85; continue; }
            let mut sum = 0.0_f32;
            for i in start..end { sum += data[i] * data[i]; }
            let rms = (sum / (end - start) as f32).sqrt();
            let db = if rms > 1e-10 { 20.0 * rms.log10() } else { -100.0 };
            let normalized = ((db + 60.0) / 60.0).clamp(0.0, 1.0);
            if normalized > *band_val { *band_val = normalized; }
            else { *band_val = *band_val * 0.85 + normalized * 0.15; }
        }
    }
}

fn compute_rms(data: &[f32]) -> f32 {
    if data.is_empty() { return 0.0; }
    let sum: f32 = data.iter().map(|s| s * s).sum();
    (sum / data.len() as f32).sqrt()
}

/// Soft clip — smooth polynomial knee to avoid harsh hard-clipping artifacts.
/// Identity below 0.95, smooth Hermite compression to ±1.0 at ±2.0.
fn soft_clip(sample: f32) -> f32 {
    let a = sample.abs();
    if a <= 0.95 {
        sample
    } else if a <= 2.0 {
        let sign = sample.signum();
        let t = (a - 0.95) / 1.05; // 0..1 over [0.95, 2.0]
        // Hermite smoothstep: C1 continuous, f(0)=0, f(1)=1, f'(0)=f'(1)=0
        let s = t * t * (3.0 - 2.0 * t);
        sign * (0.95 + 0.05 * s)
    } else {
        sample.signum()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_gain_positive() {
        let settings = Arc::new(RwLock::new(AudioDspSettings {
            gain: 20.0,
            ..Default::default()
        }));
        let mut processor = DspProcessor::new(settings, None);
        let mut data = vec![0.1; 480];
        processor.process(&mut data, 1, 80.0);
        assert!(data[0] > 0.9, "Expected amplified sample, got {}", data[0]);
    }

    #[test]
    fn test_gain_negative() {
        let settings = Arc::new(RwLock::new(AudioDspSettings {
            gain: -20.0,
            ..Default::default()
        }));
        let mut processor = DspProcessor::new(settings, None);
        let mut data = vec![0.5; 480];
        processor.process(&mut data, 1, 80.0);
        assert!(data[0] < 0.1, "Expected attenuated sample, got {}", data[0]);
    }

    #[test]
    fn test_vad_mutes_quiet() {
        let settings = Arc::new(RwLock::new(AudioDspSettings {
            vad_enabled: true,
            vad_threshold: -10.0,
            ..Default::default()
        }));
        let mut processor = DspProcessor::new(settings, None);
        let mut data = vec![0.001; 960];
        for _ in 0..20 { processor.process(&mut data, 1, 80.0); }
        assert!(data[data.len() - 1].abs() < 0.01, "Expected muted, got {}", data[data.len() - 1]);
    }

    #[test]
    fn test_agc_boosts_quiet() {
        let settings = Arc::new(RwLock::new(AudioDspSettings {
            agc_enabled: true,
            agc_target: 16000.0,
            agc_attack: 90.0,
            agc_decay: 10.0,
            ..Default::default()
        }));
        let mut processor = DspProcessor::new(settings, None);
        let mut data: Vec<f32> = vec![0.01; 4800];
        for _ in 0..10 { processor.process(&mut data, 1, 80.0); }
        assert!(data[data.len() - 1].abs() > 0.01, "AGC should have amplified the signal");
    }
}
