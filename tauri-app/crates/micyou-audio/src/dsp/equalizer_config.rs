/// EQ band gains for an 11-band equalizer.
#[derive(serde::Serialize, serde::Deserialize, Debug, Clone)]
pub struct EqualizerConfig {
    pub enabled: bool,
    pub pre_amp: f32,
    pub gains: [f32; 11],
}

impl Default for EqualizerConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            pre_amp: 0.0,
            gains: [0.0; 11],
        }
    }
}
