pub mod engine;
pub mod process;
pub mod jitter_buffer;
pub mod pipeline;

pub use engine::{AudioOutputManager, RubatoResampler};
pub use process::dsp::{DspProcessor, compute_rms};
pub use process::settings::{AudioDspSettings, EqualizerConfig};

pub fn init_onnx_runtime() {
    ort::set_api(ort_tract::api());
}
