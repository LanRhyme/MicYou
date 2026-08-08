;; WASM plugin fixture for the micyou-plugin tests.
;;
;; Contract (mirrors the native ABI):
;;   imports (module "micyou"): log, get_config, set_config, emit_event,
;;                              send_message, audio_state, connected_devices
;;   exports: memory, alloc, dealloc, api_version, init, process,
;;            handle_event, handle_message, deinit (+ test helpers)
;;
;; The plugin scales audio by a configurable gain (default 1.0) and counts
;; received events/messages. Strings live in static data segments; the bump
;; allocator serves host-side buffers.
(module
  (import "micyou" "log" (func $log (param i32 i32)))
  (import "micyou" "get_config" (func $get_config (param i32) (result i32)))
  (import "micyou" "set_config" (func $set_config (param i32 i32) (result i32)))
  (import "micyou" "emit_event" (func $emit_event (param i32 i32) (result i32)))
  (import "micyou" "send_message" (func $send_message (param i32 i32 i32) (result i32)))
  (import "micyou" "audio_state" (func $audio_state (result i32)))
  (import "micyou" "connected_devices" (func $connected_devices (result i32)))

  (memory (export "memory") 1)

  ;; static data
  (data (i32.const 0) "fixture.key\00")
  (data (i32.const 64) "wasm fixture initialized\00")

  ;; simple bump allocator (8-byte aligned)
  (global $bump (mut i32) (i32.const 1024))

  (func (export "alloc") (param $size i32) (result i32)
    (local $ptr i32)
    (local.set $ptr (global.get $bump))
    (global.set $bump
      (i32.add
        (global.get $bump)
        (i32.and (i32.add (local.get $size) (i32.const 7)) (i32.const -8))))
    (local.get $ptr))

  (func (export "dealloc") (param $ptr i32) (param $size i32))

  (func (export "api_version") (result i32)
    (i32.const 1))

  (global $gain (mut f64) (f64.const 1.0))
  (global $events (mut i32) (i32.const 0))
  (global $messages (mut i32) (i32.const 0))

  (func (export "init") (result i32)
    (call $log (i32.const 2) (i32.const 64)) ;; INFO: "wasm fixture initialized"
    (i32.const 0))

  ;; process(data_ptr, samples, channels, queued_ms) -> 0=ok 1=bypass
  (func (export "process")
    (param $ptr i32) (param $samples i32) (param $channels i32) (param $queued_ms f64)
    (result i32)
    (local $i i32) (local $gain_f32 f32)
    (local.set $gain_f32 (f32.demote_f64 (global.get $gain)))
    (if (f32.le (local.get $gain_f32) (f32.const 0.0))
      (then (return (i32.const 1))))
    (local.set $i (i32.const 0))
    (block $done
      (loop $loop
        (br_if $done (i32.ge_u (local.get $i) (local.get $samples)))
        (f32.store
          (i32.add (local.get $ptr) (i32.mul (local.get $i) (i32.const 4)))
          (f32.mul
            (f32.load (i32.add (local.get $ptr) (i32.mul (local.get $i) (i32.const 4))))
            (local.get $gain_f32)))
        (local.set $i (i32.add (local.get $i) (i32.const 1)))
        (br $loop)))
    (i32.const 0))

  (func (export "handle_event") (param $json_ptr i32) (result i32)
    (global.set $events (i32.add (global.get $events) (i32.const 1)))
    (i32.const 0))

  (func (export "handle_message") (param $ptr i32) (param $len i32) (result i32)
    (global.set $messages (i32.add (global.get $messages) (i32.const 1)))
    (i32.const 0))

  (func (export "deinit"))

  ;; test helpers
  (func (export "test_set_gain") (param $g f64)
    (global.set $gain (local.get $g)))
  (func (export "test_events") (result i32)
    (global.get $events))
  (func (export "test_messages") (result i32)
    (global.get $messages))
  ;; calls the host get_config import with "fixture.key", returns the result ptr
  (func (export "test_host_get_config") (result i32)
    (call $get_config (i32.const 0)))
)
