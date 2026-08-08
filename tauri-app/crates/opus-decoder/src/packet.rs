use crate::Error;

pub(crate) const MAX_FRAMES: usize = 48;
pub(crate) const MAX_PACKET_SIZE: usize = 1500;
pub(crate) const MAX_DURATION_SAMPLES_48K: usize = 5760; // 120 ms @ 48 kHz

#[derive(Debug, Clone, Copy)]
pub(crate) struct ParsedPacket<'a> {
    // Used by CELT/SILK mode selection and for debugging/conformance.
    #[allow(dead_code)]
    pub toc: u8,
    #[allow(dead_code)]
    pub packet_channels: u8,
    pub frame_count: usize,
    // Used by the actual decoder to feed individual frames into CELT/SILK.
    #[allow(dead_code)]
    pub frames: [&'a [u8]; MAX_FRAMES],
    pub samples_per_frame_48k: usize,
}

impl<'a> ParsedPacket<'a> {
    #[allow(dead_code)]
    pub fn frames(&self) -> &[&'a [u8]] {
        &self.frames[..self.frame_count]
    }

    pub fn samples_per_channel_48k(&self) -> usize {
        self.samples_per_frame_48k * self.frame_count
    }

    pub fn samples_per_channel(&self, fs_hz: u32) -> usize {
        (self.samples_per_channel_48k() * fs_hz as usize) / 48_000
    }
}

pub(crate) fn parse_packet(packet: &[u8]) -> Result<ParsedPacket<'_>, Error> {
    if packet.is_empty() {
        return Err(Error::BadPacket);
    }
    if packet.len() > MAX_PACKET_SIZE {
        return Err(Error::PacketTooLarge {
            max: MAX_PACKET_SIZE,
            got: packet.len(),
        });
    }

    let toc = packet[0];
    let packet_channels = if (toc & 0x04) != 0 { 2 } else { 1 };
    let samples_per_frame_48k = samples_per_frame_48k(toc);

    let mut frames: [&[u8]; MAX_FRAMES] = [&[]; MAX_FRAMES];

    let code = toc & 0x03;
    let frame_count = match code {
        0 => {
            frames[0] = &packet[1..];
            1
        }
        1 => {
            let payload = &packet[1..];
            if payload.len() < 2 {
                return Err(Error::BadPacket);
            }
            if payload.len() % 2 != 0 {
                return Err(Error::BadPacket);
            }
            let sz0 = payload.len() / 2;
            frames[0] = &payload[..sz0];
            frames[1] = &payload[sz0..];
            2
        }
        2 => {
            let payload = &packet[1..];
            let (sz0, used) = parse_size(payload)?;
            let payload = &payload[used..];
            if sz0 > payload.len() {
                return Err(Error::BadPacket);
            }
            frames[0] = &payload[..sz0];
            frames[1] = &payload[sz0..];
            2
        }
        3 => {
            let payload = &packet[1..];
            if payload.is_empty() {
                return Err(Error::BadPacket);
            }

            let ch = payload[0];
            let frame_count = (ch & 0x3f) as usize;
            if !(1..=MAX_FRAMES).contains(&frame_count) {
                return Err(Error::BadPacket);
            }

            // RFC 6716: in the code-3 "frame count" byte, bit 6 indicates padding
            // and bit 7 indicates VBR.
            let has_padding = (ch & 0x40) != 0;
            let vbr = (ch & 0x80) != 0;

            let mut idx = 1usize; // into payload
            let mut data_end = payload.len(); // exclusive, relative to payload

            if has_padding {
                let (pad_len, used) = parse_padding_len(&payload[idx..])?;
                idx += used;
                if pad_len > payload.len().saturating_sub(idx) {
                    return Err(Error::BadPacket);
                }
                data_end = payload.len() - pad_len;
                if idx > data_end {
                    return Err(Error::BadPacket);
                }
            }

            let mut sizes = [0usize; MAX_FRAMES];
            if vbr {
                let mut sum = 0usize;
                for s in sizes.iter_mut().take(frame_count - 1) {
                    let (sz, used) = parse_size(&payload[idx..data_end])?;
                    idx += used;
                    *s = sz;
                    sum = sum.saturating_add(sz);
                }
                let remaining = data_end.saturating_sub(idx);
                if sum > remaining {
                    return Err(Error::BadPacket);
                }
                sizes[frame_count - 1] = remaining - sum;
            } else {
                let remaining = data_end.saturating_sub(idx);
                if remaining % frame_count != 0 {
                    return Err(Error::BadPacket);
                }
                let sz = remaining / frame_count;
                for s in &mut sizes[..frame_count] {
                    *s = sz;
                }
            }

            // Now slice out each frame from the payload.
            let mut off = idx;
            for i in 0..frame_count {
                let sz = sizes[i];
                if off + sz > data_end {
                    return Err(Error::BadPacket);
                }
                frames[i] = &payload[off..off + sz];
                off += sz;
            }
            if off != data_end {
                return Err(Error::BadPacket);
            }

            frame_count
        }
        _ => return Err(Error::BadPacket),
    };

    let total_samples_48k = samples_per_frame_48k.saturating_mul(frame_count);
    if total_samples_48k > MAX_DURATION_SAMPLES_48K {
        return Err(Error::BadPacket);
    }

    Ok(ParsedPacket {
        toc,
        packet_channels,
        frame_count,
        frames,
        samples_per_frame_48k,
    })
}

fn parse_size(data: &[u8]) -> Result<(usize, usize), Error> {
    if data.is_empty() {
        return Err(Error::BadPacket);
    }
    let b0 = data[0] as usize;
    if b0 < 252 {
        Ok((b0, 1))
    } else {
        if data.len() < 2 {
            return Err(Error::BadPacket);
        }
        let b1 = data[1] as usize;
        Ok(((b1 << 2) + b0, 2))
    }
}

fn parse_padding_len(data: &[u8]) -> Result<(usize, usize), Error> {
    let mut pad = 0usize;
    let mut used = 0usize;
    loop {
        if used >= data.len() {
            return Err(Error::BadPacket);
        }
        let p = data[used] as usize;
        used += 1;
        // RFC 6716 code-3 padding: each 255 byte contributes 254 and continues.
        let add = if p == 255 { 254 } else { p };
        pad = pad.saturating_add(add);
        if p != 255 {
            break;
        }
    }
    Ok((pad, used))
}

fn samples_per_frame_48k(toc: u8) -> usize {
    // Port of libopus' `opus_packet_get_samples_per_frame()`, specialized to Fs=48000.
    if (toc & 0x80) != 0 {
        let audiosize = ((toc >> 3) & 0x03) as usize;
        (48_000usize << audiosize) / 400
    } else if (toc & 0x60) == 0x60 {
        if (toc & 0x08) != 0 {
            48_000usize / 50
        } else {
            48_000usize / 100
        }
    } else {
        let audiosize = ((toc >> 3) & 0x03) as usize;
        if audiosize == 3 {
            (48_000usize * 60) / 1000
        } else {
            (48_000usize << audiosize) / 100
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::io::Read;
    use std::path::{Path, PathBuf};

    #[test]
    fn parse_code0_single_frame() {
        let packet = [0b0000_0000u8, 1, 2, 3, 4];
        let pp = parse_packet(&packet).unwrap();
        assert_eq!(pp.frame_count, 1);
        assert_eq!(pp.frames()[0], &[1, 2, 3, 4]);
    }

    #[test]
    fn parse_code1_two_frames_cbr() {
        let packet = [0b0000_0001u8, 1, 2, 3, 4];
        let pp = parse_packet(&packet).unwrap();
        assert_eq!(pp.frame_count, 2);
        assert_eq!(pp.frames()[0], &[1, 2]);
        assert_eq!(pp.frames()[1], &[3, 4]);
    }

    #[test]
    fn parse_code1_odd_payload_is_rejected() {
        let packet = [0b0000_0001u8, 1, 2, 3];
        let err = parse_packet(&packet).unwrap_err();
        assert!(matches!(err, Error::BadPacket));
    }

    #[test]
    fn parse_code2_two_frames_vbr_short_size() {
        // size field <252 => one byte.
        let packet = [0b0000_0010u8, 2, 0xaa, 0xbb, 0xcc];
        let pp = parse_packet(&packet).unwrap();
        assert_eq!(pp.frame_count, 2);
        assert_eq!(pp.frames()[0], &[0xaa, 0xbb]);
        assert_eq!(pp.frames()[1], &[0xcc]);
    }

    #[test]
    fn parse_code2_two_frames_vbr_long_size() {
        // size field >=252 => two bytes; size = 4*b1 + b0.
        // Pick b0=252, b1=1 => size=256.
        let mut packet = Vec::<u8>::new();
        packet.push(0b0000_0010u8);
        packet.push(252);
        packet.push(1);
        packet.extend(std::iter::repeat_n(0x11, 256));
        packet.extend(std::iter::repeat_n(0x22, 10));

        let pp = parse_packet(&packet).unwrap();
        assert_eq!(pp.frame_count, 2);
        assert_eq!(pp.frames()[0].len(), 256);
        assert_eq!(pp.frames()[1].len(), 10);
        assert!(pp.frames()[0].iter().all(|&b| b == 0x11));
        assert!(pp.frames()[1].iter().all(|&b| b == 0x22));
    }

    #[test]
    fn parse_code3_cbr_n_frames() {
        // code=3, count=3, CBR, no padding
        let mut packet = Vec::<u8>::new();
        packet.push(0b0000_0011u8);
        packet.push(0b0000_0011u8); // 3 frames
        packet.extend([1, 2, 3, 4, 5, 6]); // 6 bytes => 2 bytes per frame

        let pp = parse_packet(&packet).unwrap();
        assert_eq!(pp.frame_count, 3);
        assert_eq!(pp.frames()[0], &[1, 2]);
        assert_eq!(pp.frames()[1], &[3, 4]);
        assert_eq!(pp.frames()[2], &[5, 6]);
    }

    #[test]
    fn parse_code3_vbr_n_frames() {
        // code=3, count=3, VBR, no padding
        // sizes: 1,2, last is remainder (3)
        let mut packet = vec![
            0b0000_0011u8,
            0b1000_0011u8, // VBR + 3 frames
            1,             // size0
            2,             // size1
        ];
        packet.extend([0xa0]); // frame0 (1)
        packet.extend([0xb0, 0xb1]); // frame1 (2)
        packet.extend([0xc0, 0xc1, 0xc2]); // frame2 (3)

        let pp = parse_packet(&packet).unwrap();
        assert_eq!(pp.frame_count, 3);
        assert_eq!(pp.frames()[0], &[0xa0]);
        assert_eq!(pp.frames()[1], &[0xb0, 0xb1]);
        assert_eq!(pp.frames()[2], &[0xc0, 0xc1, 0xc2]);
    }

    #[test]
    fn parse_code3_padding() {
        // code=3, count=1, CBR, padding present with pad_len=2.
        let mut packet = Vec::<u8>::new();
        packet.push(0b0000_0011u8);
        packet.push(0b0100_0001u8); // padding + 1 frame
        packet.push(2); // pad len
        packet.extend([0x42, 0x43, 0x44]); // frame data (3 bytes)
        packet.extend([0, 0]); // padding bytes

        let pp = parse_packet(&packet).unwrap();
        assert_eq!(pp.frame_count, 1);
        assert_eq!(pp.frames()[0], &[0x42, 0x43, 0x44]);
    }

    #[test]
    fn samples_per_frame_uses_config_low_bits() {
        // CELT-only config 19 (0b10011) is 20 ms => 960 samples at 48 kHz.
        // This validates we use config low bits (toc>>3)&0x03, not toc bits 5..6.
        let toc = 19u8 << 3;
        assert_eq!(samples_per_frame_48k(toc), 960);
    }

    #[test]
    #[ignore = "requires OPUS_TESTVECTORS_DIR (or testdata/opus_testvectors)"]
    fn parse_official_vectors_smoke() {
        let dir = vectors_dir();
        let entries = fs::read_dir(&dir).unwrap_or_else(|e| {
            panic!("failed to read vectors dir {}: {e}", dir.to_string_lossy())
        });

        let mut bit_files = Vec::<PathBuf>::new();
        for ent in entries {
            let ent = ent.unwrap();
            let p = ent.path();
            if p.extension().and_then(|s| s.to_str()) == Some("bit") {
                bit_files.push(p);
            }
        }
        bit_files.sort();
        assert!(!bit_files.is_empty(), "no .bit files in {}", dir.display());

        for bit in bit_files {
            let packets = read_opus_demo_stream(&bit).unwrap_or_else(|e| {
                panic!("failed to read {}: {e}", bit.display());
            });
            for p in packets.into_iter().flatten() {
                parse_packet(&p).unwrap_or_else(|e| {
                    panic!(
                        "parse failed for {} packet len {}: {e}",
                        bit.display(),
                        p.len()
                    );
                });
            }
        }
    }

    fn vectors_dir() -> PathBuf {
        if let Ok(p) = std::env::var("OPUS_TESTVECTORS_DIR") {
            return PathBuf::from(p);
        }
        PathBuf::from("testdata/opus_testvectors")
    }

    fn read_opus_demo_stream(path: &Path) -> std::io::Result<Vec<Option<Vec<u8>>>> {
        let mut f = fs::File::open(path)?;
        let mut bytes = Vec::new();
        f.read_to_end(&mut bytes)?;

        let mut i = 0usize;
        let mut out = Vec::new();
        while i < bytes.len() {
            if i + 4 > bytes.len() {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::UnexpectedEof,
                    "truncated packet_len",
                ));
            }
            let packet_len =
                u32::from_be_bytes([bytes[i], bytes[i + 1], bytes[i + 2], bytes[i + 3]]) as usize;
            i += 4;

            if i + 4 > bytes.len() {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::UnexpectedEof,
                    "truncated final_range",
                ));
            }
            i += 4;

            if i + packet_len > bytes.len() {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::UnexpectedEof,
                    "truncated packet",
                ));
            }
            let packet = if packet_len == 0 {
                None
            } else {
                Some(bytes[i..i + packet_len].to_vec())
            };
            i += packet_len;

            out.push(packet);
        }

        Ok(out)
    }
}
