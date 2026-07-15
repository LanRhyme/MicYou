use super::equalizer_config::EqualizerConfig;
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct AudioDspSettings {
    pub gain: f32,
    pub ns_enabled: bool,
    pub ns_type: String,
    pub ns_intensity: f32,
    pub dereverb_enabled: bool,
    pub dereverb_level: f32,
    pub agc_enabled: bool,
    pub agc_target: f32,
    pub agc_attack: f32,
    pub agc_decay: f32,
    pub vad_enabled: bool,
    pub vad_threshold: f32,
    pub processing_chain: Vec<String>,
    pub equalizer: EqualizerConfig,
}

impl Default for AudioDspSettings {
    fn default() -> Self {
        Self {
            gain: 0.0,
            ns_enabled: false,
            ns_type: "PureVox".to_string(),
            ns_intensity: 50.0,
            dereverb_enabled: false,
            dereverb_level: 30.0,
            agc_enabled: true,
            agc_target: 8000.0,
            agc_attack: 20.0,
            agc_decay: 100.0,
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
