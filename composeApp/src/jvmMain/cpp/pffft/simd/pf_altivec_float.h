
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

#ifndef PF_ALTIVEC_FLT_H
#define PF_ALTIVEC_FLT_H

/*
   Altivec support macros
*/
#if !defined(PFFFT_SIMD_DISABLE) && (defined(__ppc__) || defined(__ppc64__))
#pragma message( __FILE__ ": ALTIVEC float macros are defined" )
typedef vector float v4sf;

#  define SIMD_SZ 4

typedef union v4sf_union {
  v4sf  v;
  float f[SIMD_SZ];
} v4sf_union;

#  define VREQUIRES_ALIGN 1  /* not sure, if really required */
#  define VARCH "ALTIVEC"
#  define VZERO() ((vector float) vec_splat_u8(0))
#  define VMUL(a,b) vec_madd(a,b, VZERO())
#  define VADD(a,b) vec_add(a,b)
#  define VMADD(a,b,c) vec_madd(a,b,c)
#  define VSUB(a,b) vec_sub(a,b)
inline v4sf ld_ps1(const float *p) { v4sf v=vec_lde(0,p); return vec_splat(vec_perm(v, v, vec_lvsl(0, p)), 0); }
#  define LD_PS1(p) ld_ps1(&p)
#  define INTERLEAVE2(in1, in2, out1, out2) { v4sf tmp__ = vec_mergeh(in1, in2); out2 = vec_mergel(in1, in2); out1 = tmp__; }
#  define UNINTERLEAVE2(in1, in2, out1, out2) {                           \
    vector unsigned char vperm1 =  (vector unsigned char)(0,1,2,3,8,9,10,11,16,17,18,19,24,25,26,27); \
    vector unsigned char vperm2 =  (vector unsigned char)(4,5,6,7,12,13,14,15,20,21,22,23,28,29,30,31); \
    v4sf tmp__ = vec_perm(in1, in2, vperm1); out2 = vec_perm(in1, in2, vperm2); out1 = tmp__; \
  }
#  define VTRANSPOSE4(x0,x1,x2,x3) {              \
    v4sf y0 = vec_mergeh(x0, x2);               \
    v4sf y1 = vec_mergel(x0, x2);               \
    v4sf y2 = vec_mergeh(x1, x3);               \
    v4sf y3 = vec_mergel(x1, x3);               \
    x0 = vec_mergeh(y0, y2);                    \
    x1 = vec_mergel(y0, y2);                    \
    x2 = vec_mergeh(y1, y3);                    \
    x3 = vec_mergel(y1, y3);                    \
  }
#  define VSWAPHL(a,b) vec_perm(a,b, (vector unsigned char)(16,17,18,19,20,21,22,23,8,9,10,11,12,13,14,15))
#  define VALIGNED(ptr) ((((uintptr_t)(ptr)) & 0xF) == 0)

#endif

#endif /* PF_SSE1_FLT_H */

