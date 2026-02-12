
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

#ifndef PF_SSE1_FLT_H
#define PF_SSE1_FLT_H

/*
  SSE1 support macros
*/
#if !defined(SIMD_SZ) && !defined(PFFFT_SIMD_DISABLE) && (defined(__x86_64__) || defined(_M_X64) || defined(__i386__) || defined(i386) || defined(_M_IX86))
#pragma message( __FILE__ ": SSE1 float macros are defined" )

#include <xmmintrin.h>
typedef __m128 v4sf;

/* 4 floats by simd vector -- this is pretty much hardcoded in the preprocess/finalize functions
 *  anyway so you will have to work if you want to enable AVX with its 256-bit vectors. */
#  define SIMD_SZ 4

typedef union v4sf_union {
  v4sf  v;
  float f[SIMD_SZ];
} v4sf_union;

#  define VARCH "SSE1"
#  define VREQUIRES_ALIGN 1
#  define VZERO() _mm_setzero_ps()
#  define VMUL(a,b) _mm_mul_ps(a,b)
#  define VADD(a,b) _mm_add_ps(a,b)
#  define VMADD(a,b,c) _mm_add_ps(_mm_mul_ps(a,b), c)
#  define VSUB(a,b) _mm_sub_ps(a,b)
#  define LD_PS1(p) _mm_set1_ps(p)
#  define VLOAD_UNALIGNED(ptr)  _mm_loadu_ps(ptr)
#  define VLOAD_ALIGNED(ptr)    _mm_load_ps(ptr)

#  define INTERLEAVE2(in1, in2, out1, out2) { v4sf tmp__ = _mm_unpacklo_ps(in1, in2); out2 = _mm_unpackhi_ps(in1, in2); out1 = tmp__; }
#  define UNINTERLEAVE2(in1, in2, out1, out2) { v4sf tmp__ = _mm_shuffle_ps(in1, in2, _MM_SHUFFLE(2,0,2,0)); out2 = _mm_shuffle_ps(in1, in2, _MM_SHUFFLE(3,1,3,1)); out1 = tmp__; }
#  define VTRANSPOSE4(x0,x1,x2,x3) _MM_TRANSPOSE4_PS(x0,x1,x2,x3)
#  define VSWAPHL(a,b) _mm_shuffle_ps(b, a, _MM_SHUFFLE(3,2,1,0))

/* reverse/flip all floats */
#  define VREV_S(a)    _mm_shuffle_ps(a, a, _MM_SHUFFLE(0,1,2,3))
/* reverse/flip complex floats */
#  define VREV_C(a)    _mm_shuffle_ps(a, a, _MM_SHUFFLE(1,0,3,2))

#  define VALIGNED(ptr) ((((uintptr_t)(ptr)) & 0xF) == 0)

#else
/* #pragma message( __FILE__ ": SSE1 float macros are not defined" ) */
#endif

#endif /* PF_SSE1_FLT_H */

