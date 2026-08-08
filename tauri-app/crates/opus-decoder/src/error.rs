use core::fmt;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Error {
    InvalidSampleRate(u32),
    InvalidChannels(u8),
    PacketTooLarge { max: usize, got: usize },
    OutputTooSmall { needed: usize, got: usize },
    BadPacket,
    NotImplemented,
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::InvalidSampleRate(fs) => write!(f, "invalid Opus output sample rate: {fs}"),
            Error::InvalidChannels(ch) => write!(f, "invalid Opus channel count: {ch}"),
            Error::PacketTooLarge { max, got } => {
                write!(f, "Opus packet too large (max {max}, got {got})")
            }
            Error::OutputTooSmall { needed, got } => {
                write!(f, "output buffer too small: needed {needed}, got {got}")
            }
            Error::BadPacket => write!(f, "invalid Opus packet"),
            Error::NotImplemented => write!(f, "not implemented"),
        }
    }
}

impl std::error::Error for Error {}
