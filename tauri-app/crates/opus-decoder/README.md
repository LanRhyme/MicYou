# opus-decoder

[![Crates.io](https://img.shields.io/crates/v/opus-decoder.svg)](https://crates.io/crates/opus-decoder)
[![Docs.rs](https://docs.rs/opus-decoder/badge.svg)](https://docs.rs/opus-decoder)
[![CI](https://github.com/tadeuszwojcik/rasopus/actions/workflows/ci.yml/badge.svg)](https://github.com/tadeuszwojcik/rasopus/actions)
[![RFC 8251](https://img.shields.io/badge/RFC%208251-12%2F12%20conformant-brightgreen)](https://www.rfc-editor.org/rfc/rfc8251)
[![License](https://img.shields.io/crates/l/opus-decoder.svg)](LICENSE-APACHE)

A pure-Rust [Opus](https://opus-codec.org/) decoder with no unsafe code and no
FFI. Passes all 12 RFC 8251 conformance test vectors.

## Features

- **RFC 8251 conformant** — all 12 official test vectors pass `opus_compare`
- **All Opus modes** — CELT, SILK, and Hybrid (SWB/FB)
- **All output sample rates** — 8, 12, 16, 24, 48 kHz
- **Mono and stereo** — including up/downmix
- **Packet Loss Concealment (PLC)** — CELT fade-out, SILK pitch extrapolation
- **Multistream** — `OpusMultistreamDecoder` for surround (5.1, 7.1) and call recording
- **`#![forbid(unsafe_code)]`** — throughout the correctness implementation
- **No FFI, no C dependencies** — pure Rust

## Quick Start

Add to `Cargo.toml`:

```toml
[dependencies]
opus-decoder = "0.1"
```

### Decode a single packet

```rust
use opus_decoder::{OpusDecoder, OpusError};

fn main() -> Result<(), OpusError> {
    let mut decoder = OpusDecoder::new(48_000, 2)?; // 48 kHz, stereo
    
    let packet: &[u8] = /* your Opus packet bytes */;
    let mut pcm = vec![0i16; 960 * 2]; // 20 ms at 48 kHz, stereo interleaved
    
    let samples = decoder.decode(packet, &mut pcm, false)?;
    println!("Decoded {} samples per channel", samples);
    Ok(())
}
```

### Packet Loss Concealment

```rust
// Pass an empty slice to trigger PLC
let mut pcm = vec![0i16; 960 * 2];
decoder.decode(&[], &mut pcm, false)?; // generates concealment frame
```

### Float output

```rust
let mut pcm_f32 = vec![0f32; 960 * 2];
decoder.decode_float(packet, &mut pcm_f32, false)?;
```

### Multistream — call recording (2 speakers → stereo)

```rust
use opus_decoder::OpusMultistreamDecoder;

// Two independent mono streams → stereo output
// Left channel: caller (Stream A), Right channel: callee (Stream B)
let mut decoder = OpusMultistreamDecoder::new(
    48_000,  // sample rate
    2,       // output channels
    2,       // nb_streams
    0,       // nb_coupled_streams (both mono)
    &[0, 1], // mapping: stream 0 → L, stream 1 → R
)?;

let mut pcm = vec![0i16; 960 * 2];
decoder.decode(&multistream_packet, &mut pcm, false)?;
```

### Multistream — 5.1 surround

```rust
let mut decoder = OpusMultistreamDecoder::new(
    48_000,
    6,             // 5.1 output channels
    4,             // 4 streams total
    2,             // 2 coupled (stereo) streams
    &[0, 1, 2, 3, 4, 255], // mapping (255 = silence)
)?;
```

## API Reference

### `OpusDecoder`

```rust
impl OpusDecoder {
    pub fn new(sample_rate: u32, channels: usize) -> Result<Self, OpusError>;
    pub fn decode(&mut self, packet: &[u8], pcm: &mut [i16], fec: bool) -> Result<usize, OpusError>;
    pub fn decode_float(&mut self, packet: &[u8], pcm: &mut [f32], fec: bool) -> Result<usize, OpusError>;
    pub fn reset(&mut self);
}
```

`sample_rate` must be one of: `8000`, `12000`, `16000`, `24000`, `48000`.  
`channels` must be `1` or `2`.  
`decode` returns the number of samples decoded **per channel**.  
An empty `packet` or `fec: true` triggers Packet Loss Concealment (PLC).

### `OpusMultistreamDecoder`

```rust
impl OpusMultistreamDecoder {
    pub fn new(sample_rate: u32, nb_channels: usize, nb_streams: usize,
               nb_coupled_streams: usize, mapping: &[u8]) -> Result<Self, OpusError>;
    pub fn decode(&mut self, packet: &[u8], pcm: &mut [i16], fec: bool) -> Result<usize, OpusError>;
    pub fn decode_float(&mut self, packet: &[u8], pcm: &mut [f32], fec: bool) -> Result<usize, OpusError>;
    pub fn reset(&mut self);
}
```

`mapping[i] = 255` silences output channel `i`.  
The first `nb_coupled_streams` streams are stereo-coupled; the rest are mono.

### `OpusError`

```rust
pub enum OpusError {
    InvalidPacket,
    InternalError,
    BufferTooSmall,
    InvalidArgument(&'static str),
}
```

## Conformance

All 12 RFC 8251 test vectors pass the `opus_compare` quality metric:

| Vector | Description | Result |
|--------|-------------|--------|
| tv01 | CELT stereo FB | ✅ PASS |
| tv02 | SILK NB 8 kHz mono | ✅ PASS |
| tv03 | SILK MB 12 kHz mono | ✅ PASS |
| tv04 | SILK WB 16 kHz mono | ✅ PASS |
| tv05 | Hybrid SWB transitions | ✅ PASS |
| tv06 | Hybrid FB transitions | ✅ PASS |
| tv07 | CELT mono FB | ✅ PASS |
| tv08 | SILK stereo + CELT NB stereo | ✅ PASS |
| tv09 | SILK→CELT transitions | ✅ PASS |
| tv10 | CeltOnly + Hybrid mix | ✅ PASS |
| tv11 | CELT stereo FB | ✅ PASS |
| tv12 | SILK bandwidth transitions | ✅ PASS |

Run conformance tests locally:

```bash
# Download test vectors
cargo xtask fetch-vectors

# Run all 12 vectors
for v in 01 02 03 04 05 06 07 08 09 10 11 12; do
  OPUS_TESTVECTORS_DIR="testdata/opus_testvectors" \
  OPUS_VECTOR=testvector${v} \
  cargo test --release -p opus-decoder rfc_conformance -- --ignored --nocapture \
    2>&1 | grep -E "PASS|FAIL"
done
```

## Minimum Supported Rust Version (MSRV)

**Rust 1.85** — enforced in CI.

Uses: `std`, `thiserror`. No nightly features.

## Out of Scope

The following are intentionally **not** implemented in this crate:

- **Encoder** — decoder only
- **Container parsing** — Ogg/WebM demux belongs in a separate crate
- **SIMD optimizations** — correctness first; performance is a future phase

## Implementation Notes

This crate is a pure Rust port of libopus based on RFC 6716 and RFC 8251,
implemented function-by-function and verified against the reference libopus implementation.
The floating-point decode path is used throughout (matching libopus float semantics).

Internal architecture:

```
lib.rs                  — public API, mode routing, mode transitions
├── celt/               — CELT decoder (MDCT, PVQ, band decode, anti-collapse, PLC)
├── silk/               — SILK decoder (NLSF→LPC, excitation, IIR/FIR resampler, PLC)
├── multistream.rs      — OpusMultistreamDecoder
├── entropy.rs          — Range coder (EcDec)
└── compare.rs          — opus_compare quality metric port
```

## Known Limitations

- Hybrid mode PLC uses SILK-only concealment (no CELT highband PLC)
- No fuzz harness yet (planned)

## License

Licensed under either of:

- [Apache License, Version 2.0](LICENSE-APACHE)
- [MIT License](LICENSE-MIT)

at your option.

This crate ports algorithms from [libopus](https://gitlab.xiph.org/xiph/opus),
which is licensed under the [BSD 3-Clause License](https://gitlab.xiph.org/xiph/opus/-/blob/master/COPYING).

## Contributing

Conformance is the top priority. Any change must pass all 12 RFC 8251 test vectors
before merging. Run:

```bash
cargo test --release -p opus-decoder
cargo clippy -p opus-decoder --all-targets -- -D warnings
```
