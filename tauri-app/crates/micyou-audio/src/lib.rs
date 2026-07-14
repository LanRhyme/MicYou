#[cfg(feature = "dsp")]
pub mod dsp;
pub mod engine;
pub mod formats;
pub mod jitter;
pub mod pipeline;

#[cfg(feature = "dsp")]
pub use dsp::{AudioDspSettings, DspProcessor, EqualizerConfig};
pub use engine::{AudioOutputManager, RubatoResampler};
pub use formats::{decode_to_f32, f32_to_pcm16, AudioFormat};
pub use jitter::JitterBuffer;
pub use pipeline::{AudioPipeline, PipelineConfig, PipelineEvent};

pub fn init_onnx_runtime() {
    #[cfg(feature = "noise-suppression")]
    {
        // Standard ORT initializes automatically
    }
}
