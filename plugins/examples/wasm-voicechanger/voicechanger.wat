;; Example voice changer plugin (WASM runtime)
;; Real-time pitch shifting DSP: pitch > 1 raises the voice, < 1 lowers it
;; Linear-interpolation resampling with a history buffer; wrap-around reuse
;; keeps output duration constant (classic low-cost real-time voice changer)
;;
;; Imports (module "micyou"): log, get_config, set_config
;; Exports: memory, alloc, dealloc, api_version, init, process, handle_message
;;
;; Memory layout (static):
;;   0x100 "pitch\0"   0x110 "bypass\0"
;;   0x120 $PITCH (f64)  0x128 $READPOS (f64)
;;   0x180 $HLEN (i32)   0x184 $BYPASS (i32)
;;   0x190 msg-init string  0x1C0 "\"value\":" needle
;;   0x2000 $HIST f32 history buffer (1920 samples = 7680 bytes)
;;   heap bump starts at 0x4000

(module
  (import "micyou" "log" (func $log (param i32 i32)))
  (import "micyou" "get_config" (func $get_config (param i32) (result i32)))
  (import "micyou" "set_panel_icon" (func $set_panel_icon (param i32 i32)))
  (import "micyou" "set_config" (func $set_config (param i32 i32) (result i32)))

  (memory (export "memory") 4)

  ;; ---------- static data ----------
  (data (i32.const 0x100) "pitch\00")
  (data (i32.const 0x110) "bypass\00")
  (data (i32.const 0x190) "voicechanger initialized\00")
  (data (i32.const 0x1C0) "\"value\":")
  (data (i32.const 0x1F8) "control\00")
  (data (i32.const 0x208) "\F0\9F\8E\9A\EF\B8\8F\00")
  (data (i32.const 0x1D0) "config reloaded\00")
  (data (i32.const 0x1E0) "true\00")
  (data (i32.const 0x1F0) "false\00")

  ;; ---------- bump allocator ----------
  (global $heap (mut i32) (i32.const 0x4000))
  (func (export "alloc") (param $n i32) (result i32)
    (local $p i32)
    (local.set $p (global.get $heap))
    (i32.store (local.get $p) (local.get $n))
    (global.set $heap (i32.add (global.get $heap) (i32.add (local.get $n) (i32.const 8))))
    (i32.add (local.get $p) (i32.const 8)))
  (func (export "dealloc") (param $p i32) (param $n i32))

  (func (export "api_version") (result i32) (i32.const 1))

  ;; ---------- helpers ----------
  (func $is_digit (param $c i32) (result i32)
    (i32.and (i32.ge_u (local.get $c) (i32.const 48)) (i32.le_u (local.get $c) (i32.const 57))))

  (func $is_num_start (param $c i32) (result i32)
    (i32.or (call $is_digit (local.get $c)) (i32.eq (local.get $c) (i32.const 45))))

  ;; parse ASCII decimal (possibly negative, optional fraction) to f64
  (func $parse_f64 (param $ptr i32) (param $len i32) (result f64)
    (local $i i32) (local $neg f64) (local $val f64) (local $frac f64)
    (local $infrac i32) (local $c i32) (local $ok i32)
    (local.set $neg (f64.const 1.0))
    (block $done
      (loop $l
        (br_if $done (i32.ge_u (local.get $i) (local.get $len)))
        (local.set $c (i32.load8_u (i32.add (local.get $ptr) (local.get $i))))
        (if (i32.eq (local.get $c) (i32.const 45))
          (then (local.set $neg (f64.const -1.0))))
        (if (call $is_digit (local.get $c))
          (then
            (local.set $ok (i32.const 1))
            (if (local.get $infrac)
              (then
                (local.set $val (f64.add (local.get $val)
                  (f64.div (f64.convert_i32_u (i32.sub (local.get $c) (i32.const 48))) (local.get $frac))))
                (local.set $frac (f64.mul (local.get $frac) (f64.const 10.0))))
              (else
                (local.set $val (f64.add (f64.mul (local.get $val) (f64.const 10.0))
                  (f64.convert_i32_u (i32.sub (local.get $c) (i32.const 48))))))))
          (else
            (if (i32.eq (local.get $c) (i32.const 46))
              (then (local.set $infrac (i32.const 1)) (local.set $frac (f64.const 10.0)))
              (else (if (local.get $ok) (then (br $done)))))))
        (local.set $i (i32.add (local.get $i) (i32.const 1)))
        (br $l)))
    (f64.mul (local.get $val) (local.get $neg)))

  ;; parse NUL-terminated string to f64
  (func $parse_cstr (param $ptr i32) (result f64)
    (local $len i32)
    (block $done
      (loop $l
        (br_if $done (i32.eqz (i32.load8_u (i32.add (local.get $ptr) (local.get $len)))))
        (local.set $len (i32.add (local.get $len) (i32.const 1)))
        (br $l)))
    (call $parse_f64 (local.get $ptr) (local.get $len)))

  ;; does [ptr..ptr+len) contain needle [n..n+nlen)?
  (func $contains (param $h i32) (param $hlen i32) (param $n i32) (param $nlen i32) (result i32)
    (local $i i32) (local $j i32)
    (block $out (result i32)
      (loop $l
        (br_if $out (i32.const 0) (i32.gt_u (i32.add (local.get $i) (local.get $nlen)) (local.get $hlen)))
        (local.set $j (i32.const 0))
        ;; 内层 loop 以 block (result i32) 收尾：1=全匹配，0=不匹配
        (if (i32.eqz (block $match (result i32)
              (loop $m
                (br_if $match (i32.const 1) (i32.ge_u (local.get $j) (local.get $nlen)))
                (br_if $match (i32.const 0)
                  (i32.ne
                    (i32.load8_u (i32.add (local.get $h) (i32.add (local.get $i) (local.get $j))))
                    (i32.load8_u (i32.add (local.get $n) (local.get $j)))))
                (local.set $j (i32.add (local.get $j) (i32.const 1)))
                (br $m))
              (i32.const 0)))
          (then (nop))
          (else (br $out (i32.const 1))))
        (local.set $i (i32.add (local.get $i) (i32.const 1)))
        (br $l))
      (i32.const 0)))

  ;; bytewise compare ptr[i..i+8] with the needle at 0x1C0
  (func $eq8 (param $ptr i32) (param $i i32) (result i32)
    (local $k i32) (local $ok i32)
    (local.set $ok (i32.const 1))
    (block $done
      (loop $m
        (br_if $done (i32.ge_u (local.get $k) (i32.const 8)))
        (local.set $ok (i32.and (local.get $ok)
          (i32.eq
            (i32.load8_u (i32.add (local.get $ptr) (i32.add (local.get $i) (local.get $k))))
            (i32.load8_u (i32.add (i32.const 0x1C0) (local.get $k))))))
        (local.set $k (i32.add (local.get $k) (i32.const 1)))
        (br $m)))
    (local.get $ok))

  ;; ---------- init ----------
  (func (export "init") (result i32)
    (local $ptr i32) (local $c i32)
    (local.set $ptr (call $get_config (i32.const 0x100)))
    (if (i32.gt_s (local.get $ptr) (i32.const 0))
      (then (f64.store (i32.const 0x120) (call $parse_cstr (local.get $ptr)))))
    (local.set $ptr (call $get_config (i32.const 0x110)))
    (if (i32.gt_s (local.get $ptr) (i32.const 0))
      (then
        (local.set $c (i32.load8_u (local.get $ptr)))
        (if (i32.eq (local.get $c) (i32.const 116))
          (then (i32.store (i32.const 0x184) (i32.const 1)))
          (else (i32.store (i32.const 0x184) (i32.const 0))))))
    (call $set_panel_icon (i32.const 0x1F8) (i32.const 0x208))
    (call $log (i32.const 2) (i32.const 0x190))
    (i32.const 0))

  ;; ---------- process: pitch shift ----------
  (func (export "process") (param $data i32) (param $samples i32) (param $ch i32) (param $qms f64) (result i32)
    (local $i i32) (local $n i32) (local $p f64) (local $step f64) (local $pos f64)
    (local $i0 i32) (local $frac f64) (local $v0 f64) (local $v1 f64) (local $hl i32) (local $limit f64)
    (local $per i32) (local $c i32) (local $v f32)
    (if (i32.load (i32.const 0x184)) (then (return (i32.const 0))))
    (if (i32.eqz (local.get $ch)) (then (return (i32.const 0))))
    ;; per-channel frame size; only channel 0 is processed and the result
    ;; is copied to the other channels (interleaved stereo/multi-channel safe)
    (local.set $per (i32.div_u (local.get $samples) (local.get $ch)))
    (local.set $p (f64.load (i32.const 0x120)))
    ;; 输出频率 = 输入频率 × pitch，故读取步长 = pitch
    (local.set $step (f64.max (f64.min (local.get $p) (f64.const 8.0)) (f64.const 0.1)))
    (local.set $hl (i32.load (i32.const 0x180)))
    ;; append input frame (channel 0) to history
    (local.set $i (i32.const 0))
    (block $copy_done
      (loop $copy
        (br_if $copy_done (i32.ge_u (local.get $i) (local.get $per)))
        (i32.store
          (i32.add (i32.const 0x2000) (i32.mul (i32.add (local.get $hl) (local.get $i)) (i32.const 4)))
          (i32.load (i32.add (local.get $data) (i32.mul (i32.mul (local.get $i) (local.get $ch)) (i32.const 4)))))
        (local.set $i (i32.add (local.get $i) (i32.const 1)))
        (br $copy)))
    (local.set $hl (i32.add (local.get $hl) (local.get $per)))
    ;; trim to 1920 samples, adjusting read_pos
    (if (i32.gt_u (local.get $hl) (i32.const 1920))
      (then
        (local.set $n (i32.sub (local.get $hl) (i32.const 1920)))
        (local.set $i (i32.const 0))
        (block $mv_done
          (loop $mv
            (br_if $mv_done (i32.ge_u (local.get $i) (i32.const 1920)))
            (i32.store
              (i32.add (i32.const 0x2000) (i32.mul (local.get $i) (i32.const 4)))
              (i32.load (i32.add (i32.const 0x2000) (i32.mul (i32.add (local.get $i) (local.get $n)) (i32.const 4)))))
            (local.set $i (i32.add (local.get $i) (i32.const 1)))
            (br $mv)))
        (local.set $pos (f64.load (i32.const 0x128)))
        (local.set $pos (f64.sub (local.get $pos) (f64.convert_i32_u (local.get $n))))
        (if (f64.lt (local.get $pos) (f64.const 0.0)) (then (local.set $pos (f64.const 0.0))))
        (f64.store (i32.const 0x128) (local.get $pos))
        (local.set $hl (i32.const 1920))))
    (i32.store (i32.const 0x180) (local.get $hl))
    (local.set $limit (f64.convert_i32_u (i32.sub (local.get $hl) (i32.const 1))))
    (if (f64.lt (local.get $limit) (f64.const 0.0)) (then (return (i32.const 0))))
    ;; generate per outputs via linear interpolation on channel 0
    (local.set $i (i32.const 0))
    (block $out_done
      (loop $out
        (br_if $out_done (i32.ge_u (local.get $i) (local.get $per)))
        (local.set $pos (f64.load (i32.const 0x128)))
        (if (f64.gt (local.get $pos) (local.get $limit)) (then (local.set $pos (local.get $limit))))
        (local.set $i0 (i32.trunc_f64_s (local.get $pos)))
        (local.set $frac (f64.sub (local.get $pos) (f64.convert_i32_s (local.get $i0))))
        (local.set $v0 (f64.promote_f32 (f32.load (i32.add (i32.const 0x2000) (i32.mul (local.get $i0) (i32.const 4))))))
        (local.set $v1 (f64.promote_f32 (f32.load (i32.add (i32.const 0x2000) (i32.mul (i32.add (local.get $i0) (i32.const 1)) (i32.const 4))))))
        (local.set $v
          (f32.demote_f64
            (f64.add (f64.mul (local.get $v0) (f64.sub (f64.const 1.0) (local.get $frac)))
                     (f64.mul (local.get $v1) (local.get $frac)))))
        ;; write channel 0
        (f32.store
          (i32.add (local.get $data) (i32.mul (i32.mul (local.get $i) (local.get $ch)) (i32.const 4)))
          (local.get $v))
        ;; copy to remaining channels
        (local.set $c (i32.const 1))
        (block $c_done
          (loop $cl
            (br_if $c_done (i32.ge_u (local.get $c) (local.get $ch)))
            (f32.store
              (i32.add (local.get $data)
                (i32.mul (i32.add (i32.mul (local.get $i) (local.get $ch)) (local.get $c)) (i32.const 4)))
              (local.get $v))
            (local.set $c (i32.add (local.get $c) (i32.const 1)))
            (br $cl)))
        (f64.store (i32.const 0x128) (f64.add (local.get $pos) (local.get $step)))
        (if (f64.ge (f64.load (i32.const 0x128)) (local.get $limit))
          (then (f64.store (i32.const 0x128) (f64.const 0.0))))
        (local.set $i (i32.add (local.get $i) (i32.const 1)))
        (br $out)))
    (i32.const 0))

  ;; ---------- handle_message: config:changed payload ----------
  ;; payload is JSON {"key":"pitch","value":0.5} or
  ;; {"key":"bypass","value":true/false}
  ;; bypass: boolean "true"/"false" needles take priority, numeric fallback
  ;; pitch:  first numeric char is the value start
  (func (export "handle_message") (param $ptr i32) (param $len i32) (result i32)
    (local $i i32) (local $c i32) (local $vstart i32)
    (block $scan
      (loop $l
        (br_if $scan (i32.ge_u (local.get $i) (local.get $len)))
        (local.set $c (i32.load8_u (i32.add (local.get $ptr) (local.get $i))))
        (if (call $is_num_start (local.get $c))
          (then
            (local.set $vstart (local.get $i))
            (br $scan)))
        (local.set $i (i32.add (local.get $i) (i32.const 1)))
        (br $l)))
    ;; pitch update (numeric value required)
    (if (call $contains (local.get $ptr) (local.get $len) (i32.const 0x100) (i32.const 5))
      (then
        (if (i32.gt_u (local.get $vstart) (i32.const 0))
          (then
            (f64.store (i32.const 0x120)
              (call $parse_f64 (i32.add (local.get $ptr) (local.get $vstart)) (i32.sub (local.get $len) (local.get $vstart))))
            (call $log (i32.const 2) (i32.const 0x1D0))))))
    ;; bypass update: "true" -> 1, "false" -> 0, numeric -> !=0
    (if (call $contains (local.get $ptr) (local.get $len) (i32.const 0x110) (i32.const 6))
      (then
        (if (call $contains (local.get $ptr) (local.get $len) (i32.const 0x1E0) (i32.const 4))
          (then (i32.store (i32.const 0x184) (i32.const 1)))
          (else
            (if (call $contains (local.get $ptr) (local.get $len) (i32.const 0x1F0) (i32.const 5))
              (then (i32.store (i32.const 0x184) (i32.const 0)))
              (else
                (if (i32.gt_u (local.get $vstart) (i32.const 0))
                  (then
                    (if (f64.eq (call $parse_f64 (i32.add (local.get $ptr) (local.get $vstart)) (i32.sub (local.get $len) (local.get $vstart))) (f64.const 0.0))
                      (then (i32.store (i32.const 0x184) (i32.const 0)))
                      (else (i32.store (i32.const 0x184) (i32.const 1))))))))))))
    (i32.const 0))
)
