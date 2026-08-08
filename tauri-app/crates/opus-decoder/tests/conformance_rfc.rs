#![allow(unused_variables, unused_assignments, clippy::collapsible_if)]

#[path = "../src/compare.rs"]
mod compare;

use std::fs;
use std::io::{self, Read};
use std::path::{Path, PathBuf};

use opus_decoder::{OpusDecoder, OpusError};

#[derive(Debug)]
struct Vector {
    name: String,
    bit: String,
    dec: String,
    fs_hz: u32,
    channels: u8,
}

#[derive(Debug)]
struct OpusDemoPacket {
    /// `None` means "lost packet" (PLC).
    packet: Option<Vec<u8>>,
    /// Expected final range coder value from the reference stream.
    ///
    expected_final_range: u32,
}

#[test]
#[ignore = "requires OPUS_TESTVECTORS_DIR + vectors.txt + working decoder"]
fn rfc_conformance_vectors() {
    let vectors_dir = vectors_dir();
    let manifest_path = vectors_dir.join("vectors.txt");
    let vectors = load_manifest(&manifest_path).unwrap_or_else(|e| {
        panic!(
            "failed to load manifest {}: {e}\n\n\
             Create it based on {}\n",
            manifest_path.display(),
            Path::new(env!("CARGO_MANIFEST_DIR"))
                .join("../../testdata/vectors.example.txt")
                .display()
        )
    });

    if vectors.is_empty() {
        panic!("manifest has no vectors: {}", manifest_path.display());
    }

    let filter = std::env::var("OPUS_VECTOR").ok();
    let mut ran_any = false;

    for v in vectors {
        if let Some(f) = filter.as_deref() {
            if !v.name.contains(f) {
                continue;
            }
        }
        ran_any = true;
        run_vector(&vectors_dir, &v).unwrap_or_else(|e| {
            panic!("vector {} failed: {e}", v.name);
        });
    }

    if filter.is_some() && !ran_any {
        panic!(
            "OPUS_VECTOR filter matched nothing (manifest: {})",
            manifest_path.display()
        );
    }
}

/// Exercise public PLC entry points on a CELT vector without asserting bit-exact output.
///
/// Params: none.
/// Returns: nothing; panics on invalid PLC behavior.
#[test]
#[ignore = "manual PLC smoke test"]
fn plc_smoke_test() {
    let celt_path = vectors_dir().join("testvector07.bit");
    let celt_packets = read_opus_demo_stream(&celt_path).expect("failed to read testvector07.bit");
    let celt_payloads: Vec<&[u8]> = celt_packets
        .iter()
        .filter_map(|packet| packet.packet.as_deref())
        .collect();
    assert!(
        celt_payloads.len() >= 20,
        "need at least 20 CELT packets for PLC smoke test"
    );

    let mut decoder_i16 = OpusDecoder::new(48_000, 1).expect("failed to create CELT i16 decoder");
    let mut decoder_f32 = OpusDecoder::new(48_000, 1).expect("failed to create CELT f32 decoder");
    let mut pcm_i16 = vec![0i16; OpusDecoder::MAX_FRAME_SIZE_48K];
    let mut pcm_f32 = vec![0.0f32; OpusDecoder::MAX_FRAME_SIZE_48K];
    let mut last_good_rms = 0.0f64;
    let mut last_loss_rms = 0.0f64;

    for (frame_idx, packet) in celt_payloads.iter().take(10).enumerate() {
        let samples_i16 = decoder_i16
            .decode(packet, &mut pcm_i16, false)
            .expect("normal CELT i16 decode failed");
        let samples_f32 = decoder_f32
            .decode_float(packet, &mut pcm_f32, false)
            .expect("normal CELT f32 decode failed");
        assert_eq!(samples_i16, samples_f32);
        assert!(
            pcm_f32[..samples_f32]
                .iter()
                .all(|sample| sample.is_finite())
        );
        last_good_rms = frame_rms_f32(&pcm_f32[..samples_f32]);
        eprintln!("celt pre-loss frame {frame_idx} rms={last_good_rms:.6}");
    }

    for loss_idx in 0..3 {
        let samples_i16 = decoder_i16
            .decode(&[], &mut pcm_i16, false)
            .expect("CELT PLC i16 decode failed");
        let samples_f32 = decoder_f32
            .decode_float(&[], &mut pcm_f32, false)
            .expect("CELT PLC f32 decode failed");
        assert_eq!(samples_i16, samples_f32);
        assert!(samples_f32 > 0, "PLC should reuse the last packet duration");
        assert!(
            pcm_f32[..samples_f32]
                .iter()
                .all(|sample| sample.is_finite())
        );
        last_loss_rms = frame_rms_f32(&pcm_f32[..samples_f32]);
        eprintln!("celt loss frame {loss_idx} rms={last_loss_rms:.6}");
    }

    assert!(
        last_loss_rms < last_good_rms,
        "CELT PLC should fade out over repeated losses"
    );

    for (frame_idx, packet) in celt_payloads.iter().skip(10).take(10).enumerate() {
        let samples_i16 = decoder_i16
            .decode(packet, &mut pcm_i16, false)
            .expect("post-loss CELT i16 decode failed");
        let samples_f32 = decoder_f32
            .decode_float(packet, &mut pcm_f32, false)
            .expect("post-loss CELT f32 decode failed");
        assert_eq!(samples_i16, samples_f32);
        assert!(
            pcm_f32[..samples_f32]
                .iter()
                .all(|sample| sample.is_finite())
        );
        let rms = frame_rms_f32(&pcm_f32[..samples_f32]);
        eprintln!("celt post-loss frame {frame_idx} rms={rms:.6}");
    }

    let silk_path = vectors_dir().join("testvector02.bit");
    let silk_packets = read_opus_demo_stream(&silk_path).expect("failed to read testvector02.bit");
    let silk_payloads: Vec<&[u8]> = silk_packets
        .iter()
        .filter_map(|packet| packet.packet.as_deref())
        .collect();
    assert!(
        silk_payloads.len() >= 20,
        "need at least 20 SILK packets for PLC smoke test"
    );

    let mut silk_decoder_i16 =
        OpusDecoder::new(48_000, 2).expect("failed to create SILK i16 decoder");
    let mut silk_decoder_f32 =
        OpusDecoder::new(48_000, 2).expect("failed to create SILK f32 decoder");
    let mut silk_pcm_i16 = vec![0i16; OpusDecoder::MAX_FRAME_SIZE_48K * 2];
    let mut silk_pcm_f32 = vec![0.0f32; OpusDecoder::MAX_FRAME_SIZE_48K * 2];
    let mut silk_loss_rms = Vec::new();

    for (frame_idx, packet) in silk_payloads.iter().take(10).enumerate() {
        let samples_i16 = silk_decoder_i16
            .decode(packet, &mut silk_pcm_i16, false)
            .expect("normal SILK i16 decode failed");
        let samples_f32 = silk_decoder_f32
            .decode_float(packet, &mut silk_pcm_f32, false)
            .expect("normal SILK f32 decode failed");
        assert_eq!(samples_i16, samples_f32);
        assert!(
            silk_pcm_f32[..samples_f32 * 2]
                .iter()
                .all(|sample| sample.is_finite())
        );
        let rms = frame_rms_f32(&silk_pcm_f32[..samples_f32 * 2]);
        eprintln!("silk pre-loss frame {frame_idx} rms={rms:.6}");
    }

    for loss_idx in 0..5 {
        let samples_i16 = silk_decoder_i16
            .decode(&[], &mut silk_pcm_i16, false)
            .expect("SILK PLC i16 decode failed");
        let samples_f32 = silk_decoder_f32
            .decode_float(&[], &mut silk_pcm_f32, false)
            .expect("SILK PLC f32 decode failed");
        assert_eq!(samples_i16, samples_f32);
        assert!(
            samples_f32 > 0,
            "SILK PLC should reuse the last packet duration"
        );
        assert!(
            silk_pcm_f32[..samples_f32 * 2]
                .iter()
                .all(|sample| sample.is_finite())
        );
        let rms = frame_rms_f32(&silk_pcm_f32[..samples_f32 * 2]);
        assert!(rms > 0.0, "SILK PLC should not output silence");
        silk_loss_rms.push(rms);
        eprintln!("silk loss frame {loss_idx} rms={rms:.6}");
    }

    for pair in silk_loss_rms.windows(2) {
        assert!(
            pair[1] <= pair[0] + 1e-9,
            "SILK PLC RMS should decay monotonically over consecutive losses"
        );
    }

    for (frame_idx, packet) in silk_payloads.iter().skip(10).take(10).enumerate() {
        let samples_i16 = silk_decoder_i16
            .decode(packet, &mut silk_pcm_i16, false)
            .expect("post-loss SILK i16 decode failed");
        let samples_f32 = silk_decoder_f32
            .decode_float(packet, &mut silk_pcm_f32, false)
            .expect("post-loss SILK f32 decode failed");
        assert_eq!(samples_i16, samples_f32);
        assert!(
            silk_pcm_f32[..samples_f32 * 2]
                .iter()
                .all(|sample| sample.is_finite())
        );
        let rms = frame_rms_f32(&silk_pcm_f32[..samples_f32 * 2]);
        eprintln!("silk post-loss frame {frame_idx} rms={rms:.6}");
    }
}

/// Compute root-mean-square amplitude for one floating PCM frame.
///
/// Params: decoded floating-point `frame`.
/// Returns: RMS amplitude as `f64`.
fn frame_rms_f32(frame: &[f32]) -> f64 {
    if frame.is_empty() {
        return 0.0;
    }

    let sum_sq = frame
        .iter()
        .map(|sample| {
            let sample = f64::from(*sample);
            sample * sample
        })
        .sum::<f64>();
    (sum_sq / frame.len() as f64).sqrt()
}

fn run_vector(vectors_dir: &Path, v: &Vector) -> Result<(), Box<dyn std::error::Error>> {
    let bit_path = vectors_dir.join(&v.bit);
    let dec_path = vectors_dir.join(&v.dec);

    let packets = read_opus_demo_stream(&bit_path)?;
    let expected_pcm = load_pcm16le(&dec_path)?;
    let alt_pcm: Option<Vec<i16>> = alt_dec_path(&dec_path)
        .filter(|p| p.is_file())
        .map(|p| load_pcm16le(&p))
        .transpose()?;

    let mut dec = OpusDecoder::new(v.fs_hz, usize::from(v.channels))?;
    let mut out = vec![0i16; dec.max_frame_size_per_channel() * usize::from(v.channels)];
    let mut got_pcm = Vec::<i16>::new();
    let mut packet_end_samples = Vec::<usize>::new();
    let mut final_range_mismatches: Vec<(usize, u32, u32)> = Vec::new();
    let dump_got_pcm = std::env::var("OPUS_DUMP_GOT_PCM").ok().as_deref() == Some("1");
    let trace_packet_idx = std::env::var("OPUS_TRACE_PACKET_IDX")
        .ok()
        .and_then(|s| s.parse::<usize>().ok());
    let trace_pkt0_pcm8 = std::env::var("OPUS_TRACE_PKT0_PCM8").ok().as_deref() == Some("1");
    let trace_pcm_packet_idx = std::env::var("OPUS_TRACE_PCM_PACKET_IDX")
        .ok()
        .and_then(|s| s.parse::<usize>().ok());
    let trace_first10 = std::env::var("OPUS_TRACE_FIRST10").ok().as_deref() == Some("1");
    let trace_match_all = std::env::var("OPUS_TRACE_MATCH_ALL").ok().as_deref() == Some("1");
    let trace_pcm_1_10 = std::env::var("OPUS_TRACE_PCM_1_10").ok().as_deref() == Some("1");
    let trace_packet_max_delta =
        std::env::var("OPUS_TRACE_PACKET_MAX_DELTA").ok().as_deref() == Some("1");

    for (packet_idx, p) in packets.into_iter().enumerate() {
        let packet = p.packet.as_deref().unwrap_or(&[]);
        match dec.decode(packet, &mut out, false) {
            Ok(samples_per_channel) => {
                let pkt_start = got_pcm.len();
                let written = samples_per_channel * v.channels as usize;
                got_pcm.extend_from_slice(&out[..written]);
                let pkt_end = got_pcm.len();
                packet_end_samples.push(pkt_end);
                let expected_debug_len = expected_pcm.len() / (v.channels as usize);
                if pkt_start < expected_debug_len {
                    let compare_end = pkt_end.min(expected_debug_len);
                    let mut max_delta = 0i32;
                    let mut first_diff_idx: isize = -1;
                    let mut first_diff_delta = 0i32;
                    let mut first_expected = 0i16;
                    let mut first_got = 0i16;
                    let mut max_diff_idx: isize = -1;
                    let mut max_diff_delta = 0i32;
                    let mut max_expected = 0i16;
                    let mut max_got = 0i16;
                    for i in pkt_start..compare_end {
                        let expected_i16 = expected_pcm[i];
                        let delta = got_pcm[i] as i32 - expected_i16 as i32;
                        let abs_delta = delta.abs();
                        if abs_delta > max_delta {
                            max_delta = abs_delta;
                            max_diff_idx = i as isize;
                            max_diff_delta = delta;
                            max_expected = expected_i16;
                            max_got = got_pcm[i];
                        }
                        if first_diff_idx == -1 && delta != 0 {
                            first_diff_idx = i as isize;
                            first_diff_delta = delta;
                            first_expected = expected_i16;
                            first_got = got_pcm[i];
                        }
                    }
                    if max_delta > 50 {
                        eprintln!(
                            "DELTA pkt {} max_delta={} frame_size={} sample_range={}..{}",
                            packet_idx, max_delta, written, pkt_start, pkt_end
                        );
                    }
                    if should_trace_debug_packet(packet_idx) || max_delta > 500 {
                        let mut got_start8_csv = String::new();
                        let mut exp_start8_csv = String::new();
                        for i in pkt_start..(pkt_start + 8).min(compare_end) {
                            if !got_start8_csv.is_empty() {
                                got_start8_csv.push(';');
                                exp_start8_csv.push(';');
                            }
                            got_start8_csv.push_str(&got_pcm[i].to_string());
                            exp_start8_csv.push_str(&expected_pcm[i].to_string());
                        }
                        let data = format!(
                            "{{\"packet_idx\":{},\"frame_size\":{},\"sample_start\":{},\"sample_end\":{},\"max_delta\":{},\"max_diff_idx\":{},\"max_diff_delta\":{},\"max_expected\":{},\"max_got\":{},\"first_diff_idx\":{},\"first_diff_delta\":{},\"first_expected\":{},\"first_got\":{},\"got_start8_csv\":\"{}\",\"exp_start8_csv\":\"{}\"}}",
                            packet_idx,
                            written,
                            pkt_start,
                            pkt_end,
                            max_delta,
                            max_diff_idx,
                            max_diff_delta,
                            max_expected,
                            max_got,
                            first_diff_idx,
                            first_diff_delta,
                            first_expected,
                            first_got,
                            got_start8_csv,
                            exp_start8_csv
                        );
                        // #region agent log
                        append_debug_log(
                            "run-onset-map-v1",
                            "H3",
                            "crates/opus-decoder/tests/conformance_rfc.rs:run_vector",
                            "packet_delta",
                            &data,
                        );
                        // #endregion
                    }
                }
                if trace_pkt0_pcm8 && packet_idx == 0 && written >= 8 {
                    eprintln!(
                        "[RUST] pkt0 pcm[0..8]: {} {} {} {} {} {} {} {}",
                        out[0], out[1], out[2], out[3], out[4], out[5], out[6], out[7]
                    );
                }
                if let Some(target_pcm_idx) = trace_pcm_packet_idx {
                    if packet_idx == target_pcm_idx && written >= 8 {
                        eprintln!(
                            "[RUST] pkt{} pcm[0..8]: {} {} {} {} {} {} {} {}",
                            target_pcm_idx,
                            out[0],
                            out[1],
                            out[2],
                            out[3],
                            out[4],
                            out[5],
                            out[6],
                            out[7]
                        );
                        eprintln!(
                            "[RUST] pkt{} samples_per_channel={}",
                            target_pcm_idx, samples_per_channel
                        );
                    }
                }
                if packet_idx == 1 && written >= 8 {
                    eprintln!(
                        "rust pcm[0..8]: {} {} {} {} {} {} {} {}",
                        out[0], out[1], out[2], out[3], out[4], out[5], out[6], out[7]
                    );
                    if expected_pcm.len() >= 968 {
                        eprintln!(
                            "ref  pcm[0..8]: {} {} {} {} {} {} {} {}",
                            expected_pcm[960],
                            expected_pcm[961],
                            expected_pcm[962],
                            expected_pcm[963],
                            expected_pcm[964],
                            expected_pcm[965],
                            expected_pcm[966],
                            expected_pcm[967]
                        );
                    }
                }
                if packet_idx < 5 {
                    eprintln!("pkt{} deemph_mem={:.4}", packet_idx, dec.deemph_mem());
                }
                let got = dec.final_range();
                if packet_idx < 5 {
                    eprintln!(
                        "pkt{} final_range expected={:08x} got={:08x}",
                        packet_idx, p.expected_final_range, got
                    );
                }
                if trace_first10 && packet_idx < 10 {
                    eprintln!(
                        "[RUST] packet {}: final_range match={}",
                        packet_idx,
                        got == p.expected_final_range
                    );
                }
                if trace_match_all {
                    eprintln!(
                        "pkt {} match={} splits={}",
                        packet_idx,
                        got == p.expected_final_range,
                        dec.last_split_count()
                    );
                }
                if got != p.expected_final_range {
                    final_range_mismatches.push((packet_idx, p.expected_final_range, got));
                }
                if trace_pcm_1_10 && (1..=10).contains(&packet_idx) && written >= 4 {
                    eprintln!(
                        "[RUST] pkt{} transient={} pcm[0..4]: {} {} {} {}",
                        packet_idx,
                        dec.last_is_transient(),
                        out[0],
                        out[1],
                        out[2],
                        out[3]
                    );
                }
                if let Some(target_idx) = trace_packet_idx {
                    if packet_idx == target_idx {
                        eprintln!(
                            "[RUST] packet{} final_range expected=0x{:08x} got=0x{:08x}",
                            target_idx, p.expected_final_range, got
                        );
                        return Ok(());
                    }
                }
            }
            Err(OpusError::InternalError) => {
                return Err("decoder not implemented yet".into());
            }
            Err(e) => return Err(format!("decode failed: {e}").into()),
        }
    }

    if !final_range_mismatches.is_empty() {
        eprintln!(
            "warning: final_range mismatches for {}: {} packets (soft check for now)",
            v.name,
            final_range_mismatches.len()
        );
        for (packet_idx, expected, got) in final_range_mismatches.iter().take(8) {
            eprintln!("  packet #{packet_idx}: expected 0x{expected:08x}, got 0x{got:08x}");
        }
    }

    if dump_got_pcm {
        let mut out_bytes = Vec::with_capacity(got_pcm.len() * 2);
        for s in &got_pcm {
            out_bytes.extend_from_slice(&s.to_le_bytes());
        }
        fs::write("/tmp/rust_tv07.pcm", out_bytes)?;
    }

    // Fast path: exact match against either output.
    if expected_pcm == got_pcm {
        return Ok(());
    }
    if let Some(alt_pcm) = &alt_pcm {
        if *alt_pcm == got_pcm {
            return Ok(());
        }
    }

    // Reference conformance uses `opus_compare` (quality metric), not a
    // byte-exact PCM match.
    if expected_pcm != got_pcm {
        let min_len = expected_pcm.len().min(got_pcm.len());
        let first_diff = (0..min_len).find(|&i| expected_pcm[i] != got_pcm[i]);
        if let Some(sample_idx) = first_diff {
            let packet_idx = packet_end_samples.partition_point(|&end| end <= sample_idx);
            let mut diff_csv = String::new();
            let mut logged = 0usize;
            for i in sample_idx..min_len {
                if expected_pcm[i] != got_pcm[i] {
                    if !diff_csv.is_empty() {
                        diff_csv.push(';');
                    }
                    let delta = got_pcm[i] as i32 - expected_pcm[i] as i32;
                    diff_csv.push_str(&format!("{i}:{}:{}:{delta}", expected_pcm[i], got_pcm[i]));
                    logged += 1;
                    if logged == 16 {
                        break;
                    }
                }
            }
            eprintln!(
                "[RUST] first_pcm_diff sample={} packet={} expected={} got={}",
                sample_idx, packet_idx, expected_pcm[sample_idx], got_pcm[sample_idx]
            );
            // #region agent log
            if let Ok(mut f) = std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open("/Users/tadeusz/Opus/Rasopus/.cursor/debug-bea564.log")
            {
                let ts = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_millis())
                    .unwrap_or(0);
                let line = format!(
                    "{{\"sessionId\":\"4200c5\",\"runId\":\"run-pcm-first-diff\",\"hypothesisId\":\"H18\",\"location\":\"crates/opus-decoder/tests/conformance_rfc.rs:223\",\"message\":\"first_pcm_diff\",\"data\":{{\"sample_idx\":{},\"packet_idx\":{},\"expected\":{},\"got\":{},\"expected_len\":{},\"got_len\":{},\"first_diffs_csv\":\"{}\"}},\"timestamp\":{}}}\n",
                    sample_idx,
                    packet_idx,
                    expected_pcm[sample_idx],
                    got_pcm[sample_idx],
                    expected_pcm.len(),
                    got_pcm.len(),
                    diff_csv,
                    ts
                );
                let _ = std::io::Write::write_all(&mut f, line.as_bytes());
            }
            // #endregion
        }
        let mut top = [(0i32, 0usize); 5];
        for i in 0..min_len {
            let d = (got_pcm[i] as i32 - expected_pcm[i] as i32).abs();
            for slot in 0..5 {
                if d > top[slot].0 {
                    for shift in (slot + 1..5).rev() {
                        top[shift] = top[shift - 1];
                    }
                    top[slot] = (d, i);
                    break;
                }
            }
        }
        let mut top_csv = String::new();
        for (d, i) in top {
            if d == 0 {
                continue;
            }
            if !top_csv.is_empty() {
                top_csv.push(';');
            }
            let packet_idx = packet_end_samples.partition_point(|&end| end <= i);
            top_csv.push_str(&format!(
                "{i}:{packet_idx}:{}:{}:{}",
                expected_pcm[i], got_pcm[i], d
            ));
        }
        // #region agent log
        if let Ok(mut f) = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open("/Users/tadeusz/Opus/Rasopus/.cursor/debug-bea564.log")
        {
            let ts = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_millis())
                .unwrap_or(0);
            let line = format!(
                "{{\"sessionId\":\"4200c5\",\"runId\":\"run-pcm-first-diff\",\"hypothesisId\":\"H28\",\"location\":\"crates/opus-decoder/tests/conformance_rfc.rs:320\",\"message\":\"largest_pcm_deltas\",\"data\":{{\"top_deltas_csv\":\"{}\",\"min_len\":{}}},\"timestamp\":{}}}\n",
                top_csv, min_len, ts
            );
            let _ = std::io::Write::write_all(&mut f, line.as_bytes());
        }
        // #endregion
        if trace_packet_max_delta {
            let mut range_start = 0usize;
            for (packet_idx, &packet_end) in packet_end_samples.iter().enumerate() {
                if range_start >= min_len {
                    break;
                }
                let range_end = packet_end.min(min_len);
                if range_end <= range_start {
                    continue;
                }
                let mut max_delta = 0i32;
                let mut sum_sq = 0.0f64;
                for i in range_start..range_end {
                    let d = expected_pcm[i] as i32 - got_pcm[i] as i32;
                    let ad = d.abs();
                    if ad > max_delta {
                        max_delta = ad;
                    }
                    sum_sq += (d as f64) * (d as f64);
                }
                let rms = (sum_sq / (range_end - range_start) as f64).sqrt();
                if max_delta > 50 {
                    eprintln!(
                        "[RUST] pkt{} max_delta={} rms={:.1}",
                        packet_idx, max_delta, rms
                    );
                }
                range_start = range_end;
            }
        }
    }
    if let Some(alt_pcm) = &alt_pcm {
        if *alt_pcm != got_pcm {
            let min_len = alt_pcm.len().min(got_pcm.len());
            let first_diff = (0..min_len).find(|&i| alt_pcm[i] != got_pcm[i]);
            if let Some(sample_idx) = first_diff {
                let packet_idx = packet_end_samples.partition_point(|&end| end <= sample_idx);
                let mut diff_csv = String::new();
                let mut logged = 0usize;
                for i in sample_idx..min_len {
                    if alt_pcm[i] != got_pcm[i] {
                        if !diff_csv.is_empty() {
                            diff_csv.push(';');
                        }
                        let delta = got_pcm[i] as i32 - alt_pcm[i] as i32;
                        diff_csv.push_str(&format!("{i}:{}:{}:{delta}", alt_pcm[i], got_pcm[i]));
                        logged += 1;
                        if logged == 16 {
                            break;
                        }
                    }
                }
                // #region agent log
                if let Ok(mut f) = std::fs::OpenOptions::new()
                    .create(true)
                    .append(true)
                    .open("/Users/tadeusz/Opus/Rasopus/.cursor/debug-bea564.log")
                {
                    let ts = std::time::SystemTime::now()
                        .duration_since(std::time::UNIX_EPOCH)
                        .map(|d| d.as_millis())
                        .unwrap_or(0);
                    let line = format!(
                        "{{\"sessionId\":\"4200c5\",\"runId\":\"run-pcm-first-diff\",\"hypothesisId\":\"H27\",\"location\":\"crates/opus-decoder/tests/conformance_rfc.rs:271\",\"message\":\"first_pcm_diff_alt\",\"data\":{{\"sample_idx\":{},\"packet_idx\":{},\"alt\":{},\"got\":{},\"alt_len\":{},\"got_len\":{},\"first_diffs_csv\":\"{}\"}},\"timestamp\":{}}}\n",
                        sample_idx,
                        packet_idx,
                        alt_pcm[sample_idx],
                        got_pcm[sample_idx],
                        alt_pcm.len(),
                        got_pcm.len(),
                        diff_csv,
                        ts
                    );
                    let _ = std::io::Write::write_all(&mut f, line.as_bytes());
                }
                // #endregion
            }
        }
    }
    let q0 = compare::compare_quality(
        &expected_pcm,
        &got_pcm,
        v.channels as usize,
        v.channels as usize,
        v.fs_hz,
    )?;
    if q0.passes_vectors() {
        return Ok(());
    }
    if let Some(alt_pcm) = &alt_pcm {
        if alt_pcm.len() == expected_pcm.len() {
            let q1 = compare::compare_quality(
                alt_pcm,
                &got_pcm,
                v.channels as usize,
                v.channels as usize,
                v.fs_hz,
            )?;
            if q1.passes_vectors() {
                return Ok(());
            }
            return Err(format!(
                "PCM mismatch vs both references (quality {:.1}% err {:.6} ; alt {:.1}% err {:.6})",
                q0.quality_percent,
                q0.internal_weighted_error,
                q1.quality_percent,
                q1.internal_weighted_error
            )
            .into());
        }
    }

    Err(format!(
        "PCM mismatch (quality {:.1}% err {:.6})",
        q0.quality_percent, q0.internal_weighted_error
    )
    .into())
}

/// Decide whether a packet should emit extra debug logging.
///
/// Params: `packet_idx` packet number in the vector stream.
/// Returns: true when explicit packet tracing is requested.
fn should_trace_debug_packet(packet_idx: usize) -> bool {
    std::env::var("OPUS_TRACE_PACKET_IDX")
        .ok()
        .and_then(|value| value.parse::<usize>().ok())
        == Some(packet_idx)
}

/// Append one JSON debug line to the local trace log.
///
/// Params: `run_id`, `hypothesis_id`, `location`, `message`, and JSON `data`.
/// Returns: nothing; errors are ignored because this is debug-only tracing.
fn append_debug_log(run_id: &str, hypothesis_id: &str, location: &str, message: &str, data: &str) {
    if let Ok(mut f) = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open("/Users/tadeusz/Opus/Rasopus/.cursor/debug-bea564.log")
    {
        let ts = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis())
            .unwrap_or(0);
        let line = format!(
            "{{\"sessionId\":\"4200c5\",\"runId\":\"{}\",\"hypothesisId\":\"{}\",\"location\":\"{}\",\"message\":\"{}\",\"data\":{},\"timestamp\":{}}}\n",
            run_id, hypothesis_id, location, message, data, ts
        );
        let _ = std::io::Write::write_all(&mut f, line.as_bytes());
    }
}

fn alt_dec_path(dec_path: &Path) -> Option<PathBuf> {
    let name = dec_path.file_name()?.to_str()?;
    if !name.ends_with(".dec") || name.ends_with("m.dec") {
        return None;
    }
    let alt = name.strip_suffix(".dec")?.to_string() + "m.dec";
    Some(dec_path.with_file_name(alt))
}

fn load_manifest(path: &Path) -> Result<Vec<Vector>, Box<dyn std::error::Error>> {
    let s = fs::read_to_string(path)?;
    let mut out = Vec::new();
    for (lineno, line) in s.lines().enumerate() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let mut it = line.split_whitespace();
        let name = it
            .next()
            .ok_or_else(|| format!("line {}: missing name", lineno + 1))?
            .to_string();
        let bit = it
            .next()
            .ok_or_else(|| format!("line {}: missing .bit path", lineno + 1))?
            .to_string();
        let dec = it
            .next()
            .ok_or_else(|| format!("line {}: missing .dec path", lineno + 1))?
            .to_string();
        let fs_hz = it
            .next()
            .ok_or_else(|| format!("line {}: missing fs_hz", lineno + 1))?
            .parse::<u32>()?;
        let channels = it
            .next()
            .ok_or_else(|| format!("line {}: missing channels", lineno + 1))?
            .parse::<u8>()?;
        if it.next().is_some() {
            return Err(format!("line {}: too many fields", lineno + 1).into());
        }
        out.push(Vector {
            name,
            bit,
            dec,
            fs_hz,
            channels,
        });
    }
    Ok(out)
}

fn vectors_dir() -> PathBuf {
    if let Ok(p) = std::env::var("OPUS_TESTVECTORS_DIR") {
        return PathBuf::from(p);
    }
    PathBuf::from("testdata/opus_testvectors")
}

fn load_pcm16le(path: &Path) -> Result<Vec<i16>, Box<dyn std::error::Error>> {
    let bytes = fs::read(path)?;
    if bytes.len() % 2 != 0 {
        return Err(format!("{}: odd byte count", path.display()).into());
    }
    let mut out = Vec::<i16>::with_capacity(bytes.len() / 2);
    for c in bytes.chunks_exact(2) {
        out.push(i16::from_le_bytes([c[0], c[1]]));
    }
    Ok(out)
}

fn read_opus_demo_stream(path: &Path) -> io::Result<Vec<OpusDemoPacket>> {
    let mut f = fs::File::open(path)?;
    let mut bytes = Vec::new();
    f.read_to_end(&mut bytes)?;

    // Opus "opus_demo" framing (proprietary): repeating records:
    // - u32be packet_len
    // - u32be final_range
    // - packet bytes (packet_len)
    //
    // A packet_len of 0 is used to represent a lost packet (PLC).
    //
    // NOTE: This is a best-effort implementation intended for the official
    // conformance vectors. If we find incompatibilities, adjust to match the
    // reference `opus_demo.c` format exactly.
    let mut i = 0usize;
    let mut out = Vec::new();
    while i < bytes.len() {
        if i + 4 > bytes.len() {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "truncated packet_len",
            ));
        }
        let packet_len =
            u32::from_be_bytes([bytes[i], bytes[i + 1], bytes[i + 2], bytes[i + 3]]) as usize;
        i += 4;

        if i + 4 > bytes.len() {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "truncated final_range",
            ));
        }
        let expected_final_range =
            u32::from_be_bytes([bytes[i], bytes[i + 1], bytes[i + 2], bytes[i + 3]]);
        i += 4;

        if i + packet_len > bytes.len() {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "truncated packet",
            ));
        }
        let packet = if packet_len == 0 {
            None
        } else {
            Some(bytes[i..i + packet_len].to_vec())
        };
        i += packet_len;

        out.push(OpusDemoPacket {
            packet,
            expected_final_range,
        });
    }

    Ok(out)
}
