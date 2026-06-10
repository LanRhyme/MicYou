# UI Polish + Bidirectional Mute Design

## Overview

Two-part enhancement: (1) implement bidirectional mute between desktop and Android, (2) polish the main UI with anime.js-powered animations and refined visual details.

## Part 1: Bidirectional Mute

### Architecture

**Backend (Rust):**
- `ServerState` gains `connection_tx: Arc<Mutex<Option<mpsc::Sender<MessageWrapper>>>>` — stores the active TCP connection's sender channel
- `tcp_server::handle_client` stores `tx` into shared state on connect, clears on disconnect
- New Tauri command `set_mute_state(is_muted: bool)` — sends `MuteMessage { is_muted }` via the shared `tx` to Android
- When receiving `MuteMessage` from Android, emit `mute-state-changed` event to frontend via `app.emit()`
- When frontend sets mute, also emit `mute-state-changed` so UI stays in sync

**Frontend (Vue):**
- `toggleMute` calls `invoke('set_mute_state', { isMuted: !isMuted.value })` instead of local toggle
- Listen for `mute-state-changed` event → update `isMuted` ref
- Mute button placed in bottom bar right side, left of monitoring toggle button

### Mute Button Visual

- Icon: `VolumeX` (unmuted, normal color) / `Volume2` (muted, error color)
- Same size as sibling buttons (w-10 h-10 rounded-full)
- anime.js: icon crossfade with scale bounce on toggle

## Part 2: UI Visual Polish

### Bottom Bar
- All buttons unified to 40×40 rounded-full
- Hover: bg-surface-variant/80 transition (keep existing)
- Mute button added between monitoring and settings

### Mode Card
- Selected mode button: add subtle box-shadow glow (`shadow-primary/20 shadow-lg`)
- Transition: shadow 200ms ease

### Central Button
- Hover: scale-105 (anime.js spring)
- Active/press: scale-95 with spring back
- Streaming state breathing glow: anime.js opacity loop on the background blur element

### Header
- Window control buttons: hover bg transition stays (already good)

## Part 3: anime.js Animations

### Dependency
- Add `animejs` (v3) to package.json

### Animation Inventory

| Element | Trigger | Animation |
|---------|---------|-----------|
| Central connect button | hover | scale 1→1.05, spring ease |
| Central connect button | click | scale 1→0.95→1, spring bounce |
| Streaming breathing glow | state=streaming | opacity 0.3→0.6 loop, 2s |
| Mode button selection | mode change | shadow expand + color crossfade |
| Mute icon toggle | mute toggle | scale 0.8→1 + opacity fade, 300ms |
| Status text change | state change | translateY 10→0 + opacity 0→1, 400ms |
| LIVE badge | appears | scale 0→1 + opacity, spring |
| Bottom bar status dot | streaming | scale pulse loop (1→1.3→1), 1.5s |

### Implementation Pattern

Use `useAnime` composable or direct `anime()` calls in `onMounted`/`watchEffect`:
- Store anime instances for cleanup in `onUnmounted`
- Use `anime.timeline()` for sequenced animations
- Use `anime({ loop: true, direction: 'alternate' })` for breathing/pulse effects

## Files to Modify

| File | Change |
|------|--------|
| `package.json` | Add `animejs` dependency |
| `src-tauri/src/lib.rs` | Add `connection_tx` to `ServerState`, `set_mute_state` command, emit mute events |
| `src-tauri/src/tcp_server.rs` | Store/clear `tx` in shared state, emit `mute-state-changed` on receive |
| `src/App.vue` | Rewire `toggleMute`, add mute button in bottom bar, add anime.js animations |
| `src/locales/en.json` | No change needed (mute/unmute keys already exist) |
| `src/locales/zh.json` | No change needed |

## Out of Scope

- Audio processing changes (DSP stays the same)
- PocketLayout changes (already has mute button)
- Theme/appearance changes
- Settings dialog changes
