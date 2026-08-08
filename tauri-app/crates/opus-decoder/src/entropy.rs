//! Entropy (range) decoder used by Opus (CELT + SILK).
//!
//! Port of libopus CELT range decoder (`celt/entdec.c`, `celt/entcode.c`).
//! Decoder-only; we keep the structure/semantics close to the reference to
//! minimize bit-exactness risk.

#![allow(dead_code)]

use core::cmp;

// Constants from `celt/mfrngcod.h`.
const EC_SYM_BITS: i32 = 8;
const EC_SYM_MAX: u32 = (1u32 << EC_SYM_BITS) - 1;
const EC_CODE_BITS: i32 = 32;
const EC_CODE_TOP: u32 = 1u32 << (EC_CODE_BITS - 1);
const EC_CODE_BOT: u32 = EC_CODE_TOP >> EC_SYM_BITS;
const EC_CODE_EXTRA: i32 = ((EC_CODE_BITS - 2) % EC_SYM_BITS) + 1;

// Constants from `celt/entcode.h`.
const EC_UINT_BITS: i32 = 8;
const BITRES: i32 = 3;

// `ec_window` in libopus is `opus_uint32` by default.
type EcWindow = u32;
const EC_WINDOW_SIZE: i32 = (core::mem::size_of::<EcWindow>() as i32) * 8;

#[inline]
fn ec_ilog(v: u32) -> i32 {
    // Matches `EC_ILOG()` semantics: returns 0 when v==0, else floor(log2(v))+1.
    if v == 0 {
        0
    } else {
        32 - v.leading_zeros() as i32
    }
}

#[inline]
fn imul32(a: u32, b: u32) -> u32 {
    a.wrapping_mul(b)
}

#[inline]
fn celt_udiv(n: u32, d: u32) -> u32 {
    // libopus has an optional div table optimization; `n/d` is bit-exact.
    n / d
}

#[derive(Debug, Clone)]
pub(crate) struct EcDec<'a> {
    buf: &'a [u8],
    storage: usize,

    // Raw bits live at the end of the buffer, read LSB-first.
    end_offs: usize,
    end_window: EcWindow,
    nend_bits: i32,

    // Total whole bits "used" so far (range coder + raw bits).
    nbits_total: i32,

    // Range-coded bytes are read from the start.
    offs: usize,

    // Range decoder state.
    rng: u32,
    // Difference between top of current range and input value, minus 1.
    val: u32,
    // Saved normalization factor from `decode()`.
    ext: u32,
    // Buffered symbol awaiting normalization.
    rem: i32,

    error: bool,
}

impl<'a> EcDec<'a> {
    pub fn new(buf: &'a [u8]) -> Self {
        let storage = buf.len();

        // Mirrors `ec_dec_init()`.
        let mut st = Self {
            buf,
            storage,
            end_offs: 0,
            end_window: 0,
            nend_bits: 0,
            nbits_total: EC_CODE_BITS + 1
                - ((EC_CODE_BITS - EC_CODE_EXTRA) / EC_SYM_BITS) * EC_SYM_BITS,
            offs: 0,
            rng: 1u32 << EC_CODE_EXTRA,
            val: 0,
            ext: 0,
            rem: 0,
            error: false,
        };

        st.rem = st.read_byte() as i32;
        st.val = st.rng - 1 - ((st.rem as u32) >> (EC_SYM_BITS - EC_CODE_EXTRA));
        st.normalize();

        st
    }

    #[inline]
    pub fn tell(&self) -> i32 {
        // Matches `ec_tell()`: slightly over-estimates.
        self.nbits_total - ec_ilog(self.rng)
    }

    #[inline]
    pub fn tell_frac(&self) -> u32 {
        // Port of fast `ec_tell_frac()` from `celt/entcode.c`.
        const CORRECTION: [u32; 8] = [35733, 38967, 42495, 46340, 50535, 55109, 60097, 65535];

        let nbits = (self.nbits_total as u32) << BITRES;
        let mut l = ec_ilog(self.rng);
        let r = self.rng >> (l - 16);
        let mut b = ((r >> 12) - 8) as usize;
        b += (r > CORRECTION[b]) as usize;
        l = (l << 3) + b as i32;
        nbits - (l as u32)
    }

    #[inline]
    pub fn final_range(&self) -> u32 {
        // libopus exposes this via OPUS_GET_FINAL_RANGE.
        self.rng
    }

    /// Return active entropy storage in bytes.
    ///
    /// Params: none.
    /// Returns: currently visible storage after any shrinking for raw bits.
    #[inline]
    pub fn storage(&self) -> usize {
        self.storage
    }

    /// Return the whole-bit counter tracked by the entropy decoder.
    ///
    /// Params: none.
    /// Returns: `nbits_total`, excluding partial range-coder bits.
    #[inline]
    pub fn nbits_total(&self) -> i32 {
        self.nbits_total
    }

    /// Return current entropy decoder range state.
    ///
    /// Params: none.
    /// Returns: internal `rng` value used for CELT noise/folding seed chaining.
    pub fn rng(&self) -> u32 {
        self.rng
    }

    /// Return current entropy decoder `val` state.
    ///
    /// Params: none.
    /// Returns: internal `val` register for debug comparisons.
    pub fn debug_val(&self) -> u32 {
        self.val
    }

    #[inline]
    pub fn is_error(&self) -> bool {
        self.error
    }

    /// Shrink visible decoder storage to hide trailing side data.
    ///
    /// Params: `bytes` number of bytes reserved at packet end.
    /// Returns: nothing; the active storage window is clamped in place.
    pub fn shrink_storage(&mut self, bytes: usize) {
        self.storage = self.storage.saturating_sub(bytes);
        self.end_offs = self.end_offs.min(self.storage);
        if self.offs > self.storage {
            self.offs = self.storage;
            self.error = true;
        }
    }

    /// Debug accessor: number of raw-bit bytes consumed from packet end.
    ///
    /// Params: none.
    /// Returns: `end_offs` value from entropy decoder state.
    pub(crate) fn end_offs(&self) -> usize {
        self.end_offs
    }

    /// Debug accessor: number of currently buffered raw bits.
    ///
    /// Params: none.
    /// Returns: `nend_bits` value from entropy decoder state.
    pub(crate) fn nend_bits(&self) -> i32 {
        self.nend_bits
    }

    #[inline]
    fn read_byte(&mut self) -> u8 {
        if self.offs < self.storage {
            let b = self.buf[self.offs];
            self.offs += 1;
            b
        } else {
            0
        }
    }

    #[inline]
    fn read_byte_from_end(&mut self) -> u8 {
        if self.end_offs < self.storage {
            self.end_offs += 1;
            self.buf[self.storage - self.end_offs]
        } else {
            0
        }
    }

    #[inline]
    fn normalize(&mut self) {
        while self.rng <= EC_CODE_BOT {
            self.nbits_total += EC_SYM_BITS;
            self.rng <<= EC_SYM_BITS;

            // Use up remaining bits from last symbol.
            let mut sym = self.rem as u32;

            // Read next symbol.
            self.rem = self.read_byte() as i32;

            // Take the rest of the bits we need from this new symbol.
            sym = (sym << EC_SYM_BITS | (self.rem as u32)) >> (EC_SYM_BITS - EC_CODE_EXTRA);

            // Subtract from val, capped to < EC_CODE_TOP.
            self.val = ((self.val << EC_SYM_BITS) + (EC_SYM_MAX & !sym)) & (EC_CODE_TOP - 1);
        }
    }

    /// Equivalent of `ec_decode()`.
    pub fn decode(&mut self, ft: u32) -> u32 {
        self.ext = celt_udiv(self.rng, ft);
        let s = self.val / self.ext;
        ft - cmp::min(s + 1, ft)
    }

    /// Equivalent of `ec_decode_bin()` with `ft == 1<<bits`.
    pub fn decode_bin(&mut self, bits: u32) -> u32 {
        self.ext = self.rng >> bits;
        let s = self.val / self.ext;
        (1u32 << bits) - cmp::min(s + 1, 1u32 << bits)
    }

    /// Equivalent of `ec_dec_update()`.
    pub fn update(&mut self, fl: u32, fh: u32, ft: u32) {
        let s = imul32(self.ext, ft - fh);
        self.val = self.val.wrapping_sub(s);
        self.rng = if fl > 0 {
            imul32(self.ext, fh - fl)
        } else {
            self.rng.wrapping_sub(s)
        };
        self.normalize();
    }

    /// Decode a bit that has a 1/(1<<logp) probability of being a one.
    pub fn dec_bit_logp(&mut self, logp: u32) -> bool {
        let r = self.rng;
        let d = self.val;
        let s = r >> logp;
        let ret = d < s;
        if !ret {
            self.val = d - s;
        }
        self.rng = if ret { s } else { r - s };
        self.normalize();
        ret
    }

    /// Decodes a symbol from an "inverse CDF" table (`ec_dec_icdf`).
    pub fn dec_icdf(&mut self, icdf: &[u8], ftb: u32) -> i32 {
        let s0 = self.rng;
        let d = self.val;
        let r = s0 >> ftb;
        let mut ret: i32 = -1;
        let mut s = s0;
        let mut t;
        loop {
            t = s;
            ret += 1;
            let idx = ret as usize;
            if idx >= icdf.len() {
                self.error = true;
                return icdf.len() as i32 - 1;
            }
            s = imul32(r, icdf[idx] as u32);
            if d >= s {
                break;
            }
        }
        self.val = d - s;
        self.rng = t - s;
        self.normalize();
        ret
    }

    /// Decodes a symbol from an "inverse CDF" table (`ec_dec_icdf16`).
    pub fn dec_icdf16(&mut self, icdf: &[u16], ftb: u32) -> i32 {
        let s0 = self.rng;
        let d = self.val;
        let r = s0 >> ftb;
        let mut ret: i32 = -1;
        let mut s = s0;
        let mut t;
        loop {
            t = s;
            ret += 1;
            let idx = ret as usize;
            if idx >= icdf.len() {
                self.error = true;
                return icdf.len() as i32 - 1;
            }
            s = imul32(r, icdf[idx] as u32);
            if d >= s {
                break;
            }
        }
        self.val = d - s;
        self.rng = t - s;
        self.normalize();
        ret
    }

    /// Extract a raw unsigned integer with a non-power-of-2 range (`ec_dec_uint`).
    pub fn dec_uint(&mut self, ft_in: u32) -> u32 {
        // Match libopus behavior: undefined for ft<=1; we treat it as error.
        if ft_in <= 1 {
            self.error = true;
            return 0;
        }

        let mut ftm1 = ft_in - 1;
        let mut ftb = ec_ilog(ftm1);
        if ftb > EC_UINT_BITS {
            ftb -= EC_UINT_BITS;
            let ft = (ftm1 >> ftb) as u32 + 1;
            let s = self.decode(ft);
            self.update(s, s + 1, ft);
            let t = (s << ftb) | self.dec_bits(ftb as u32);
            if t <= ftm1 {
                t
            } else {
                self.error = true;
                ftm1
            }
        } else {
            ftm1 += 1;
            let s = self.decode(ftm1);
            self.update(s, s + 1, ftm1);
            s
        }
    }

    /// Extract a sequence of raw bits from the stream (`ec_dec_bits`).
    pub fn dec_bits(&mut self, bits: u32) -> u32 {
        debug_assert!(bits <= 25);

        let mut window = self.end_window;
        let mut available = self.nend_bits;
        if available < bits as i32 {
            loop {
                window |= (self.read_byte_from_end() as EcWindow) << available;
                available += EC_SYM_BITS;
                if available > EC_WINDOW_SIZE - EC_SYM_BITS {
                    break;
                }
            }
        }
        let ret = window & ((1u32 << bits) - 1);
        window >>= bits;
        available -= bits as i32;
        self.end_window = window;
        self.nend_bits = available;
        self.nbits_total += bits as i32;
        ret
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ilog_matches_expected() {
        assert_eq!(ec_ilog(0), 0);
        assert_eq!(ec_ilog(1), 1);
        assert_eq!(ec_ilog(2), 2);
        assert_eq!(ec_ilog(3), 2);
        assert_eq!(ec_ilog(4), 3);
        assert_eq!(ec_ilog(0xFFFF_FFFF), 32);
    }

    #[test]
    fn tell_frac_monotonicish() {
        // Sanity: consuming bits should increase the reported usage.
        let mut d = EcDec::new(&[0u8; 64]);
        let a = d.tell_frac();
        let _ = d.dec_bits(8);
        let b = d.tell_frac();
        assert!(b >= a);
    }
}
