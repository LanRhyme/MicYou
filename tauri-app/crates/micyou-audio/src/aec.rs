/// Stable reason codes reported when AEC becomes unavailable at runtime.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum AecFailure {
    InferenceFailed,
    ModelLoadFailed,
    ModelMissing,
    PipeWireUnavailable,
    ReferenceLost,
    VirtualSourceMissing,
}

impl std::fmt::Display for AecFailure {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(self.as_str())
    }
}

impl AecFailure {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::InferenceFailed => "inference_failed",
            Self::ModelLoadFailed => "model_load_failed",
            Self::ModelMissing => "model_missing",
            Self::PipeWireUnavailable => "pipewire_unavailable",
            Self::ReferenceLost => "reference_lost",
            Self::VirtualSourceMissing => "virtual_source_missing",
        }
    }
}

#[cfg(test)]
mod tests {
    use super::AecFailure;

    #[test]
    fn failure_codes_remain_stable_for_frontends() {
        assert_eq!(AecFailure::InferenceFailed.as_str(), "inference_failed");
        assert_eq!(AecFailure::ModelLoadFailed.as_str(), "model_load_failed");
        assert_eq!(AecFailure::ModelMissing.as_str(), "model_missing");
        assert_eq!(
            AecFailure::PipeWireUnavailable.as_str(),
            "pipewire_unavailable"
        );
        assert_eq!(AecFailure::ReferenceLost.as_str(), "reference_lost");
        assert_eq!(
            AecFailure::VirtualSourceMissing.as_str(),
            "virtual_source_missing"
        );
    }
}
