pub mod engine;
#[cfg(feature = "dsp")]
pub mod dsp;
pub mod loopback;

pub use engine::{AudioOutputManager, RubatoResampler};
#[cfg(feature = "dsp")]
pub use dsp::{AudioDspSettings, DspProcessor, EqualizerConfig};
pub use loopback::LoopbackCapture;

pub fn init_onnx_runtime() {
    #[cfg(feature = "noise-suppression")]
    {
        // Standard ORT initializes automatically
    }
}
