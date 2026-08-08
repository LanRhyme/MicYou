//! Opus multistream decoder — wraps multiple `OpusDecoder` instances
//! and demultiplexes channel mapping per RFC 7845 §5.1.
//!
//! Mirrors the wrapper logic from libopus `opus_multistream_decoder.c`.

use crate::packet;
use crate::{OpusDecoder, OpusError};

/// Opus multistream decoder.
///
/// Holds one `OpusDecoder` per stream. Coupled streams use stereo decoders and
/// remaining streams use mono decoders.
pub struct OpusMultistreamDecoder {
    /// One decoder per stream. Coupled streams are stereo (2ch), rest mono.
    decoders: Vec<OpusDecoder>,
    /// Total output channels (1–255).
    nb_channels: usize,
    /// Total streams in packet (coupled + mono).
    nb_streams: usize,
    /// Streams that are stereo-coupled (always the first `nb_coupled_streams`).
    nb_coupled_streams: usize,
    /// `mapping[output_channel] = index into interleaved decoder outputs`.
    /// `255` means "silence this output channel".
    mapping: Vec<u8>,
    /// Output sample rate (same for all streams).
    sample_rate: u32,
}

impl OpusMultistreamDecoder {
    /// Create a new multistream decoder.
    ///
    /// Parameters: `sample_rate`, total output `nb_channels`, `nb_streams`,
    /// `nb_coupled_streams`, and per-output-channel `mapping`.
    /// Returns: initialized multistream decoder on success.
    ///
    /// # Examples
    ///
    /// ```rust
    /// use opus_decoder::OpusMultistreamDecoder;
    ///
    /// let decoder = OpusMultistreamDecoder::new(48_000, 2, 2, 0, &[0, 1])?;
    /// # let _ = decoder;
    /// # Ok::<(), opus_decoder::OpusError>(())
    /// ```
    pub fn new(
        sample_rate: u32,
        nb_channels: usize,
        nb_streams: usize,
        nb_coupled_streams: usize,
        mapping: &[u8],
    ) -> Result<Self, OpusError> {
        if nb_channels == 0 || nb_channels > 255 {
            return Err(OpusError::InvalidArgument("nb_channels"));
        }
        if nb_streams == 0 {
            return Err(OpusError::InvalidArgument("nb_streams"));
        }
        if nb_coupled_streams > nb_streams {
            return Err(OpusError::InvalidArgument("nb_coupled_streams"));
        }
        if nb_streams > 255usize.saturating_sub(nb_coupled_streams) {
            return Err(OpusError::InvalidArgument("nb_streams"));
        }
        if mapping.len() != nb_channels {
            return Err(OpusError::InvalidArgument("mapping"));
        }

        let total_decoded_channels = (2 * nb_coupled_streams) + (nb_streams - nb_coupled_streams);
        for &slot in mapping {
            if slot != 255 && usize::from(slot) >= total_decoded_channels {
                return Err(OpusError::InvalidArgument("mapping"));
            }
        }

        let mut decoders = Vec::with_capacity(nb_streams);
        for stream_idx in 0..nb_streams {
            let channels = if stream_idx < nb_coupled_streams {
                2
            } else {
                1
            };
            decoders.push(OpusDecoder::new(sample_rate, channels)?);
        }

        Ok(Self {
            decoders,
            nb_channels,
            nb_streams,
            nb_coupled_streams,
            mapping: mapping.to_vec(),
            sample_rate,
        })
    }

    /// Decode a multistream packet into interleaved i16 PCM.
    ///
    /// Parameters: multistream `packet`, writable interleaved `pcm`, and `fec`.
    /// - `fec`: reserved for future in-band FEC support. Currently treated as
    ///   packet loss concealment (PLC) when `true`. Pass `false` for normal decode.
    ///
    /// Returns: decoded samples per output channel.
    ///
    /// # Examples
    ///
    /// ```rust,no_run
    /// use opus_decoder::OpusMultistreamDecoder;
    ///
    /// let mut decoder = OpusMultistreamDecoder::new(48_000, 2, 2, 0, &[0, 1])?;
    /// let packet = std::fs::read("multistream-frame.opus")?;
    /// let mut pcm = vec![0i16; 960 * 2];
    /// let samples = decoder.decode(&packet, &mut pcm, false)?;
    /// # let _ = samples;
    /// # Ok::<(), Box<dyn std::error::Error>>(())
    /// ```
    pub fn decode(
        &mut self,
        packet: &[u8],
        pcm: &mut [i16],
        fec: bool,
    ) -> Result<usize, OpusError> {
        let sub_packets = if packet.is_empty() || fec {
            vec![&[][..]; self.nb_streams]
        } else {
            split_multistream_packet(packet, self.nb_streams)?
        };
        let frame_size = self.expected_frame_size(&sub_packets, fec)?;
        let needed = frame_size * self.nb_channels;
        if pcm.len() < needed {
            return Err(OpusError::BufferTooSmall);
        }
        if frame_size == 0 {
            return Ok(0);
        }

        let mut stream_pcm = Vec::with_capacity(self.nb_streams);
        for (stream_idx, sub_packet) in sub_packets.iter().enumerate() {
            let channels = self.stream_channels(stream_idx);
            let mut buf = vec![0i16; frame_size * channels];
            let written = self.decoders[stream_idx].decode(sub_packet, &mut buf, fec)?;
            if written != frame_size {
                return Err(OpusError::InvalidPacket);
            }
            stream_pcm.push(buf);
        }

        for (channel_idx, &slot) in self.mapping.iter().enumerate() {
            if slot == 255 {
                zero_output_channel_i16(pcm, self.nb_channels, channel_idx, frame_size);
                continue;
            }

            let (stream_idx, source_channel) = self.slot_to_stream_channel(usize::from(slot));
            copy_output_channel_i16(
                pcm,
                self.nb_channels,
                channel_idx,
                &stream_pcm[stream_idx],
                self.stream_channels(stream_idx),
                source_channel,
                frame_size,
            );
        }

        Ok(frame_size)
    }

    /// Decode a multistream packet into interleaved f32 PCM.
    ///
    /// Parameters: multistream `packet`, writable interleaved `pcm`, and `fec`.
    /// - `fec`: reserved for future in-band FEC support. Currently treated as
    ///   packet loss concealment (PLC) when `true`. Pass `false` for normal decode.
    ///
    /// Returns: decoded samples per output channel.
    ///
    /// # Examples
    ///
    /// ```rust,no_run
    /// use opus_decoder::OpusMultistreamDecoder;
    ///
    /// let mut decoder = OpusMultistreamDecoder::new(48_000, 2, 2, 0, &[0, 1])?;
    /// let packet = std::fs::read("multistream-frame.opus")?;
    /// let mut pcm = vec![0.0f32; 960 * 2];
    /// let samples = decoder.decode_float(&packet, &mut pcm, false)?;
    /// # let _ = samples;
    /// # Ok::<(), Box<dyn std::error::Error>>(())
    /// ```
    pub fn decode_float(
        &mut self,
        packet: &[u8],
        pcm: &mut [f32],
        fec: bool,
    ) -> Result<usize, OpusError> {
        let sub_packets = if packet.is_empty() || fec {
            vec![&[][..]; self.nb_streams]
        } else {
            split_multistream_packet(packet, self.nb_streams)?
        };
        let frame_size = self.expected_frame_size(&sub_packets, fec)?;
        let needed = frame_size * self.nb_channels;
        if pcm.len() < needed {
            return Err(OpusError::BufferTooSmall);
        }
        if frame_size == 0 {
            return Ok(0);
        }

        let mut stream_pcm = Vec::with_capacity(self.nb_streams);
        for (stream_idx, sub_packet) in sub_packets.iter().enumerate() {
            let channels = self.stream_channels(stream_idx);
            let mut buf = vec![0.0f32; frame_size * channels];
            let written = self.decoders[stream_idx].decode_float(sub_packet, &mut buf, fec)?;
            if written != frame_size {
                return Err(OpusError::InvalidPacket);
            }
            stream_pcm.push(buf);
        }

        for (channel_idx, &slot) in self.mapping.iter().enumerate() {
            if slot == 255 {
                zero_output_channel_f32(pcm, self.nb_channels, channel_idx, frame_size);
                continue;
            }

            let (stream_idx, source_channel) = self.slot_to_stream_channel(usize::from(slot));
            copy_output_channel_f32(
                pcm,
                self.nb_channels,
                channel_idx,
                &stream_pcm[stream_idx],
                self.stream_channels(stream_idx),
                source_channel,
                frame_size,
            );
        }

        Ok(frame_size)
    }

    /// Reset all internal decoders.
    ///
    /// Parameters: none.
    /// Returns: nothing.
    pub fn reset(&mut self) {
        for decoder in &mut self.decoders {
            decoder.reset();
        }
    }

    /// Compute the expected frame size for one multistream decode call.
    ///
    /// Parameters: per-stream `sub_packets` and FEC flag `fec`.
    /// Returns: decoded samples per output channel.
    fn expected_frame_size(&self, sub_packets: &[&[u8]], fec: bool) -> Result<usize, OpusError> {
        if fec || sub_packets.iter().all(|packet| packet.is_empty()) {
            return Ok(self
                .decoders
                .first()
                .map(|decoder| decoder.last_packet_duration)
                .unwrap_or(0));
        }

        let mut frame_size = None;
        for sub_packet in sub_packets.iter().filter(|packet| !packet.is_empty()) {
            let samples = packet::parse_packet(sub_packet)
                .map_err(OpusError::from)?
                .samples_per_channel(self.sample_rate);
            if let Some(expected) = frame_size {
                if expected != samples {
                    return Err(OpusError::InvalidPacket);
                }
            } else {
                frame_size = Some(samples);
            }
        }

        Ok(frame_size.unwrap_or(0))
    }

    /// Return the decoded channel count for one elementary stream.
    ///
    /// Parameters: `stream_idx` elementary stream index.
    /// Returns: `2` for coupled streams and `1` for mono streams.
    fn stream_channels(&self, stream_idx: usize) -> usize {
        if stream_idx < self.nb_coupled_streams {
            2
        } else {
            1
        }
    }

    /// Map one decoded channel slot to `(stream_idx, channel_idx)`.
    ///
    /// Parameters: flattened decoded `slot`.
    /// Returns: elementary stream index and channel index within that stream.
    fn slot_to_stream_channel(&self, slot: usize) -> (usize, usize) {
        let coupled_slots = 2 * self.nb_coupled_streams;
        if slot < coupled_slots {
            (slot / 2, slot % 2)
        } else {
            (self.nb_coupled_streams + (slot - coupled_slots), 0)
        }
    }
}

/// Split a multistream packet into per-stream sub-packets.
///
/// Parameters: full multistream `packet` and total `nb_streams`.
/// Returns: borrowed sub-packet slices in stream order.
fn split_multistream_packet(packet: &[u8], nb_streams: usize) -> Result<Vec<&[u8]>, OpusError> {
    if nb_streams == 0 {
        return Err(OpusError::InvalidArgument("nb_streams"));
    }
    if packet.is_empty() {
        return Ok(vec![packet; nb_streams]);
    }

    let mut out = Vec::with_capacity(nb_streams);
    let mut offset = 0usize;
    for _stream_idx in 0..nb_streams.saturating_sub(1) {
        let (packet_len, used) = parse_self_delimited_size(&packet[offset..])?;
        offset += used;
        if offset + packet_len > packet.len() {
            return Err(OpusError::InvalidPacket);
        }
        out.push(&packet[offset..offset + packet_len]);
        offset += packet_len;
    }

    if offset > packet.len() {
        return Err(OpusError::InvalidPacket);
    }
    out.push(&packet[offset..]);
    Ok(out)
}

/// Parse one self-delimited multistream sub-packet length.
///
/// Parameters: encoded length `data`.
/// Returns: tuple `(packet_len, bytes_used)`.
fn parse_self_delimited_size(data: &[u8]) -> Result<(usize, usize), OpusError> {
    if data.is_empty() {
        return Err(OpusError::InvalidPacket);
    }

    let first = usize::from(data[0]);
    if first < 252 {
        Ok((first, 1))
    } else {
        if data.len() < 2 {
            return Err(OpusError::InvalidPacket);
        }
        Ok((first + (4 * usize::from(data[1])), 2))
    }
}

/// Copy one decoded i16 channel into the interleaved multistream output buffer.
///
/// Parameters: mutable destination `pcm`, destination `dst_stride`, output
/// channel index `dst_channel`, source `src`, source `src_stride`, source
/// channel index `src_channel`, and `frame_size`.
/// Returns: nothing; destination channel is overwritten.
fn copy_output_channel_i16(
    pcm: &mut [i16],
    dst_stride: usize,
    dst_channel: usize,
    src: &[i16],
    src_stride: usize,
    src_channel: usize,
    frame_size: usize,
) {
    for sample_idx in 0..frame_size {
        pcm[sample_idx * dst_stride + dst_channel] = src[sample_idx * src_stride + src_channel];
    }
}

/// Zero one interleaved i16 output channel.
///
/// Parameters: mutable destination `pcm`, destination `dst_stride`, output
/// channel index `dst_channel`, and `frame_size`.
/// Returns: nothing; destination channel is zero-filled.
fn zero_output_channel_i16(
    pcm: &mut [i16],
    dst_stride: usize,
    dst_channel: usize,
    frame_size: usize,
) {
    for sample_idx in 0..frame_size {
        pcm[sample_idx * dst_stride + dst_channel] = 0;
    }
}

/// Copy one decoded f32 channel into the interleaved multistream output buffer.
///
/// Parameters: mutable destination `pcm`, destination `dst_stride`, output
/// channel index `dst_channel`, source `src`, source `src_stride`, source
/// channel index `src_channel`, and `frame_size`.
/// Returns: nothing; destination channel is overwritten.
fn copy_output_channel_f32(
    pcm: &mut [f32],
    dst_stride: usize,
    dst_channel: usize,
    src: &[f32],
    src_stride: usize,
    src_channel: usize,
    frame_size: usize,
) {
    for sample_idx in 0..frame_size {
        pcm[sample_idx * dst_stride + dst_channel] = src[sample_idx * src_stride + src_channel];
    }
}

/// Zero one interleaved f32 output channel.
///
/// Parameters: mutable destination `pcm`, destination `dst_stride`, output
/// channel index `dst_channel`, and `frame_size`.
/// Returns: nothing; destination channel is zero-filled.
fn zero_output_channel_f32(
    pcm: &mut [f32],
    dst_stride: usize,
    dst_channel: usize,
    frame_size: usize,
) {
    for sample_idx in 0..frame_size {
        pcm[sample_idx * dst_stride + dst_channel] = 0.0;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Split one two-stream packet with a one-byte length prefix.
    ///
    /// Parameters: none.
    /// Returns: nothing; panics on mismatch.
    #[test]
    fn split_two_stream_packet_with_short_prefix() {
        let packet = [3u8, 10, 11, 12, 20, 21];
        let sub_packets = split_multistream_packet(&packet, 2).unwrap();
        assert_eq!(sub_packets, vec![&[10, 11, 12][..], &[20, 21][..]]);
    }

    /// Split one empty packet into PLC sub-packets for all streams.
    ///
    /// Parameters: none.
    /// Returns: nothing; panics on mismatch.
    #[test]
    fn split_empty_packet_for_plc() {
        let sub_packets = split_multistream_packet(&[], 3).unwrap();
        assert_eq!(sub_packets, vec![&[][..], &[][..], &[][..]]);
    }
}
