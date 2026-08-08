;; MicYou WASM example: audioinspector (audio state monitor)
;; set_interval(2000) -> each tick samples audio_state + connected_devices
;; -> set_config persists both (scratch buffers are consumed sequentially)
(module
  (import "micyou" "log" (func $log (param i32 i32)))
  (import "micyou" "set_interval" (func $set_interval (param i64 i32) (result i64)))
  (import "micyou" "audio_state" (func $audio_state (result i32)))
  (import "micyou" "connected_devices" (func $connected_devices (result i32)))
  (import "micyou" "set_config" (func $set_config (param i32 i32) (result i32)))
  (import "micyou" "set_panel_icon" (func $set_panel_icon (param i32 i32)))
  (memory (export "memory") 4)
  ;; bump allocator
  (global $heap (mut i32) (i32.const 0x3000))
  (func (export "alloc") (param $n i32) (result i32)
    (local $p i32)
    (local.set $p (global.get $heap))
    (global.set $heap (i32.add (global.get $heap) (local.get $n)))
    (i32.store (local.get $p) (local.get $n))
    (i32.add (local.get $p) (i32.const 4)))
  (func (export "dealloc") (param $p i32) (param $n i32))
  (func (export "api_version") (result i32) (i32.const 1))

  ;; statics
  ;; 0x100 'inspector'     0x10C 📊     0x110 empty \00
  ;; 0x120 'audioState'    0x12C 'devices'
  ;; 0x138 'audio inspector ready'
  (data (i32.const 0x100) "inspector\00")
  (data (i32.const 0x10C) "\F0\9F\93\8A")
  (data (i32.const 0x110) "\00")
  (data (i32.const 0x120) "audioState\00")
  (data (i32.const 0x12C) "devices\00")
  (data (i32.const 0x138) "audio inspector ready\00")

  (func $strlen (param $p i32) (result i32)
    (local $i i32)
    (block $out
      (loop $lp
        (br_if $out (i32.eqz (i32.load8_u (i32.add (local.get $p) (local.get $i)))))
        (local.set $i (i32.add (local.get $i) (i32.const 1)))
        (br $lp)))
    (local.get $i))

  (func (export "init") (result i32)
    (call $set_panel_icon (i32.const 0x100) (i32.const 0x10C))
    (drop (call $set_interval (i64.const 2000) (i32.const 0x110)))
    (call $log (i32.const 2) (i32.const 0x138))
    (i32.const 0))

  ;; every tick (or manual refresh) samples and persists
  (func (export "handle_message") (param $payload i32) (param $len i32) (result i32)
    (local $a i32)
    (local.set $a (call $audio_state))
    (drop (call $set_config (i32.const 0x120) (local.get $a)))
    (drop (call $set_config (i32.const 0x12C) (call $connected_devices)))
    (i32.const 0))

  (func (export "deinit"))
)
