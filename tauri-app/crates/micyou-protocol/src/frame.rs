use bytes::BytesMut;
use prost::Message;

use crate::micyou::MessageWrapper;

pub const HEADER_SIZE: usize = 8; // 4 bytes magic + 4 bytes length

#[derive(Debug)]
pub enum FrameError {
    Incomplete,
    InvalidMagic,
    DecodeError(prost::DecodeError),
}

impl std::fmt::Display for FrameError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            FrameError::Incomplete => write!(f, "incomplete frame"),
            FrameError::InvalidMagic => write!(f, "invalid packet magic"),
            FrameError::DecodeError(e) => write!(f, "decode error: {}", e),
        }
    }
}

impl std::error::Error for FrameError {}

/// Encode a MessageWrapper into a framed byte buffer.
///
/// Format: [magic: i32 BE][payload_len: i32 BE][protobuf payload]
pub fn encode_frame(msg: &MessageWrapper, magic: i32) -> BytesMut {
    let mut payload = BytesMut::new();
    msg.encode(&mut payload)
        .expect("protobuf encode should not fail");

    let mut frame = BytesMut::with_capacity(HEADER_SIZE + payload.len());
    frame.extend_from_slice(&magic.to_be_bytes());
    frame.extend_from_slice(&(payload.len() as i32).to_be_bytes());
    frame.extend_from_slice(&payload);
    frame
}

/// Decode one frame from the buffer.
///
/// Returns `(message, consumed_bytes)` on success.
/// Returns `FrameError::Incomplete` if more data is needed.
pub fn decode_frame(
    buf: &[u8],
    expected_magic: i32,
) -> Result<(MessageWrapper, usize), FrameError> {
    if buf.len() < HEADER_SIZE {
        return Err(FrameError::Incomplete);
    }

    let magic = i32::from_be_bytes(buf[0..4].try_into().unwrap());
    if magic != expected_magic {
        return Err(FrameError::InvalidMagic);
    }

    let payload_len = i32::from_be_bytes(buf[4..8].try_into().unwrap()) as usize;
    if buf.len() < HEADER_SIZE + payload_len {
        return Err(FrameError::Incomplete);
    }

    let msg = MessageWrapper::decode(&buf[HEADER_SIZE..HEADER_SIZE + payload_len])
        .map_err(FrameError::DecodeError)?;

    Ok((msg, HEADER_SIZE + payload_len))
}
