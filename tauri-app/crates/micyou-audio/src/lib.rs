pub mod engine;
#[cfg(feature = "dsp")]
pub mod dsp;

pub use engine::{AudioOutputManager, RubatoResampler};
#[cfg(feature = "dsp")]
pub use dsp::{AudioDspSettings, DspProcessor, EqualizerConfig};

pub fn init_onnx_runtime() {
    #[cfg(feature = "noise-suppression")]
    ort::set_api(ort_tract::api());
}
