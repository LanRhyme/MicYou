
/* Copyright (c) 2013  Julien Pommier ( pommier@modartt.com )
   Copyright (c) 2020  Hayati Ayguen ( h_ayguen@web.de )

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

#ifndef PF_SCAL_FLT_H
#define PF_SCAL_FLT_H

/*
  fallback mode(s) for situations where SSE/AVX/NEON/Altivec are not available, use scalar mode instead
*/

#if !defined(SIMD_SZ) && defined(PFFFT_SCALVEC_ENABLED)
#pragma message( __FILE__ ": float SCALAR4 macros are defined" )

typedef struct {
  vsfscalar a;
  vsfscalar b;
  vsfscalar c;
  vsfscalar d;
} v4sf;

#  define SIMD_SZ 4

typedef union v4sf_union {
  v4sf  v;
  vsfscalar f[SIMD_SZ];
} v4sf_union;

#  define VARCH "4xScalar"
#  define VREQUIRES_ALIGN 0

  static ALWAYS_INLINE(v4sf) VZERO() {
    v4sf r = { 0.f, 0.f, 0.f, 0.f };
    return r;
  }

  static ALWAYS_INLINE(v4sf) VMUL(v4sf A, v4sf B) {
    v4sf r = { A.a * B.a, A.b * B.b, A.c * B.c, A.d * B.d };
    return r;
  }

  static ALWAYS_INLINE(v4sf) VADD(v4sf A, v4sf B) {
    v4sf r = { A.a + B.a, A.b + B.b, A.c + B.c, A.d + B.d };
    return r;
  }

  static ALWAYS_INLINE(v4sf) VMADD(v4sf A, v4sf B, v4sf C) {
    v4sf r = { A.a * B.a + C.a, A.b * B.b + C.b, A.c * B.c + C.c, A.d * B.d + C.d };
    return r;
  }

  static ALWAYS_INLINE(v4sf) VSUB(v4sf A, v4sf B) {
    v4sf r = { A.a - B.a, A.b - B.b, A.c - B.c, A.d - B.d };
    return r;
  }

  static ALWAYS_INLINE(v4sf) LD_PS1(vsfscalar v) {
    v4sf r = { v, v, v, v };
    return r;
  }

#  define VLOAD_UNALIGNED(ptr)  (*((v4sf*)(ptr)))

#  define VLOAD_ALIGNED(ptr)    (*((v4sf*)(ptr)))

#  define VALIGNED(ptr) ((((uintptr_t)(ptr)) & (sizeof(v4sf)-1) ) == 0)


  /* INTERLEAVE2() */
  #define INTERLEAVE2( A, B, C, D) \
  do { \
    v4sf Cr = { A.a, B.a, A.b, B.b }; \
    v4sf Dr = { A.c, B.c, A.d, B.d }; \
    C = Cr; \
    D = Dr; \
  } while (0)


  /* UNINTERLEAVE2() */
  #define UNINTERLEAVE2(A, B, C, D) \
  do { \
    v4sf Cr = { A.a, A.c, B.a, B.c }; \
    v4sf Dr = { A.b, A.d, B.b, B.d }; \
    C = Cr; \
    D = Dr; \
  } while (0)


  /* VTRANSPOSE4() */
  #define VTRANSPOSE4(A, B, C, D) \
  do { \
    v4sf Ar = { A.a, B.a, C.a, D.a }; \
    v4sf Br = { A.b, B.b, C.b, D.b }; \
    v4sf Cr = { A.c, B.c, C.c, D.c }; \
    v4sf Dr = { A.d, B.d, C.d, D.d }; \
    A = Ar; \
    B = Br; \
    C = Cr; \
    D = Dr; \
  } while (0)


  /* VSWAPHL() */
  static ALWAYS_INLINE(v4sf) VSWAPHL(v4sf A, v4sf B) {
    v4sf r = { B.a, B.b, A.c, A.d };
    return r;
  }


  /* reverse/flip all floats */
  static ALWAYS_INLINE(v4sf) VREV_S(v4sf A) {
    v4sf r = { A.d, A.c, A.b, A.a };
    return r;
  }

  /* reverse/flip complex floats */
  static ALWAYS_INLINE(v4sf) VREV_C(v4sf A) {
    v4sf r = { A.c, A.d, A.a, A.b };
    return r;
  }

#else
/* #pragma message( __FILE__ ": float SCALAR4 macros are not defined" ) */
#endif


#if !defined(SIMD_SZ)
#pragma message( __FILE__ ": float SCALAR1 macros are defined" )
typedef vsfscalar v4sf;

#  define SIMD_SZ 1

typedef union v4sf_union {
  v4sf  v;
  vsfscalar f[SIMD_SZ];
} v4sf_union;

#  define VARCH "Scalar"
#  define VREQUIRES_ALIGN 0
#  define VZERO() 0.f
#  define VMUL(a,b) ((a)*(b))
#  define VADD(a,b) ((a)+(b))
#  define VMADD(a,b,c) ((a)*(b)+(c))
#  define VSUB(a,b) ((a)-(b))
#  define LD_PS1(p) (p)
#  define VLOAD_UNALIGNED(ptr)  (*(ptr))
#  define VLOAD_ALIGNED(ptr)    (*(ptr))
#  define VALIGNED(ptr) ((((uintptr_t)(ptr)) & (sizeof(vsfscalar)-1) ) == 0)

#else
/* #pragma message( __FILE__ ": float SCALAR1 macros are not defined" ) */
#endif


#endif /* PF_SCAL_FLT_H */

