# Workspace Restructure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restructure the Tauri app's Rust backend from a single monolithic crate into a Cargo workspace with fine-grained crates, and add Cargo feature flags for platform-specific and optional-functional modules.

**Architecture:** Split into 3 workspace crates: micyou-protocol (protobuf types + constants), micyou-audio (audio engine + DSP), and micyou-app (Tauri binary with commands, servers, platform code). Feature flags control platform modules (vbcable, adb) and optional heavy dependencies (noise-suppression, web-server).

**Tech Stack:** Cargo workspaces, Cargo features, prost (protobuf), cpal (audio), ort (ONNX ML), axum (web server), Tauri 2

---

## Target Workspace Structure

```
tauri-app/
├── Cargo.toml                         # [workspace] root
├── src-tauri/
│   ├── Cargo.toml                     # micyou-app (Tauri binary crate)
│   ├── build.rs                       # tauri_build only
│   ├── src/
│   │   ├── main.rs                    # Entry point
│   │   ├── lib.rs                     # Tauri setup, ServerState, commands
│   │   ├── commands/
│   │   │   ├── mod.rs
│   │   │   └── about.rs
│   │   ├── tray.rs
│   │   ├── tcp_server.rs
│   │   ├── udp_server.rs
│   │   ├── web_server.rs              # #[cfg(feature = "web-server")]
│   │   ├── network.rs
│   │   ├── adb_manager.rs             # always compiled
│   │   └── vbcable.rs                 # feature gates zip dep
│   ├── tauri.conf.json
│   ├── resources/
│   ├── capabilities/
│   ├── icons/
│   └── gen/
├── crates/
│   ├── micyou-protocol/
│   │   ├── Cargo.toml
│   │   ├── build.rs                   # prost-build protobuf compilation
│   │   ├── proto/
│   │   │   └── network.proto
│   │   └── src/
│   │       └── lib.rs                 # Generated protobuf types + protocol constants
│   └── micyou-audio/
│       ├── Cargo.toml
│       └── src/
│           ├── lib.rs                 # Re-exports + init_onnx_runtime()
│           ├── engine.rs              # AudioOutputManager + RubatoResampler
│           └── dsp.rs                 # DspProcessor + AudioDspSettings
```

## Feature Flags Summary

### micyou-audio
| Feature | Default | Dependencies Gated |
|---------|---------|-------------------|
| dsp | yes | nnnoiseless, ort, ort-tract, rustfft |
| noise-suppression | no | ort, ort-tract (inside dsp) |

### micyou-app
| Feature | Default | Code/Dependencies Gated |
|---------|---------|------------------------|
| vbcable | yes | zip crate (install fn gated with stub fallback) |
| adb | yes | no extra deps (feature for future extensibility) |
| web-server | yes | web_server.rs module, axum, rcgen, rustls, tokio-rustls, rustls-pemfile |

## Dependency Graph

```
micyou-protocol (no internal deps, only prost+bytes+serde)
    ↑
    ├── micyou-audio (depends on protocol; cpal, rubato, optional ort/nnnoiseless)
    │
    └── micyou-app (depends on protocol + audio; tauri, tokio, mdns-sd, optional axum)
```

---

## Task 1: Delete Empty Plugin Stubs

**Files:** Delete src-tauri/plugins/ (entire directory)

- [ ] Step 1: Verify plugins are empty (find should return no .rs/.kt/.java/.ts files)
- [ ] Step 2: rm -rf src-tauri/plugins
- [ ] Step 3: cargo check in src-tauri (should still pass)
- [ ] Step 4: Commit

---

## Task 2: Create Workspace Root Cargo.toml

**Files:** Create Cargo.toml at tauri-app/ root

```toml
[workspace]
resolver = "2"
members = [
    "src-tauri",
    "crates/micyou-protocol",
    "crates/micyou-audio",
]

[workspace.package]
version = "2.0.0"
authors = ["LanRhyme"]
edition = "2021"
```

- [ ] Step 1: Create the file
- [ ] Step 2: Commit

---

## Task 3: Create micyou-protocol Crate

**Files:**
- Create: crates/micyou-protocol/Cargo.toml
- Create: crates/micyou-protocol/build.rs
- Create: crates/micyou-protocol/src/lib.rs
- Move: src-tauri/proto/network.proto -> crates/micyou-protocol/proto/network.proto

### Cargo.toml
```toml
[package]
name = "micyou-protocol"
version.workspace = true
authors.workspace = true
edition.workspace = true

[dependencies]
prost = "0.14.3"
bytes = "1.11.1"
serde = { version = "1", features = ["derive"] }

[build-dependencies]
prost-build = "0.14.3"
protoc-bin-vendored = "3.2.0"
```

### build.rs
```rust
fn main() -> Result<(), Box<dyn std::error::Error>> {
    std::env::set_var("PROTOC", protoc_bin_vendored::protoc_bin_path().unwrap());
    prost_build::compile_protos(&["proto/network.proto"], &["proto/"])?;
    Ok(())
}
```

### src/lib.rs
```rust
pub mod micyou {
    include!(concat!(env!("OUT_DIR"), "/micyou.rs"));
}

pub const PACKET_MAGIC: i32 = 0x4D696359;
pub const UDP_PACKET_MAGIC: i32 = 0x4D696355;
pub const PORT: u16 = 9123;
pub const UDP_PORT: u16 = 9124;
pub const MDNS_SERVICE_TYPE: &str = "_micyou._tcp.local.";
pub const MDNS_WEB_SERVICE_TYPE: &str = "_micyou-web._tcp.local.";
pub const HANDSHAKE_CLIENT_STR: &[u8] = b"MicYouCheck1";
pub const HANDSHAKE_SERVER_STR: &[u8] = b"MicYouCheck2";
```

- [ ] Step 1: Create directory structure
- [ ] Step 2: Create Cargo.toml
- [ ] Step 3: Move proto file
- [ ] Step 4: Create build.rs
- [ ] Step 5: Create src/lib.rs
- [ ] Step 6: cargo check -p micyou-protocol
- [ ] Step 7: Commit

---

## Task 4: Create micyou-audio Crate

**Files:**
- Create: crates/micyou-audio/Cargo.toml
- Create: crates/micyou-audio/src/lib.rs
- Copy: src-tauri/src/audio_engine.rs -> crates/micyou-audio/src/engine.rs
- Copy: src-tauri/src/dsp.rs -> crates/micyou-audio/src/dsp.rs

### Cargo.toml
```toml
[package]
name = "micyou-audio"
version.workspace = true
authors.workspace = true
edition.workspace = true

[features]
default = ["dsp"]
dsp = ["dep:nnnoiseless", "dep:ort", "dep:ort-tract", "dep:rustfft"]
noise-suppression = ["dsp", "dep:ort", "dep:ort-tract"]

[dependencies]
micyou-protocol = { path = "../micyou-protocol" }
serde = { version = "1", features = ["derive"] }
log = "0.4.32"
cpal = "0.15.3"
rubato = "3.0.0"
ringbuf = "0.3.3"

nnnoiseless = { version = "0.5.2", optional = true }
ort = { version = "=2.0.0-rc.12", default-features = false, features = ["std", "ndarray", "api-17", "alternative-backend"], optional = true }
ort-tract = { version = "0.3.0+0.22", optional = true }
rustfft = { version = "6.4.1", optional = true }
```

### src/lib.rs
```rust
pub mod engine;
#[cfg(feature = "dsp")]
pub mod dsp;

pub use engine::{AudioOutputManager, RubatoResampler};
#[cfg(feature = "dsp")]
pub use dsp::{AudioDspSettings, DspProcessor, EqualizerConfig};

pub fn init_onnx_runtime() {
    #[cfg(feature = "noise-suppression")]
    ort::set_api(ort_tract::api());
}
```

### engine.rs changes
- Copy from audio_engine.rs, no import changes needed (self-contained with cpal/rubato/ringbuf)

### dsp.rs changes (COMPLEX - 1112 lines)
- Gate UlunasProcessor with #[cfg(feature = "noise-suppression")]
- Gate RnnoiseProcessor with #[cfg(feature = "dsp")]
- Gate Equalizer with #[cfg(feature = "dsp")]
- DspProcessor struct gets conditional fields:
  - #[cfg(feature = "noise-suppression")] ulunas: Option<UlunasProcessor>
  - #[cfg(feature = "dsp")] rnnoise: Option<RnnoiseProcessor>
  - #[cfg(feature = "dsp")] equalizer: Option<Equalizer>
- DspProcessor::new() and process() use conditional compilation blocks
- AudioDspSettings and EqualizerConfig always available (pure serde structs)

- [ ] Step 1: Create directory structure
- [ ] Step 2: Create Cargo.toml
- [ ] Step 3: Copy audio_engine.rs as engine.rs
- [ ] Step 4: Verify engine.rs has no crate:: imports
- [ ] Step 5: Copy dsp.rs
- [ ] Step 6: Add feature gates to dsp.rs (most complex step)
- [ ] Step 7: Create src/lib.rs
- [ ] Step 8: cargo check -p micyou-audio && cargo check -p micyou-audio --no-default-features
- [ ] Step 9: Commit

---

## Task 5: Update micyou-app Cargo.toml

**Files:** Modify src-tauri/Cargo.toml

Replace entire file with workspace-aware version:
- Package name: micyou-app (was micyou)
- Add [features] section: default = ["vbcable", "adb", "web-server"]
- Add workspace crate deps: micyou-protocol, micyou-audio
- Remove prost-build from [build-dependencies]
- Make zip, axum, rcgen, rustls, tokio-rustls, rustls-pemfile optional
- Keep tauri, tokio, mdns-sd, serde, reqwest, etc. as required deps

- [ ] Step 1: Rewrite Cargo.toml
- [ ] Step 2: Verify TOML parses (cargo check will fail on missing modules, that's ok)
- [ ] Step 3: Commit

---

## Task 6: Delete Old Source Files

**Files:**
- Delete: src-tauri/src/audio_engine.rs
- Delete: src-tauri/src/dsp.rs
- Delete: src-tauri/src/protocol.rs

Note: stats.rs and jitter_buffer.rs STAY in src-tauri/src/ (no heavy deps, tightly coupled to server code)

- [ ] Step 1: Delete the 3 files
- [ ] Step 2: Commit

---

## Task 7: Update lib.rs

**Files:** Modify src-tauri/src/lib.rs

Key changes:
1. Remove pub mod protocol/audio_engine/dsp
2. Add #[cfg(feature = "web-server")] to pub mod web_server
3. Replace crate::protocol:: -> micyou_protocol::
4. Replace crate::audio_engine:: -> micyou_audio::engine::
5. Replace crate::dsp:: -> micyou_audio::dsp::
6. Feature-gate ServerState fields: web_server, web_mdns
7. Feature-gate web code in start_server/stop_server
8. Feature-gate get_web_status command
9. Feature-gate web fields in ServerState initialization in run()

- [ ] Step 1: Update module declarations
- [ ] Step 2: Update imports
- [ ] Step 3: Update ServerState struct with cfg gates
- [ ] Step 4: Replace all crate::protocol references
- [ ] Step 5: Feature-gate web server code in start_server
- [ ] Step 6: Feature-gate web server code in stop_server
- [ ] Step 7: Feature-gate get_web_status
- [ ] Step 8: Feature-gate ServerState init in run()
- [ ] Step 9: Verify compilation
- [ ] Step 10: Commit

---

## Task 8: Update Other Modules

**Files:**
- Modify: src-tauri/src/tcp_server.rs (crate::protocol:: -> micyou_protocol::)
- Modify: src-tauri/src/udp_server.rs (crate::protocol:: -> micyou_protocol::)
- Modify: src-tauri/src/network.rs (crate::protocol:: -> micyou_protocol::)
- Modify: src-tauri/src/jitter_buffer.rs (crate::protocol:: -> micyou_protocol::)
- Modify: src-tauri/src/vbcable.rs (add stub for install_vbcable when feature disabled)

Import changes per file:

tcp_server.rs:
- crate::protocol::{PACKET_MAGIC, HANDSHAKE_CLIENT_STR, HANDSHAKE_SERVER_STR} -> micyou_protocol::
- crate::protocol::micyou::{MessageWrapper, AudioPacketMessageOrdered} -> micyou_protocol::micyou::

udp_server.rs:
- crate::protocol::UDP_PACKET_MAGIC -> micyou_protocol::UDP_PACKET_MAGIC
- crate::protocol::micyou:: -> micyou_protocol::micyou::

network.rs:
- crate::protocol::MDNS_SERVICE_TYPE -> micyou_protocol::MDNS_SERVICE_TYPE
- crate::protocol::MDNS_WEB_SERVICE_TYPE -> micyou_protocol::MDNS_WEB_SERVICE_TYPE

jitter_buffer.rs:
- crate::protocol::micyou::AudioPacketMessageOrdered -> micyou_protocol::micyou::AudioPacketMessageOrdered

vbcable.rs - add stub:
```rust
#[cfg(not(feature = "vbcable"))]
#[tauri::command]
pub fn install_vbcable() -> Result<VBCableResult, String> {
    Ok(VBCableResult {
        success: false,
        error_type: Some("feature_disabled".to_string()),
        message: Some("VB-Cable installation feature not enabled".to_string()),
    })
}
```

- [ ] Step 1: Update tcp_server.rs
- [ ] Step 2: Update udp_server.rs
- [ ] Step 3: Update network.rs
- [ ] Step 4: Update jitter_buffer.rs
- [ ] Step 5: Add feature stub to vbcable.rs
- [ ] Step 6: Verify no other files have crate::protocol imports
- [ ] Step 7: cargo check
- [ ] Step 8: Commit

---

## Task 9: Update build.rs and main.rs

**Files:**
- Modify: src-tauri/build.rs
- Modify: src-tauri/src/main.rs

build.rs -> just tauri_build::build() (prost-build moved to micyou-protocol)

main.rs:
```rust
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    micyou_audio::init_onnx_runtime();
    tauri_app_lib::run()
}
```

- [ ] Step 1: Simplify build.rs
- [ ] Step 2: Update main.rs
- [ ] Step 3: Remove prost-build from Cargo.toml build-dependencies
- [ ] Step 4: Commit

---

## Task 10: Verify Full Build

- [ ] Step 1: cargo check --workspace
- [ ] Step 2: cargo check -p micyou-app --no-default-features
- [ ] Step 3: cargo check per feature (web-server, vbcable, adb individually)
- [ ] Step 4: cargo test --workspace
- [ ] Step 5: cargo clippy --workspace (fix warnings)
- [ ] Step 6: npm run tauri dev (verify app launches)
- [ ] Step 7: Final commit

---

## Notes

### Why stats.rs and jitter_buffer.rs Stay in micyou-app
Zero heavy deps (serde + std only), tightly coupled to tcp_server/udp_server/lib.rs. A 4th crate adds complexity with no benefit.

### Why Tauri-dependent Code Stays in micyou-app
tray.rs, commands/about.rs, tcp_server.rs, web_server.rs all use tauri crate. Extracting requires adding tauri as dep of sub-crate (defeats purpose).

### Feature Flag Strategy
Features control dependency weight, not code compilation:
- web-server: Gates axum + TLS stack (~15 transitive crates), module conditionally compiled
- vbcable: Gates zip crate, module always compiled, install fn gated with stub
- adb: No extra deps, always compiled, feature for future
- dsp (micyou-audio): Gates nnnoiseless/ort/rustfft, module conditionally compiled

### sync-version.js
No changes needed. It reads/writes src-tauri/Cargo.toml at same path. Verify it doesn't hardcode package name "micyou" (now "micyou-app").

---

## Addendum: sync-version.js Compatibility

### Issue
sync-version.js uses regex `^version\s*=\s*".*"$` to update src-tauri/Cargo.toml. With `version.workspace = true`, the regex won't match and version sync will silently fail.

### Solution
**Keep `version = "2.0.0"` explicitly in src-tauri/Cargo.toml** instead of using `version.workspace = true`. The workspace.package.version is still used by micyou-protocol and micyou-audio (which don't need external version sync). This avoids modifying sync-version.js.

### Updated Task 5: micyou-app Cargo.toml
In the [package] section, use:
```toml
[package]
name = "micyou-app"
version = "2.0.0"              # Explicit (not workspace) - sync-version.js needs this
authors.workspace = true
edition.workspace = true
```

### Package Name Change Note
Package name changes from `micyou` to `micyou-app`. sync-version.js doesn't reference the package name (only matches version line), so no script changes needed.
