/*
   Copyright (c) 2020  Dario Mambro ( dario.mambro@gmail.com )
*/

/* Copyright (c) 2013  Julien Pommier ( pommier@modartt.com )

   Redistribution and use of the Software in source and binary forms,
   with or without modification, is permitted provided that the
   following conditions are met:

   - Neither the names of NCAR's Computational and Information Systems
   Laboratory, the University Corporation for Atmospheric Research,
   nor the names of its sponsors or contributors may be used to
   endorse or promote products derived from this Software without
   specific prior written permission.

   - Redistributions of source code must retain the above copyright
   notices, this list of conditions, and the disclaimer below.

   - Redistributions in binary form must reproduce the above copyright
   notice, this list of conditions, and the disclaimer below in the
   documentation and/or other materials provided with the
   distribution.

   THIS SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
   EXPRESS OR IMPLIED, INCLUDING, BUT NOT LIMITED TO THE WARRANTIES OF
   MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
   NONINFRINGEMENT. IN NO EVENT SHALL THE CONTRIBUTORS OR COPYRIGHT
   HOLDERS BE LIABLE FOR ANY CLAIM, INDIRECT, INCIDENTAL, SPECIAL,
   EXEMPLARY, OR CONSEQUENTIAL DAMAGES OR OTHER LIABILITY, WHETHER IN AN
   ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
   CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS WITH THE
   SOFTWARE.
*/

#ifndef PF_NEON_DBL_H
#define PF_NEON_DBL_H

/*
  NEON 64bit support macros
*/
#if !defined(PFFFT_SIMD_DISABLE) && defined(PFFFT_ENABLE_NEON) && (defined(__aarch64__) || defined(__arm64__))

#pragma message (__FILE__ ": NEON (from AVX) macros are defined" )

#include "pf_neon_double_from_avx.h"
typedef __m256d v4sf;

/* 4 doubles by simd vector */
#  define SIMD_SZ 4

typedef union v4sf_union {
  v4sf  v;
  double f[SIMD_SZ];
} v4sf_union;

#  define VARCH "NEON"
#  define VREQUIRES_ALIGN 1
#  define VZERO() _mm256_setzero_pd()
#  define VMUL(a,b) _mm256_mul_pd(a,b)
#  define VADD(a,b) _mm256_add_pd(a,b)
#  define VMADD(a,b,c) _mm256_add_pd(_mm256_mul_pd(a,b), c)
#  define VSUB(a,b) _mm256_sub_pd(a,b)
#  define LD_PS1(p) _mm256_set1_pd(p)
#  define VLOAD_UNALIGNED(ptr)  _mm256_loadu_pd(ptr)
#  define VLOAD_ALIGNED(ptr)    _mm256_load_pd(ptr)

FORCE_INLINE __m256d _mm256_insertf128_pd_1(__m256d a, __m128d b)
{
    __m256d res;
    res.vect_f64[0] = a.vect_f64[0];
    res.vect_f64[1] = b;
    return res;
}

FORCE_INLINE __m128d _mm_shuffle_pd_00(__m128d a, __m128d b)
{
    float64x1_t al = vget_low_f64(a);
    float64x1_t bl = vget_low_f64(b);
    return vcombine_f64(al, bl);
}

FORCE_INLINE __m128d _mm_shuffle_pd_11(__m128d a, __m128d b)
{
    float64x1_t ah = vget_high_f64(a);
    float64x1_t bh = vget_high_f64(b);
    return vcombine_f64(ah, bh);
}

FORCE_INLINE __m256d _mm256_shuffle_pd_00(__m256d a, __m256d b)
{
    __m256d res;
    res.vect_f64[0] = _mm_shuffle_pd_00(a.vect_f64[0],b.vect_f64[0]);
    res.vect_f64[1] = _mm_shuffle_pd_00(a.vect_f64[1],b.vect_f64[1]);
    return res;
}

FORCE_INLINE __m256d _mm256_shuffle_pd_11(__m256d a, __m256d b)
{
    __m256d res;
    res.vect_f64[0] = _mm_shuffle_pd_11(a.vect_f64[0],b.vect_f64[0]);
    res.vect_f64[1] = _mm_shuffle_pd_11(a.vect_f64[1],b.vect_f64[1]);
    return res;
}

FORCE_INLINE __m256d _mm256_permute2f128_pd_0x20(__m256d a, __m256d b) {
    __m256d res;
    res.vect_f64[0] = a.vect_f64[0];
    res.vect_f64[1] = b.vect_f64[0];
    return res;
}


FORCE_INLINE __m256d _mm256_permute2f128_pd_0x31(__m256d a, __m256d b)
{
    __m256d res;
    res.vect_f64[0] = a.vect_f64[1];
    res.vect_f64[1] = b.vect_f64[1];
    return res;
}

FORCE_INLINE __m256d _mm256_reverse(__m256d x)
{
    __m256d res;
    float64x2_t low = x.vect_f64[0];
    float64x2_t high = x.vect_f64[1];
    float64x1_t a = vget_low_f64(low);
    float64x1_t b = vget_high_f64(low);
    float64x1_t c = vget_low_f64(high);
    float64x1_t d = vget_high_f64(high);
    res.vect_f64[0] =  vcombine_f64(d, c);
    res.vect_f64[1] =  vcombine_f64(b, a);
    return res;
}

/* INTERLEAVE2 (in1, in2, out1, out2) pseudo code:
out1 = [ in1[0], in2[0], in1[1], in2[1] ]
out2 = [ in1[2], in2[2], in1[3], in2[3] ]
*/
#  define INTERLEAVE2(in1, in2, out1, out2) {							\
	__m128d low1__ = _mm256_castpd256_pd128(in1);						\
	__m128d low2__ = _mm256_castpd256_pd128(in2);						\
	__m128d high1__ = _mm256_extractf128_pd(in1, 1);					\
	__m128d high2__ = _mm256_extractf128_pd(in2, 1);					\
	__m256d tmp__ = _mm256_insertf128_pd_1(								\
		_mm256_castpd128_pd256(_mm_shuffle_pd_00(low1__, low2__)),		\
		_mm_shuffle_pd_11(low1__, low2__));								\
	out2 = _mm256_insertf128_pd_1(										\
		_mm256_castpd128_pd256(_mm_shuffle_pd_00(high1__, high2__)),	\
		_mm_shuffle_pd_11(high1__, high2__));							\
	out1 = tmp__;														\
}

/*UNINTERLEAVE2(in1, in2, out1, out2) pseudo code:
out1 = [ in1[0], in1[2], in2[0], in2[2] ]
out2 = [ in1[1], in1[3], in2[1], in2[3] ]
*/
#  define UNINTERLEAVE2(in1, in2, out1, out2) {							\
	__m128d low1__ = _mm256_castpd256_pd128(in1);						\
	__m128d low2__ = _mm256_castpd256_pd128(in2);						\
	__m128d high1__ = _mm256_extractf128_pd(in1, 1);					\
	__m128d high2__ = _mm256_extractf128_pd(in2, 1); 					\
	__m256d tmp__ = _mm256_insertf128_pd_1(								\
		_mm256_castpd128_pd256(_mm_shuffle_pd_00(low1__, high1__)),		\
		_mm_shuffle_pd_00(low2__, high2__));							\
	out2 = _mm256_insertf128_pd_1(										\
		_mm256_castpd128_pd256(_mm_shuffle_pd_11(low1__, high1__)),		\
		_mm_shuffle_pd_11(low2__, high2__));							\
	out1 = tmp__;														\
}

#  define VTRANSPOSE4(row0, row1, row2, row3) {							\
        __m256d tmp3, tmp2, tmp1, tmp0;                     			\
                                                            			\
        tmp0 = _mm256_shuffle_pd_00((row0),(row1));       				\
        tmp2 = _mm256_shuffle_pd_11((row0),(row1));       				\
        tmp1 = _mm256_shuffle_pd_00((row2),(row3));       				\
        tmp3 = _mm256_shuffle_pd_11((row2),(row3));       				\
                                                            			\
        (row0) = _mm256_permute2f128_pd_0x20(tmp0, tmp1);			    \
        (row1) = _mm256_permute2f128_pd_0x20(tmp2, tmp3); 		        \
        (row2) = _mm256_permute2f128_pd_0x31(tmp0, tmp1); 		        \
        (row3) = _mm256_permute2f128_pd_0x31(tmp2, tmp3); 		        \
    }

/*VSWAPHL(a, b) pseudo code:
return [ b[0], b[1], a[2], a[3] ]
*/
#  define VSWAPHL(a,b)	\
   _mm256_insertf128_pd_1(_mm256_castpd128_pd256(_mm256_castpd256_pd128(b)), _mm256_extractf128_pd(a, 1))

/* reverse/flip all floats */
#  define VREV_S(a)   _mm256_reverse(a)

/* reverse/flip complex floats */
#  define VREV_C(a)    _mm256_insertf128_pd_1(_mm256_castpd128_pd256(_mm256_extractf128_pd(a, 1)), _mm256_castpd256_pd128(a))

#  define VALIGNED(ptr) ((((uintptr_t)(ptr)) & 0x1F) == 0)

#endif

#endif /* PF_AVX_DBL_H */

