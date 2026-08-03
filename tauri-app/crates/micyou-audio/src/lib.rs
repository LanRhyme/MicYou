#[cfg(feature = "dsp")]
pub mod dsp;
pub mod engine;
pub mod loopback;

#[cfg(feature = "dsp")]
pub use dsp::{AudioDspSettings, DspProcessor, EqualizerConfig};
pub use engine::{AudioOutputManager, RubatoResampler};
pub use loopback::LoopbackCapture;

pub fn init_onnx_runtime() {
    #[cfg(feature = "noise-suppression")]
    {
        // Standard ORT initializes automatically
    }
}
