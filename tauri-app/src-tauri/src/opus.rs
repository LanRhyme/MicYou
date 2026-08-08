//! Pure-Rust Opus decoder (`opus-decoder`, RFC 8251 conformant, no FFI) for
//! the Opus audio payloads MicYou sends from Android. Only the decoder path
//! needed by the server audio thread is exposed; the encoder lives on-device
//! (concentus).

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Channels {
    Mono,
    Stereo,
}

impl Channels {
    pub fn from_channel_count(count: usize) -> Option<Self> {
        match count {
            1 => Some(Channels::Mono),
            2 => Some(Channels::Stereo),
            _ => None,
        }
    }

    fn as_usize(self) -> usize {
        match self {
            Channels::Mono => 1,
            Channels::Stereo => 2,
        }
    }
}

pub struct Decoder {
    raw: opus_decoder::OpusDecoder,
    channels: usize,
}

impl Decoder {
    /// Create a decoder for `sample_rate` (8000/12000/16000/24000/48000) and
    /// `channels` (1 or 2). Errors if the arguments are invalid.
    pub fn new(sample_rate: u32, channels: Channels) -> Result<Self, String> {
        let channel_count = channels.as_usize();
        let raw = opus_decoder::OpusDecoder::new(sample_rate, channel_count)
            .map_err(|e| format!("Failed to create Opus decoder: {}", e))?;
        Ok(Decoder {
            raw,
            channels: channel_count,
        })
    }

    /// Decode one Opus packet into interleaved f32 samples. `output` must be
    /// sized for at least the expected frame (e.g. `sample_rate/50 * channels`).
    /// Returns the number of samples per channel decoded, or an error string.
    pub fn decode_float(&mut self, input: &[u8], output: &mut [f32]) -> Result<usize, String> {
        catch_decode(|| self.raw.decode_float(input, output, false))
    }

    /// Feed packet-loss concealment for a missing 20 ms frame. Returns the
    /// number of samples per channel synthesized, or an error string.
    pub fn decode_plc(&mut self, output: &mut [f32]) -> Result<usize, String> {
        // An empty packet triggers the decoder's packet-loss concealment.
        catch_decode(|| self.raw.decode_float(&[], output, false))
    }
}

/// Wrap the third-party decoder call so a panic (e.g. arithmetic overflow on a
/// corrupted Opus frame in debug builds) surfaces as an `Err` instead of taking
/// down the audio thread. Callers should rebuild the decoder after an error.
fn catch_decode<F, E>(f: F) -> Result<usize, String>
where
    F: FnOnce() -> Result<usize, E>,
    E: std::fmt::Display,
{
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)) {
        Ok(Ok(n)) => Ok(n),
        Ok(Err(e)) => Err(e.to_string()),
        Err(_) => Err("opus decoder panicked".to_string()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_invalid_channel_count() {
        assert!(Channels::from_channel_count(0).is_none());
        assert!(Channels::from_channel_count(3).is_none());
        assert_eq!(Channels::from_channel_count(1), Some(Channels::Mono));
    }

    #[test]
    fn rejects_unsupported_sample_rate() {
        assert!(Decoder::new(12345, Channels::Mono).is_err());
    }

    #[test]
    fn creates_and_releases_decoder() {
        assert!(Decoder::new(48_000, Channels::Mono).is_ok());
        assert!(Decoder::new(48_000, Channels::Stereo).is_ok());
    }

    /// Decodes a real 20 ms/48 kHz mono Opus packet (encoded by libopus from a
    /// 440 Hz sine) and checks the pure-Rust decoder reproduces the reference
    /// libopus output within tolerance.
    #[test]
    fn decodes_reference_packet_matches_libopus() {
        const PACKET_HEX: &str = "f8b50deb1eb40e944932d3fd268028017f54b83a73ed169712de61de6705b3cd153aded6a7fe583bd5673e17593738c4f4cd03564506efb72678f4406d58337c9b2adf85f80ed221b1f3bc98a0b12038c4b2216ab617916cbb6c693c18bc46fcba872c6fbe19067f828873e8cc5d0a8873e930e5ea912ace292b64752a6a327bd60aa819378b0975aa8ba6a3316955d2b4a74895396b8524eb90e7bea61cb1843845102e486d50f4722f06fecfb161fc8b0690f11468ec47edae370b7a2a0c1afe4831ee2d376d";
        let packet: Vec<u8> = (0..PACKET_HEX.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&PACKET_HEX[i..i + 2], 16).unwrap())
            .collect();

        let mut decoder = Decoder::new(48_000, Channels::Mono).unwrap();
        let mut out = vec![0f32; 960];
        let frames = decoder.decode_float(&packet, &mut out).unwrap();
        assert_eq!(frames, 960);

        // Reference libopus output for the same packet.
        let ref_first = [0.0f32; 8];
        let ref_last = [
            -0.21212, -0.19436, -0.17594, -0.15690, -0.13733, -0.11728, -0.09685, -0.07610,
        ];
        let ref_sum = -6.0122f32;

        for (got, expected) in out[..8].iter().zip(ref_first.iter()) {
            assert!((got - expected).abs() < 0.02, "first-sample mismatch: {got} vs {expected}");
        }
        for (got, expected) in out[952..960].iter().zip(ref_last.iter()) {
            assert!((got - expected).abs() < 0.02, "last-sample mismatch: {got} vs {expected}");
        }
        let sum: f32 = out.iter().sum();
        assert!(
            (sum - ref_sum).abs() < 1.0,
            "energy mismatch: sum {sum} vs reference {ref_sum}"
        );
    }
}
