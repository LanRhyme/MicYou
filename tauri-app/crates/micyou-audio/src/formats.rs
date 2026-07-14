/// Audio format identifiers matching the protocol wire format.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum AudioFormat {
    Pcm16 = 2,
    PcmU8 = 3,
    F32 = 4,
    Pcm24 = 6,
}

impl AudioFormat {
    pub fn from_i32(v: i32) -> Option<Self> {
        match v {
            2 => Some(Self::Pcm16),
            3 => Some(Self::PcmU8),
            4 => Some(Self::F32),
            6 => Some(Self::Pcm24),
            _ => None,
        }
    }
}

/// Decode raw audio bytes into normalized f32 PCM samples.
pub fn decode_to_f32(buffer: &[u8], format: AudioFormat, out: &mut Vec<f32>) {
    match format {
        AudioFormat::Pcm16 => {
            let capacity = buffer.len() / 2;
            out.reserve(capacity);
            for chunk in buffer.chunks_exact(2) {
                let sample_i16 = i16::from_le_bytes([chunk[0], chunk[1]]);
                out.push(sample_i16 as f32 / 32768.0);
            }
        }
        AudioFormat::PcmU8 => {
            out.reserve(buffer.len());
            for &byte in buffer {
                let sample_f32 = (byte as f32 - 128.0) / 128.0;
                out.push(sample_f32);
            }
        }
        AudioFormat::F32 => {
            let capacity = buffer.len() / 4;
            out.reserve(capacity);
            for chunk in buffer.chunks_exact(4) {
                let sample_f32 = f32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]);
                out.push(sample_f32);
            }
        }
        AudioFormat::Pcm24 => {
            let capacity = buffer.len() / 3;
            out.reserve(capacity);
            for chunk in buffer.chunks_exact(3) {
                let sample24 =
                    (chunk[0] as i32) | ((chunk[1] as i32) << 8) | ((chunk[2] as i8 as i32) << 16);
                let sample_f32 = (sample24 as f32) / 8388608.0;
                out.push(sample_f32);
            }
        }
    }
}

/// Convert f32 PCM samples to i16 PCM bytes.
pub fn f32_to_pcm16(float32_bytes: &[u8]) -> Vec<u8> {
    let num_floats = float32_bytes.len() / 4;
    let mut pcm = Vec::with_capacity(num_floats * 2);
    for i in 0..num_floats {
        let offset = i * 4;
        let sample = f32::from_le_bytes([
            float32_bytes[offset],
            float32_bytes[offset + 1],
            float32_bytes[offset + 2],
            float32_bytes[offset + 3],
        ]);
        let clamped = sample.clamp(-1.0, 1.0);
        let pcm_sample = (clamped * 32767.0) as i16;
        pcm.extend_from_slice(&pcm_sample.to_le_bytes());
    }
    pcm
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_decode_pcm16() {
        // i16 value 16384 = 0.5 in normalized float
        let bytes = 16384i16.to_le_bytes();
        let mut out = Vec::new();
        decode_to_f32(&bytes, AudioFormat::Pcm16, &mut out);
        assert_eq!(out.len(), 1);
        assert!((out[0] - 0.5).abs() < 0.001);
    }

    #[test]
    fn test_decode_f32() {
        let bytes = 0.5f32.to_le_bytes();
        let mut out = Vec::new();
        decode_to_f32(&bytes, AudioFormat::F32, &mut out);
        assert_eq!(out.len(), 1);
        assert!((out[0] - 0.5).abs() < 0.001);
    }

    #[test]
    fn test_f32_to_pcm16_one() {
        let input = 1.0f32.to_le_bytes();
        let pcm = f32_to_pcm16(&input);
        assert_eq!(pcm.len(), 2);
        let sample = i16::from_le_bytes([pcm[0], pcm[1]]);
        assert_eq!(sample, 32767);
    }

    #[test]
    fn test_f32_to_pcm16_zero() {
        let input = 0.0f32.to_le_bytes();
        let pcm = f32_to_pcm16(&input);
        let sample = i16::from_le_bytes([pcm[0], pcm[1]]);
        assert_eq!(sample, 0);
    }
}
