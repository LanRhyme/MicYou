pub mod engine;
pub mod process;
pub mod jitter_buffer;
pub mod pipeline;

pub use engine::{AudioOutputManager, RubatoResampler};
pub use process::dsp::{DspProcessor, compute_rms};
pub use process::settings::{AudioDspSettings, EqualizerConfig};

pub fn init_onnx_runtime() {
    // ONNX Runtime is initialized automatically via ort's download-binaries feature.
    // The prebuilt shared library is downloaded at build time and statically linked.
}
