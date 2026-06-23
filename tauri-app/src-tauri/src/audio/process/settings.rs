/// Audio DSP settings, synced from the frontend.
#[derive(Debug, Clone, serde::Deserialize, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AudioDspSettings {
    pub gain: f32,           // dB, -50 to +50
    pub ns_enabled: bool,
    pub ns_type: String,     // "RNNoise", "Ulunas", "Speexdsp", "Lightweight", "None"
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
