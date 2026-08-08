mod common;

use common::{build_multistream_packet, load_packets};
use opus_decoder::{OpusDecoder, OpusMultistreamDecoder};

/// Verify the call-recording use case with two independent mono streams.
///
/// Parameters: none.
/// Returns: nothing; panics on invalid mapping or decode behavior.
#[ignore = "requires OPUS_TESTVECTORS_DIR"]
#[test]
fn multistream_stereo_call_recording() {
    let packets = load_packets("testvector07").unwrap();
    let packet = build_multistream_packet(&[&packets[0], &packets[1]]);

    let mut decoder = OpusMultistreamDecoder::new(48_000, 2, 2, 0, &[0, 1]).unwrap();
    let mut pcm = vec![0i16; OpusDecoder::MAX_FRAME_SIZE_48K * 2];
    let samples = decoder.decode(&packet, &mut pcm, false).unwrap();

    assert!(samples > 0);
    assert!(
        pcm[..samples * 2]
            .chunks_exact(2)
            .any(|frame| frame[0] != frame[1])
    );
}

/// Verify that mapping value `255` silences the corresponding output channel.
///
/// Parameters: none.
/// Returns: nothing; panics on invalid output.
#[ignore = "requires OPUS_TESTVECTORS_DIR"]
#[test]
fn multistream_silence_channel() {
    let packets = load_packets("testvector07").unwrap();
    let packet = &packets[0];

    let mut decoder = OpusMultistreamDecoder::new(48_000, 2, 1, 0, &[0, 255]).unwrap();
    let mut pcm = vec![0i16; OpusDecoder::MAX_FRAME_SIZE_48K * 2];
    let samples = decoder.decode(packet, &mut pcm, false).unwrap();

    assert!(samples > 0);
    assert!(
        pcm[..samples * 2]
            .chunks_exact(2)
            .all(|frame| frame[1] == 0)
    );
}

/// Reject invalid multistream topology arguments.
///
/// Parameters: none.
/// Returns: nothing; panics on unexpected success.
#[test]
fn multistream_invalid_args() {
    assert!(OpusMultistreamDecoder::new(48_000, 2, 1, 2, &[0, 1]).is_err());
    assert!(OpusMultistreamDecoder::new(48_000, 2, 1, 0, &[0]).is_err());
    assert!(OpusMultistreamDecoder::new(48_000, 2, 1, 0, &[0, 2]).is_err());
}
