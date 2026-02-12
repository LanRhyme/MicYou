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

#ifndef PF_AVX_DBL_H
#define PF_AVX_DBL_H

/*
   vector support macros: the rest of the code is independant of
   AVX -- adding support for other platforms with 4-element
   vectors should be limited to these macros
*/


/*
  AVX support macros
*/
#if !defined(SIMD_SZ) && !defined(PFFFT_SIMD_DISABLE) && defined(__AVX__)
#pragma message( __FILE__ ": AVX macros are defined" )

#include <immintrin.h>
typedef __m256d v4sf;

/* 4 doubles by simd vector */
#  define SIMD_SZ 4

typedef union v4sf_union {
  v4sf  v;
  double f[SIMD_SZ];
} v4sf_union;

#  define VARCH "AVX"
#  define VREQUIRES_ALIGN 1
#  define VZERO() _mm256_setzero_pd()
#  define VMUL(a,b) _mm256_mul_pd(a,b)
#  define VADD(a,b) _mm256_add_pd(a,b)
#  define VMADD(a,b,c) _mm256_add_pd(_mm256_mul_pd(a,b), c)
#  define VSUB(a,b) _mm256_sub_pd(a,b)
#  define LD_PS1(p) _mm256_set1_pd(p)
#  define VLOAD_UNALIGNED(ptr)  _mm256_loadu_pd(ptr)
#  define VLOAD_ALIGNED(ptr)    _mm256_load_pd(ptr)

/* INTERLEAVE2 (in1, in2, out1, out2) pseudo code:
out1 = [ in1[0], in2[0], in1[1], in2[1] ]
out2 = [ in1[2], in2[2], in1[3], in2[3] ]
*/
#  define INTERLEAVE2(in1, in2, out1, out2) {							\
	__m128d low1__ = _mm256_castpd256_pd128(in1);						\
	__m128d low2__ = _mm256_castpd256_pd128(in2);						\
	__m128d high1__ = _mm256_extractf128_pd(in1, 1);					\
	__m128d high2__ = _mm256_extractf128_pd(in2, 1);					\
	__m256d tmp__ = _mm256_insertf128_pd(								\
		_mm256_castpd128_pd256(_mm_shuffle_pd(low1__, low2__, 0)),		\
		_mm_shuffle_pd(low1__, low2__, 3),								\
		1);																\
	out2 = _mm256_insertf128_pd(										\
		_mm256_castpd128_pd256(_mm_shuffle_pd(high1__, high2__, 0)),	\
		_mm_shuffle_pd(high1__, high2__, 3),							\
		1);																\
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
	__m256d tmp__ = _mm256_insertf128_pd(								\
		_mm256_castpd128_pd256(_mm_shuffle_pd(low1__, high1__, 0)),		\
		_mm_shuffle_pd(low2__, high2__, 0),								\
		1);																\
	out2 = _mm256_insertf128_pd(										\
		_mm256_castpd128_pd256(_mm_shuffle_pd(low1__, high1__, 3)),		\
		_mm_shuffle_pd(low2__, high2__, 3),								\
		1);																\
	out1 = tmp__;														\
}

#  define VTRANSPOSE4(row0, row1, row2, row3) {				\
        __m256d tmp3, tmp2, tmp1, tmp0;                     \
                                                            \
        tmp0 = _mm256_shuffle_pd((row0),(row1), 0x0);       \
        tmp2 = _mm256_shuffle_pd((row0),(row1), 0xF);       \
        tmp1 = _mm256_shuffle_pd((row2),(row3), 0x0);       \
        tmp3 = _mm256_shuffle_pd((row2),(row3), 0xF);       \
                                                            \
        (row0) = _mm256_permute2f128_pd(tmp0, tmp1, 0x20);	\
        (row1) = _mm256_permute2f128_pd(tmp2, tmp3, 0x20);  \
        (row2) = _mm256_permute2f128_pd(tmp0, tmp1, 0x31);  \
        (row3) = _mm256_permute2f128_pd(tmp2, tmp3, 0x31);  \
    }

/*VSWAPHL(a, b) pseudo code:
return [ b[0], b[1], a[2], a[3] ]
*/
#  define VSWAPHL(a,b)	\
   _mm256_insertf128_pd(_mm256_castpd128_pd256(_mm256_castpd256_pd128(b)), _mm256_extractf128_pd(a, 1), 1)

/* reverse/flip all floats */
#  define VREV_S(a)    _mm256_insertf128_pd(_mm256_castpd128_pd256(_mm_permute_pd(_mm256_extractf128_pd(a, 1),1)), _mm_permute_pd(_mm256_castpd256_pd128(a), 1), 1)

/* reverse/flip complex floats */
#  define VREV_C(a)    _mm256_insertf128_pd(_mm256_castpd128_pd256(_mm256_extractf128_pd(a, 1)), _mm256_castpd256_pd128(a), 1)

#  define VALIGNED(ptr) ((((uintptr_t)(ptr)) & 0x1F) == 0)

#endif

#endif /* PF_AVX_DBL_H */

