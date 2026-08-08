#![allow(dead_code)]

use std::fs;
use std::io::{self, Read};
use std::path::{Path, PathBuf};

/// Minimal vector metadata used by tests and benchmarks.
#[allow(dead_code)]
#[derive(Debug, Clone)]
pub struct VectorSpec {
    /// Relative `.bit` payload path.
    pub bit: String,
    /// Output sample rate used by the harness.
    pub sample_rate: u32,
    /// Output channel count used by the harness.
    pub channels: usize,
}

/// Resolve the local Opus vector directory used by tests and benches.
///
/// Parameters: none.
/// Returns: absolute path to `testdata/opus_testvectors` when possible.
pub fn vectors_dir() -> PathBuf {
    if let Ok(path) = std::env::var("OPUS_TESTVECTORS_DIR") {
        return PathBuf::from(path);
    }
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../testdata/opus_testvectors")
        .canonicalize()
        .unwrap_or_else(|_| PathBuf::from("testdata/opus_testvectors"))
}

/// Load one vector description from the RFC manifest.
///
/// Parameters: canonical `vector_name` like `testvector07`.
/// Returns: parsed vector metadata for the matching manifest entry.
pub fn load_vector_spec(vector_name: &str) -> io::Result<VectorSpec> {
    let manifest = fs::read_to_string(vectors_dir().join("vectors.txt"))?;
    for line in manifest.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }

        let mut fields = line.split_whitespace();
        let name = fields.next().ok_or_else(|| {
            io::Error::new(
                io::ErrorKind::InvalidData,
                "missing vector name in manifest",
            )
        })?;
        if name != vector_name {
            continue;
        }

        let bit = fields
            .next()
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing .bit path"))?
            .to_string();
        let _dec = fields
            .next()
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing .dec path"))?;
        let sample_rate = fields
            .next()
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing sample rate"))?
            .parse::<u32>()
            .map_err(|err| io::Error::new(io::ErrorKind::InvalidData, err))?;
        let channels = fields
            .next()
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing channel count"))?
            .parse::<usize>()
            .map_err(|err| io::Error::new(io::ErrorKind::InvalidData, err))?;

        return Ok(VectorSpec {
            bit,
            sample_rate,
            channels,
        });
    }

    Err(io::Error::new(
        io::ErrorKind::NotFound,
        format!("vector not found in manifest: {vector_name}"),
    ))
}

/// Load raw packet payloads for one official Opus test vector.
///
/// Parameters: canonical `vector_name` like `testvector07`.
/// Returns: all non-lost packet payloads from the `.bit` stream.
pub fn load_packets(vector_name: &str) -> io::Result<Vec<Vec<u8>>> {
    let spec = load_vector_spec(vector_name)?;
    let packets = read_opus_demo_stream(&vectors_dir().join(spec.bit))?;
    Ok(packets.into_iter().flatten().collect())
}

/// Read an `opus_demo` `.bit` stream into packet payloads.
///
/// Parameters: absolute `path` to the `.bit` vector stream.
/// Returns: ordered payload list where `None` denotes PLC/lost packet records.
pub fn read_opus_demo_stream(path: &Path) -> io::Result<Vec<Option<Vec<u8>>>> {
    let mut file = fs::File::open(path)?;
    let mut bytes = Vec::new();
    file.read_to_end(&mut bytes)?;

    let mut packets = Vec::new();
    let mut index = 0usize;
    while index < bytes.len() {
        if index + 4 > bytes.len() {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "truncated packet_len",
            ));
        }
        let packet_len = u32::from_be_bytes([
            bytes[index],
            bytes[index + 1],
            bytes[index + 2],
            bytes[index + 3],
        ]) as usize;
        index += 4;

        if index + 4 > bytes.len() {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "truncated final_range",
            ));
        }
        let _expected_final_range = u32::from_be_bytes([
            bytes[index],
            bytes[index + 1],
            bytes[index + 2],
            bytes[index + 3],
        ]);
        index += 4;

        if index + packet_len > bytes.len() {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "truncated packet",
            ));
        }
        let packet = if packet_len == 0 {
            None
        } else {
            Some(bytes[index..index + packet_len].to_vec())
        };
        index += packet_len;
        packets.push(packet);
    }

    Ok(packets)
}

/// Build a synthetic multistream packet with self-delimited size prefixes.
///
/// Parameters: elementary Opus `sub_packets` in stream order.
/// Returns: concatenated multistream packet bytes.
pub fn build_multistream_packet(sub_packets: &[&[u8]]) -> Vec<u8> {
    let mut packet = Vec::new();
    for (stream_idx, sub_packet) in sub_packets.iter().enumerate() {
        if stream_idx + 1 != sub_packets.len() {
            packet.extend(encode_self_delimited_size(sub_packet.len()));
        }
        packet.extend_from_slice(sub_packet);
    }
    packet
}

/// Encode one multistream self-delimited sub-packet length.
///
/// Parameters: sub-packet byte `len`.
/// Returns: one- or two-byte self-delimited size field.
fn encode_self_delimited_size(len: usize) -> Vec<u8> {
    if len < 252 {
        vec![u8::try_from(len).expect("short self-delimited size fits in u8")]
    } else {
        let remainder = len - 252;
        vec![
            u8::try_from(252 + (remainder & 0x03))
                .expect("self-delimited prefix first byte fits in u8"),
            u8::try_from(remainder >> 2).expect("self-delimited prefix second byte fits in u8"),
        ]
    }
}
