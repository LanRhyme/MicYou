//! Public API for the pure Rust Opus decoder port.
//!
//! The crate keeps the CELT and SILK internals close to libopus while exposing
//! stable packet-to-PCM decoding entry points.

#![forbid(unsafe_code)]
#![allow(
    clippy::erasing_op,
    clippy::identity_op,
    clippy::precedence,
    clippy::int_plus_one
)]

macro_rules! debug_trace {
    ($($arg:tt)*) => {};
}

mod celt;
pub(crate) mod compare;
mod entropy;
mod error;
mod multistream;
mod packet;
mod silk;
use crate::entropy::EcDec;
use core::sync::atomic::{AtomicUsize, Ordering};

pub(crate) use error::Error;
pub use multistream::OpusMultistreamDecoder;

static TRACE_DECODE_PACKET_IDX: AtomicUsize = AtomicUsize::new(0);

/// High-level single-stream Opus decoder.
///
/// This wrapper exposes the stable public API for packet-to-PCM decoding while
/// keeping CELT and SILK state management internal to the crate.
pub struct OpusDecoder {
    decoder: Decoder,
    float_scratch: Vec<i16>,
    loss_count: u32,
    last_packet_duration: usize,
}

impl OpusDecoder {
    /// Maximum decoded frame size per channel at 48 kHz.
    pub const MAX_FRAME_SIZE_48K: usize = Decoder::MAX_FRAME_SIZE_48K;

    /// Create a new decoder.
    ///
    /// `sample_rate` must be `8000`, `12000`, `16000`, `24000`, or `48000`,
    /// and `channels` must be `1` or `2`.
    ///
    /// # Examples
    ///
    /// ```rust
    /// use opus_decoder::OpusDecoder;
    ///
    /// let decoder = OpusDecoder::new(48_000, 2)?;
    /// # let _ = decoder;
    /// # Ok::<(), opus_decoder::OpusError>(())
    /// ```
    pub fn new(sample_rate: u32, channels: usize) -> Result<Self, OpusError> {
        let channels =
            u8::try_from(channels).map_err(|_| OpusError::InvalidArgument("channels"))?;
        let decoder = Decoder::new(sample_rate, channels).map_err(OpusError::from)?;

        Ok(Self {
            decoder,
            float_scratch: Vec::new(),
            loss_count: 0,
            last_packet_duration: 0,
        })
    }

    /// Return the maximum decoded frame size per channel for this output rate.
    pub fn max_frame_size_per_channel(&self) -> usize {
        self.decoder.max_frame_size_per_channel()
    }

    /// Mirror internal PLC bookkeeping into the public wrapper.
    ///
    /// Parameters: none.
    /// Returns: nothing.
    fn sync_state_from_decoder(&mut self) {
        self.loss_count = self.decoder.loss_count;
        self.last_packet_duration = self.decoder.last_packet_duration;
    }

    /// Decode a packet into 16-bit PCM samples (interleaved if stereo).
    ///
    /// Empty `packet` input triggers packet loss concealment using the previous
    /// decoder state. Returns the number of decoded samples per channel.
    /// - `fec`: reserved for future in-band FEC support. Currently treated as
    ///   packet loss concealment (PLC) when `true`. Pass `false` for normal decode.
    ///
    /// # Examples
    ///
    /// ```rust,no_run
    /// use opus_decoder::OpusDecoder;
    ///
    /// let mut decoder = OpusDecoder::new(48_000, 2)?;
    /// let packet = std::fs::read("frame.opus")?;
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
        let samples_per_channel = if packet.is_empty() || fec {
            self.decoder.decode(None, pcm).map_err(OpusError::from)?
        } else {
            self.decoder
                .decode(Some(packet), pcm)
                .map_err(OpusError::from)?
        };
        self.sync_state_from_decoder();
        Ok(samples_per_channel)
    }

    /// Decode a packet into f32 PCM samples (interleaved if stereo).
    ///
    /// Empty `packet` input triggers packet loss concealment using the previous
    /// decoder state. Returns the number of decoded samples per channel.
    /// - `fec`: reserved for future in-band FEC support. Currently treated as
    ///   packet loss concealment (PLC) when `true`. Pass `false` for normal decode.
    ///
    /// # Examples
    ///
    /// ```rust,no_run
    /// use opus_decoder::OpusDecoder;
    ///
    /// let mut decoder = OpusDecoder::new(48_000, 1)?;
    /// let packet = std::fs::read("frame.opus")?;
    /// let mut pcm = vec![0.0f32; 960];
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
        let samples_per_channel_hint = if packet.is_empty() || fec {
            self.decoder.last_packet_duration
        } else {
            packet::parse_packet(packet)
                .map_err(OpusError::from)?
                .samples_per_channel(self.decoder.fs_hz())
        };
        let needed = samples_per_channel_hint * self.decoder.channels() as usize;
        if pcm.len() < needed {
            return Err(OpusError::BufferTooSmall);
        }

        if self.float_scratch.len() < needed {
            self.float_scratch.resize(needed, 0);
        }

        let samples_per_channel = self
            .decoder
            .decode(
                if packet.is_empty() || fec {
                    None
                } else {
                    Some(packet)
                },
                &mut self.float_scratch[..needed],
            )
            .map_err(OpusError::from)?;
        self.sync_state_from_decoder();
        let written = samples_per_channel * self.decoder.channels() as usize;
        for (dst, src) in pcm.iter_mut().zip(self.float_scratch[..written].iter()) {
            *dst = f32::from(*src) / 32768.0;
        }

        Ok(samples_per_channel)
    }

    /// Reset decoder state (e.g. after packet loss or seek).
    ///
    /// Parameters: none.
    /// Returns: nothing.
    pub fn reset(&mut self) {
        self.decoder.reset();
        self.loss_count = 0;
        self.last_packet_duration = 0;
    }

    /// Return the last range-coder final state observed by the decoder.
    pub fn final_range(&self) -> u32 {
        self.decoder.final_range()
    }

    /// Return the last CELT recursive split count.
    #[doc(hidden)]
    pub fn last_split_count(&self) -> usize {
        self.decoder.last_split_count()
    }

    /// Return the current deemphasis memory for channel 0.
    #[doc(hidden)]
    pub fn deemph_mem(&self) -> f32 {
        self.decoder.deemph_mem()
    }

    /// Return whether the last CELT frame used the transient path.
    #[doc(hidden)]
    pub fn last_is_transient(&self) -> bool {
        self.decoder.last_is_transient()
    }

    /// Return whether the last decoded packet carried SILK redundancy.
    #[doc(hidden)]
    pub fn last_had_redundancy(&self) -> bool {
        self.decoder.last_had_redundancy()
    }

    /// Return whether the last decoded SILK redundancy was CELT-to-SILK.
    #[doc(hidden)]
    pub fn last_celt_to_silk(&self) -> bool {
        self.decoder.last_celt_to_silk()
    }
}

/// Errors returned by the public Opus decoding API.
#[derive(Debug, thiserror::Error)]
pub enum OpusError {
    /// The provided Opus packet is malformed or internally inconsistent.
    #[error("invalid packet")]
    InvalidPacket,
    /// The decoder hit an internal unsupported or unexpected state.
    #[error("internal error")]
    InternalError,
    /// The output PCM buffer is too small for the decoded frame.
    #[error("buffer too small")]
    BufferTooSmall,
    /// One of the public API arguments is invalid.
    #[error("invalid argument: {0}")]
    InvalidArgument(&'static str),
}

impl From<Error> for OpusError {
    fn from(value: Error) -> Self {
        match value {
            Error::InvalidSampleRate(_) => Self::InvalidArgument("sample_rate"),
            Error::InvalidChannels(_) => Self::InvalidArgument("channels"),
            Error::PacketTooLarge { .. } | Error::BadPacket => Self::InvalidPacket,
            Error::OutputTooSmall { .. } => Self::BufferTooSmall,
            Error::NotImplemented => Self::InternalError,
        }
    }
}

#[derive(Debug, Clone)]
pub(crate) struct Decoder {
    fs_hz: u32,
    channels: u8,
    celt: celt::CeltDecoder,
    silk: silk::SilkDecoder,
    last_packet_split_count: usize,
    last_final_range: u32,
    last_had_redundancy: bool,
    last_celt_to_silk: bool,
    prev_mode: Option<OpusMode>,
    prev_redundancy: bool,
    loss_count: u32,
    last_packet_duration: usize,
    last_output: Vec<i16>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum OpusMode {
    SilkOnly,
    Hybrid,
    CeltOnly,
}

impl Decoder {
    pub(crate) const MAX_FRAME_SIZE_48K: usize = 5760; // 120 ms @ 48 kHz

    pub(crate) fn new(fs_hz: u32, channels: u8) -> Result<Self, Error> {
        if !matches!(fs_hz, 8000 | 12000 | 16000 | 24000 | 48000) {
            return Err(Error::InvalidSampleRate(fs_hz));
        }
        if !matches!(channels, 1 | 2) {
            return Err(Error::InvalidChannels(channels));
        }
        Ok(Self {
            fs_hz,
            channels,
            celt: celt::CeltDecoder::new(fs_hz, channels),
            silk: silk::SilkDecoder::new(fs_hz, channels),
            last_packet_split_count: 0,
            last_final_range: 0,
            last_had_redundancy: false,
            last_celt_to_silk: false,
            prev_mode: None,
            prev_redundancy: false,
            loss_count: 0,
            last_packet_duration: 0,
            last_output: Vec::new(),
        })
    }

    pub(crate) fn fs_hz(&self) -> u32 {
        self.fs_hz
    }

    pub(crate) fn channels(&self) -> u8 {
        self.channels
    }

    pub(crate) fn max_frame_size_per_channel(&self) -> usize {
        // Opus internally decodes 48 kHz and can output other Fs by deterministic
        // downsampling/decimation (libopus behavior).
        match self.fs_hz {
            8000 => 960,
            12000 => 1440,
            16000 => 1920,
            24000 => 2880,
            48000 => Self::MAX_FRAME_SIZE_48K,
            _ => 0, // guarded in new()
        }
    }

    /// Conceal a lost non-CELT frame by fading the previous PCM.
    ///
    /// Params: per-channel `frame_size`, consecutive `loss_count`, and mutable `out`.
    /// Returns: nothing; `out` receives interleaved concealed PCM.
    fn conceal_with_fade(&self, frame_size: usize, loss_count: u32, out: &mut [i16]) {
        let channels = self.channels as usize;
        let needed = frame_size * channels;
        if self.last_output.len() < needed {
            out[..needed].fill(0);
            return;
        }

        let fade = 0.9f32.powi((loss_count.min(10) + 1) as i32);
        for (dst, src) in out[..needed]
            .iter_mut()
            .zip(self.last_output[..needed].iter())
        {
            let sample = f32::from(*src) * fade;
            *dst = sample.round().clamp(i16::MIN as f32, i16::MAX as f32) as i16;
        }
    }

    /// Persist the most recent decoded interleaved PCM frame.
    ///
    /// Params: decoded `out` buffer and `samples_per_channel` written into it.
    /// Returns: nothing; the decoder keeps a copy for future PLC fallback.
    fn store_last_output(&mut self, out: &[i16], samples_per_channel: usize) {
        let written = samples_per_channel * self.channels as usize;
        self.last_output.clear();
        self.last_output.extend_from_slice(&out[..written]);
    }

    /// Decode a lost packet using CELT PLC or a safe fade fallback.
    ///
    /// Params: mutable interleaved `out` buffer.
    /// Returns: concealed sample count per channel.
    fn decode_lost_packet(&mut self, out: &mut [i16]) -> Result<usize, Error> {
        let samples_per_channel = self.last_packet_duration;
        if samples_per_channel == 0 {
            self.last_final_range = 0;
            return Ok(0);
        }

        let needed = samples_per_channel * self.channels as usize;
        if out.len() < needed {
            return Err(Error::OutputTooSmall {
                needed,
                got: out.len(),
            });
        }

        out[..needed].fill(0);
        match self.prev_mode {
            Some(OpusMode::CeltOnly) => {
                let concealed = self
                    .celt
                    .decode_lost(samples_per_channel, self.channels as usize);
                for (dst, src) in out[..needed].iter_mut().zip(concealed.iter()) {
                    *dst = src.round().clamp(i16::MIN as f32, i16::MAX as f32) as i16;
                }
            }
            Some(OpusMode::SilkOnly | OpusMode::Hybrid) => {
                self.silk
                    .decode_lost(samples_per_channel, out, self.loss_count)?;
            }
            _ => {
                self.conceal_with_fade(samples_per_channel, self.loss_count, out);
            }
        }

        self.loss_count = self.loss_count.saturating_add(1);
        self.last_packet_split_count = 0;
        self.last_final_range = 0;
        self.last_had_redundancy = false;
        self.last_celt_to_silk = false;
        self.prev_redundancy = false;
        self.store_last_output(out, samples_per_channel);
        Ok(samples_per_channel)
    }

    pub(crate) fn reset(&mut self) {
        // Placeholder. The real implementation will reset SILK/CELT state.
        self.celt.reset();
        self.silk.reset();
        self.last_packet_split_count = 0;
        self.last_final_range = 0;
        self.last_had_redundancy = false;
        self.last_celt_to_silk = false;
        self.prev_mode = None;
        self.prev_redundancy = false;
        self.loss_count = 0;
        self.last_packet_duration = 0;
        self.last_output.clear();
    }

    /// Return range coder final state for conformance comparison.
    pub(crate) fn final_range(&self) -> u32 {
        self.last_final_range
    }

    /// Return recursive split count from last decoded frame.
    ///
    /// This is a debug metric used for CELT split-path diagnostics.
    pub(crate) fn last_split_count(&self) -> usize {
        self.last_packet_split_count
    }

    /// Return deemphasis memory for channel 0 (debug metric).
    ///
    /// This is used for CELT synthesis diagnostics in mono vectors.
    pub(crate) fn deemph_mem(&self) -> f32 {
        self.celt.deemph_mem(0)
    }

    /// Return whether the last CELT frame was transient.
    ///
    /// This is a compatibility helper for the local conformance harness.
    pub(crate) fn last_is_transient(&self) -> bool {
        false
    }

    /// Return whether the last decoded packet carried SILK redundancy.
    ///
    /// This follows the top-level transition bookkeeping used by the decoder.
    pub(crate) fn last_had_redundancy(&self) -> bool {
        self.last_had_redundancy
    }

    /// Return whether the last decoded SILK redundancy was CELT-to-SILK.
    ///
    /// This is a debug helper for transition tracing in the conformance harness.
    pub(crate) fn last_celt_to_silk(&self) -> bool {
        self.last_celt_to_silk
    }

    /// Decode one Opus packet to interleaved i16 PCM.
    ///
    /// - `packet=None` triggers PLC (packet loss concealment).
    /// - `out` must be large enough for the maximum frame size (120 ms).
    /// - Returns the number of samples per channel written.
    pub(crate) fn decode(
        &mut self,
        packet: Option<&[u8]>,
        out: &mut [i16],
    ) -> Result<usize, Error> {
        let packet_idx = TRACE_DECODE_PACKET_IDX.fetch_add(1, Ordering::SeqCst);
        let (toc, samples_per_channel_needed) = match packet {
            Some(packet) => {
                let pp = packet::parse_packet(packet)?;
                (Some(pp.toc), pp.samples_per_channel(self.fs_hz))
            }
            None => (None, self.last_packet_duration),
        };

        let needed = samples_per_channel_needed * self.channels as usize;
        if out.len() < needed {
            return Err(Error::OutputTooSmall {
                needed,
                got: out.len(),
            });
        }

        let Some(toc) = toc else {
            return self.decode_lost_packet(out);
        };

        self.celt.reset_loss_count();
        let config = (toc >> 3) & 0x1f;
        let mode = match config {
            0..=11 => OpusMode::SilkOnly,
            12..=15 => OpusMode::Hybrid,
            16..=31 => OpusMode::CeltOnly,
            _ => unreachable!(),
        };
        // Parse again to get per-frame slices.
        let pp = packet::parse_packet(packet.unwrap())?;
        self.last_packet_split_count = 0;
        self.last_final_range = 0;

        let transition = self.prev_mode.is_some()
            && ((mode == OpusMode::CeltOnly
                && self.prev_mode != Some(OpusMode::CeltOnly)
                && !self.prev_redundancy)
                || (mode != OpusMode::CeltOnly && self.prev_mode == Some(OpusMode::CeltOnly)));
        let transition_samples = (self.fs_hz as usize) / 200;
        let transition_overlap = (self.fs_hz as usize) / 400;
        let channels = self.channels as usize;
        let celt_transition = if transition && mode == OpusMode::CeltOnly {
            vec![0i16; transition_samples * channels]
        } else {
            Vec::new()
        };
        let mut apply_celt_transition = transition && mode == OpusMode::CeltOnly;
        let mut reset_silk = transition && self.prev_mode == Some(OpusMode::CeltOnly);
        let mut had_redundancy = false;
        let mut last_celt_to_silk = false;
        let mut written_per_channel = 0usize;
        for &frame in pp.frames().iter() {
            let frame_samples_48k = pp.samples_per_frame_48k;
            let out_frame = &mut out[written_per_channel * self.channels as usize..];
            match mode {
                OpusMode::CeltOnly => {
                    if apply_celt_transition {
                        self.celt.reset();
                    }
                    // CELT frame sizes are specified in 48 kHz samples; this is enough
                    // to drive the CELT-side LM selection.
                    let mut ec = EcDec::new(frame);
                    let celt_frame = self.celt.decode_frame_with_ec(
                        frame,
                        &mut ec,
                        frame_samples_48k,
                        config,
                        pp.packet_channels,
                        packet_idx,
                        out_frame,
                        false,
                    )?;
                    if apply_celt_transition {
                        apply_transition_fade_i16(
                            &celt_transition,
                            &mut out_frame[..celt_frame.samples_per_channel * channels],
                            transition_overlap.min(celt_frame.samples_per_channel / 2),
                            channels,
                            self.celt.window(),
                            self.fs_hz,
                        );
                        apply_celt_transition = false;
                    }
                    self.last_packet_split_count += self.celt.last_split_count();
                    self.last_final_range = self.celt.final_range();
                    written_per_channel += celt_frame.samples_per_channel;
                }
                OpusMode::SilkOnly => {
                    if reset_silk {
                        self.silk.reset();
                        reset_silk = false;
                    }
                    let packet_frame = frame;
                    let silk_frame = self.silk.decode_frame(
                        packet_frame,
                        frame_samples_48k,
                        config,
                        pp.packet_channels,
                        packet_idx,
                        out_frame,
                    )?;
                    last_celt_to_silk = silk_frame.celt_to_silk;
                    let mut redundancy_rng = 0u32;
                    if silk_frame.consumed_redundancy {
                        let redundancy_data =
                            &packet_frame[packet_frame.len() - silk_frame.redundancy_bytes..];
                        let redundancy_frame_size_48k = 240usize;
                        let redundancy_samples = (self.fs_hz as usize) / 200;
                        let redundancy_end_band = silk_redundancy_end_band(config);
                        let mut redundancy_out =
                            vec![0i16; redundancy_samples * self.channels as usize];
                        if !silk_frame.celt_to_silk {
                            self.celt.reset();
                        }
                        self.celt.set_start_band(0);
                        self.celt.set_end_band(redundancy_end_band);
                        let redundancy_frame = self.celt.decode_frame(
                            redundancy_data,
                            redundancy_frame_size_48k,
                            config,
                            pp.packet_channels,
                            packet_idx,
                            &mut redundancy_out,
                        )?;
                        self.celt.clear_end_band();
                        redundancy_rng = self.celt.final_range();
                        if !silk_frame.celt_to_silk {
                            let overlap = (self.fs_hz as usize) / 400;
                            let channels = self.channels as usize;
                            let silk_tail_start =
                                (silk_frame.samples_per_channel - overlap) * channels;
                            let silk_tail_end = silk_frame.samples_per_channel * channels;
                            let redundancy_start = overlap * channels;
                            let redundancy_end = redundancy_start + overlap * channels;
                            let silk_tail = out_frame[silk_tail_start..silk_tail_end].to_vec();
                            smooth_fade_i16(
                                &silk_tail,
                                &redundancy_out[redundancy_start..redundancy_end],
                                &mut out_frame[silk_tail_start..silk_tail_end],
                                overlap,
                                channels,
                                self.celt.window(),
                                self.fs_hz,
                            );
                        } else {
                            let overlap = (self.fs_hz as usize) / 400;
                            let channels = self.channels as usize;
                            let frame_len = silk_frame.samples_per_channel * channels;
                            apply_transition_fade_i16(
                                &redundancy_out,
                                &mut out_frame[..frame_len],
                                overlap.min(redundancy_frame.samples_per_channel / 2),
                                channels,
                                self.celt.window(),
                                self.fs_hz,
                            );
                        }
                    }
                    had_redundancy = silk_frame.consumed_redundancy && !silk_frame.celt_to_silk;
                    self.last_final_range = self.silk.final_range() ^ redundancy_rng;
                    written_per_channel += silk_frame.samples_per_channel;
                }
                OpusMode::Hybrid => {
                    let mut ec = EcDec::new(frame);
                    let silk_frame = self.silk.decode_frame_with_ec(
                        frame,
                        &mut ec,
                        frame_samples_48k,
                        config,
                        pp.packet_channels,
                        true,
                        packet_idx,
                        out_frame,
                    )?;
                    let redundancy = if ec.tell() + 17 + 20 <= (frame.len() as i32) * 8 {
                        ec.dec_bit_logp(12)
                    } else {
                        false
                    };
                    let mut celt_to_silk = false;
                    let mut redundancy_bytes = 0usize;
                    if redundancy {
                        celt_to_silk = ec.dec_bit_logp(1);
                        redundancy_bytes = ec.dec_uint(256) as usize + 2;
                        ec.shrink_storage(redundancy_bytes);
                    }
                    let mut celt_to_silk_audio = Vec::new();
                    let mut celt_to_silk_samples = 0usize;
                    let mut redundant_rng = 0u32;
                    let apply_celt_to_silk_audio = redundancy
                        && celt_to_silk
                        && (self.prev_mode != Some(OpusMode::SilkOnly) || self.prev_redundancy);
                    let reset_main_celt = self.prev_mode.is_some()
                        && self.prev_mode != Some(mode)
                        && !self.prev_redundancy;
                    if redundancy && celt_to_silk {
                        let redundancy_samples = (self.fs_hz as usize) / 200;
                        let redundancy_data = &frame[frame.len() - redundancy_bytes..];
                        self.celt.set_start_band(0);
                        celt_to_silk_audio = vec![0i16; redundancy_samples * channels];
                        let redundancy_frame = self.celt.decode_frame(
                            redundancy_data,
                            240,
                            config,
                            pp.packet_channels,
                            packet_idx,
                            &mut celt_to_silk_audio,
                        )?;
                        self.last_packet_split_count += self.celt.last_split_count();
                        celt_to_silk_samples = redundancy_frame.samples_per_channel;
                        redundant_rng = self.celt.final_range();
                    }
                    if reset_main_celt {
                        self.celt.reset();
                    }
                    self.celt.set_start_band(17);
                    let celt_frame = match self.celt.decode_frame_with_ec(
                        frame,
                        &mut ec,
                        frame_samples_48k,
                        config,
                        pp.packet_channels,
                        packet_idx,
                        out_frame,
                        true,
                    ) {
                        Ok(frame) => frame,
                        Err(err) => {
                            self.celt.set_start_band(0);
                            return Err(err);
                        }
                    };
                    self.celt.set_start_band(0);
                    debug_assert_eq!(
                        silk_frame.samples_per_channel,
                        celt_frame.samples_per_channel
                    );
                    self.last_packet_split_count += self.celt.last_split_count();
                    let main_celt_rng = self.celt.final_range();
                    self.last_final_range = main_celt_rng;
                    if redundancy && !celt_to_silk {
                        let channels = self.channels as usize;
                        let redundancy_samples = (self.fs_hz as usize) / 200;
                        let overlap = (self.fs_hz as usize) / 400;
                        let frame_len = celt_frame.samples_per_channel * channels;
                        let redundancy_data = &frame[frame.len() - redundancy_bytes..];
                        let mut redundancy_out = vec![0i16; redundancy_samples * channels];
                        self.celt.reset();
                        self.celt.set_start_band(0);
                        let redundancy_frame = self.celt.decode_frame(
                            redundancy_data,
                            240,
                            config,
                            pp.packet_channels,
                            packet_idx,
                            &mut redundancy_out,
                        )?;
                        self.last_packet_split_count += self.celt.last_split_count();
                        let redundant_rng = self.celt.final_range();
                        let fade_len = overlap * channels;
                        let tail_start = frame_len.saturating_sub(fade_len);
                        let tail_end = tail_start + fade_len;
                        let redundancy_start = fade_len;
                        let redundancy_end = redundancy_start + fade_len;
                        if tail_end <= out_frame.len()
                            && redundancy_end <= redundancy_out.len()
                            && redundancy_frame.samples_per_channel == redundancy_samples
                        {
                            let celt_tail = out_frame[tail_start..tail_end].to_vec();
                            smooth_fade_i16(
                                &celt_tail,
                                &redundancy_out[redundancy_start..redundancy_end],
                                &mut out_frame[tail_start..tail_end],
                                overlap,
                                channels,
                                self.celt.window(),
                                self.fs_hz,
                            );
                        }
                        self.last_final_range ^= redundant_rng;
                    }
                    if redundancy && celt_to_silk {
                        if apply_celt_to_silk_audio {
                            let overlap = (self.fs_hz as usize) / 400;
                            let frame_len = celt_frame.samples_per_channel * channels;
                            apply_transition_fade_i16(
                                &celt_to_silk_audio,
                                &mut out_frame[..frame_len],
                                overlap.min(celt_to_silk_samples / 2),
                                channels,
                                self.celt.window(),
                                self.fs_hz,
                            );
                        }
                        self.last_final_range ^= redundant_rng;
                    }
                    last_celt_to_silk = celt_to_silk;
                    had_redundancy = redundancy && !celt_to_silk;
                    written_per_channel += silk_frame.samples_per_channel;
                }
            }
        }

        self.prev_mode = Some(mode);
        self.last_had_redundancy = had_redundancy;
        self.last_celt_to_silk = last_celt_to_silk;
        self.prev_redundancy = had_redundancy;
        self.loss_count = 0;
        self.last_packet_duration = written_per_channel;
        self.store_last_output(out, written_per_channel);
        Ok(written_per_channel)
    }
}

/// Crossfade SILK PCM with redundant CELT PCM using the CELT overlap window.
///
/// Params: previous `in1`, incoming `in2`, mutable `out`, overlap length,
/// interleaved `channels`, CELT `window`, and output sampling rate `fs_hz`.
/// Returns: nothing; `out` is updated in-place.
fn smooth_fade_i16(
    in1: &[i16],
    in2: &[i16],
    out: &mut [i16],
    overlap: usize,
    channels: usize,
    window: &[f32],
    fs_hz: u32,
) {
    let inc = (48_000 / fs_hz) as usize;
    for c in 0..channels {
        for i in 0..overlap {
            let w = window[i * inc] * window[i * inc];
            let idx = i * channels + c;
            let mixed = w * in2[idx] as f32 + (1.0 - w) * in1[idx] as f32;
            out[idx] = mixed.round().clamp(i16::MIN as f32, i16::MAX as f32) as i16;
        }
    }
}

/// Apply the SILK-to-CELT transition prefix and crossfade.
///
/// Params: previous-mode `transition` PCM, mutable decoded `pcm`, fade length
/// `overlap`, interleaved `channels`, CELT `window`, and output rate `fs_hz`.
/// Returns: nothing; `pcm` is updated in-place.
fn apply_transition_fade_i16(
    transition: &[i16],
    pcm: &mut [i16],
    overlap: usize,
    channels: usize,
    window: &[f32],
    fs_hz: u32,
) {
    if overlap == 0 || channels == 0 {
        return;
    }

    let prefix_len = overlap * channels;
    let copy_len = prefix_len.min(transition.len()).min(pcm.len());
    pcm[..copy_len].copy_from_slice(&transition[..copy_len]);

    let fade_available = (transition.len().saturating_sub(prefix_len))
        .min(pcm.len().saturating_sub(prefix_len))
        / channels;
    if fade_available == 0 {
        return;
    }

    let fade_samples = fade_available.min(overlap);
    let fade_len = fade_samples * channels;
    let fade_start = prefix_len;
    let fade_end = fade_start + fade_len;
    let incoming = pcm[fade_start..fade_end].to_vec();
    smooth_fade_i16(
        &transition[fade_start..fade_end],
        &incoming,
        &mut pcm[fade_start..fade_end],
        fade_samples,
        channels,
        window,
        fs_hz,
    );
}

/// Map SILK packet config to CELT redundancy end band.
///
/// Params: Opus TOC `config`.
/// Returns: exclusive CELT end band matching libopus packet bandwidth.
fn silk_redundancy_end_band(config: u8) -> usize {
    match config {
        0..=3 => 13,
        4..=11 => 17,
        12..=13 => 19,
        14..=15 => 21,
        _ => 21,
    }
}
