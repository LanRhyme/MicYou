pub mod aec;
#[cfg(feature = "dsp")]
pub mod dsp;
pub mod engine;
pub mod loopback;
pub mod mixer;

pub use aec::AecFailure;
#[cfg(feature = "dsp")]
pub use dsp::{AudioDspSettings, DspProcessor, EqualizerConfig};
pub use engine::{AudioOutputManager, RubatoResampler};
pub use mixer::{SoundMixer, SoundEffect};
pub use loopback::LoopbackCapture;

pub fn init_onnx_runtime() {
    #[cfg(feature = "noise-suppression")]
    {
        // Standard ORT initializes automatically
    }
}
