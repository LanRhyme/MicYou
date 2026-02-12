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

#ifndef PF_SSE2_DBL_H
#define PF_SSE2_DBL_H

//detect sse2 support under MSVC
#if defined ( _M_IX86_FP )
#  if _M_IX86_FP == 2
#    if !defined(__SSE2__)
#      define __SSE2__
#    endif
#  endif
#endif

/*
  SSE2 64bit support macros
*/
#if !defined(SIMD_SZ) && !defined(PFFFT_SIMD_DISABLE) && (defined( __SSE4_2__ ) |  defined( __SSE4_1__ ) || defined( __SSE3__ ) || defined( __SSE2__ ) || defined ( __x86_64__ ) || defined( _M_AMD64 ) || defined( _M_X64 ) || defined( __amd64 ))
#pragma message (__FILE__ ": SSE2 double macros are defined" )

#include <emmintrin.h>

typedef struct {
    __m128d d128[2];
} m256d;

typedef m256d v4sf;

#  define SIMD_SZ 4

typedef union v4sf_union {
  v4sf  v;
  double f[SIMD_SZ];
} v4sf_union;


#if defined(__GNUC__) || defined(__clang__)

#pragma push_macro("FORCE_INLINE")
#define FORCE_INLINE static inline __attribute__((always_inline))

#elif defined (_MSC_VER)
#define FORCE_INLINE static __forceinline

#else
#error "Macro name collisions may happens with unknown compiler"
#ifdef FORCE_INLINE
#undef FORCE_INLINE
#endif
#define FORCE_INLINE static inline
#endif

FORCE_INLINE m256d mm256_setzero_pd(void)
{
    m256d ret;
    ret.d128[0] = ret.d128[1] = _mm_setzero_pd();
    return ret;
}

FORCE_INLINE m256d mm256_mul_pd(m256d a, m256d b)
{
    m256d ret;
    ret.d128[0] = _mm_mul_pd(a.d128[0], b.d128[0]);
    ret.d128[1] = _mm_mul_pd(a.d128[1], b.d128[1]);
    return ret;
}

FORCE_INLINE m256d mm256_add_pd(m256d a, m256d b)
{
    m256d ret;
    ret.d128[0] = _mm_add_pd(a.d128[0], b.d128[0]);
    ret.d128[1] = _mm_add_pd(a.d128[1], b.d128[1]);
    return ret;
}

FORCE_INLINE m256d mm256_sub_pd(m256d a, m256d b)
{
    m256d ret;
    ret.d128[0] = _mm_sub_pd(a.d128[0], b.d128[0]);
    ret.d128[1] = _mm_sub_pd(a.d128[1], b.d128[1]);
    return ret;
}

FORCE_INLINE m256d mm256_set1_pd(double a)
{
    m256d ret;
    ret.d128[0] = ret.d128[1] = _mm_set1_pd(a);
    return ret;
}

FORCE_INLINE m256d mm256_load_pd (double const * mem_addr)
{
    m256d res;
    res.d128[0] = _mm_load_pd((const double *)mem_addr);
    res.d128[1] = _mm_load_pd((const double *)mem_addr + 2);
    return res;
}
FORCE_INLINE m256d mm256_loadu_pd (double const * mem_addr)
{
    m256d res;
    res.d128[0] = _mm_loadu_pd((const double *)mem_addr);
    res.d128[1] = _mm_loadu_pd((const double *)mem_addr + 2);
    return res;
}


#  define VARCH "SSE2"
#  define VREQUIRES_ALIGN 1
#  define VZERO() mm256_setzero_pd()
#  define VMUL(a,b) mm256_mul_pd(a,b)
#  define VADD(a,b) mm256_add_pd(a,b)
#  define VMADD(a,b,c) mm256_add_pd(mm256_mul_pd(a,b), c)
#  define VSUB(a,b) mm256_sub_pd(a,b)
#  define LD_PS1(p) mm256_set1_pd(p)
#  define VLOAD_UNALIGNED(ptr)  mm256_loadu_pd(ptr)
#  define VLOAD_ALIGNED(ptr)    mm256_load_pd(ptr)


FORCE_INLINE __m128d mm256_castpd256_pd128(m256d a)
{
    return a.d128[0];
}

FORCE_INLINE __m128d mm256_extractf128_pd (m256d a, const int imm8)
{
    assert(imm8 >= 0 && imm8 <= 1);
    return a.d128[imm8];
}
FORCE_INLINE m256d mm256_insertf128_pd_1(m256d a, __m128d b)
{
    m256d res;
    res.d128[0] = a.d128[0];
    res.d128[1] = b;
    return res;
}
FORCE_INLINE m256d mm256_castpd128_pd256(__m128d a)
{
    m256d res;
    res.d128[0] = a;
    return res;
}

FORCE_INLINE m256d mm256_shuffle_pd_00(m256d a, m256d b)
{
    m256d res;
    res.d128[0] = _mm_shuffle_pd(a.d128[0],b.d128[0],0);
    res.d128[1] = _mm_shuffle_pd(a.d128[1],b.d128[1],0);
    return res;
}

FORCE_INLINE m256d mm256_shuffle_pd_11(m256d a, m256d b)
{
    m256d res;
    res.d128[0] = _mm_shuffle_pd(a.d128[0],b.d128[0], 3);
    res.d128[1] = _mm_shuffle_pd(a.d128[1],b.d128[1], 3);
    return res;
}

FORCE_INLINE m256d mm256_permute2f128_pd_0x20(m256d a, m256d b) {
    m256d res;
    res.d128[0] = a.d128[0];
    res.d128[1] = b.d128[0];
    return res;
}


FORCE_INLINE m256d mm256_permute2f128_pd_0x31(m256d a, m256d b)
{
    m256d res;
    res.d128[0] = a.d128[1];
    res.d128[1] = b.d128[1];
    return res;
}

FORCE_INLINE m256d mm256_reverse(m256d x)
{
    m256d res;
    res.d128[0] = _mm_shuffle_pd(x.d128[1],x.d128[1],1);
    res.d128[1] = _mm_shuffle_pd(x.d128[0],x.d128[0],1);
    return res;
}

/* INTERLEAVE2 (in1, in2, out1, out2) pseudo code:
out1 = [ in1[0], in2[0], in1[1], in2[1] ]
out2 = [ in1[2], in2[2], in1[3], in2[3] ]
*/
#  define INTERLEAVE2(in1, in2, out1, out2) {							\
	__m128d low1__ = mm256_castpd256_pd128(in1);						\
	__m128d low2__ = mm256_castpd256_pd128(in2);						\
	__m128d high1__ = mm256_extractf128_pd(in1, 1);					\
	__m128d high2__ = mm256_extractf128_pd(in2, 1);					\
	m256d tmp__ = mm256_insertf128_pd_1(								\
		mm256_castpd128_pd256(_mm_shuffle_pd(low1__, low2__, 0)),		\
		_mm_shuffle_pd(low1__, low2__, 3));								\
	out2 = mm256_insertf128_pd_1(										\
		mm256_castpd128_pd256(_mm_shuffle_pd(high1__, high2__, 0)),	\
		_mm_shuffle_pd(high1__, high2__, 3));							\
	out1 = tmp__;														\
}

/*UNINTERLEAVE2(in1, in2, out1, out2) pseudo code:
out1 = [ in1[0], in1[2], in2[0], in2[2] ]
out2 = [ in1[1], in1[3], in2[1], in2[3] ]
*/
#  define UNINTERLEAVE2(in1, in2, out1, out2) {							\
	__m128d low1__ = mm256_castpd256_pd128(in1);						\
	__m128d low2__ = mm256_castpd256_pd128(in2);						\
	__m128d high1__ = mm256_extractf128_pd(in1, 1);					\
	__m128d high2__ = mm256_extractf128_pd(in2, 1); 					\
	m256d tmp__ = mm256_insertf128_pd_1(								\
		mm256_castpd128_pd256(_mm_shuffle_pd(low1__, high1__, 0)),		\
		_mm_shuffle_pd(low2__, high2__, 0));							\
	out2 = mm256_insertf128_pd_1(										\
		mm256_castpd128_pd256(_mm_shuffle_pd(low1__, high1__, 3)),		\
		_mm_shuffle_pd(low2__, high2__, 3));							\
	out1 = tmp__;														\
}

#  define VTRANSPOSE4(row0, row1, row2, row3) {							\
        m256d tmp3, tmp2, tmp1, tmp0;                     			\
                                                            			\
        tmp0 = mm256_shuffle_pd_00((row0),(row1));       				\
        tmp2 = mm256_shuffle_pd_11((row0),(row1));       				\
        tmp1 = mm256_shuffle_pd_00((row2),(row3));       				\
        tmp3 = mm256_shuffle_pd_11((row2),(row3));       				\
                                                            			\
        (row0) = mm256_permute2f128_pd_0x20(tmp0, tmp1);			    \
        (row1) = mm256_permute2f128_pd_0x20(tmp2, tmp3); 		        \
        (row2) = mm256_permute2f128_pd_0x31(tmp0, tmp1); 		        \
        (row3) = mm256_permute2f128_pd_0x31(tmp2, tmp3); 		        \
    }

/*VSWAPHL(a, b) pseudo code:
return [ b[0], b[1], a[2], a[3] ]
*/
#  define VSWAPHL(a,b)	\
   mm256_insertf128_pd_1(mm256_castpd128_pd256(mm256_castpd256_pd128(b)), mm256_extractf128_pd(a, 1))

/* reverse/flip all floats */
#  define VREV_S(a)   mm256_reverse(a)

/* reverse/flip complex floats */
#  define VREV_C(a)    mm256_insertf128_pd_1(mm256_castpd128_pd256(mm256_extractf128_pd(a, 1)), mm256_castpd256_pd128(a))

#  define VALIGNED(ptr) ((((uintptr_t)(ptr)) & 0x1F) == 0)

#endif
#endif
