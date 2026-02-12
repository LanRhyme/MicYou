/* Copyright (c) 2013  Julien Pommier ( pommier@modartt.com )
   Copyright (c) 2020  Hayati Ayguen ( h_ayguen@web.de )

   Based on original fortran 77 code from FFTPACKv4 from NETLIB
   (http://www.netlib.org/fftpack), authored by Dr Paul Swarztrauber
   of NCAR, in 1985.

   As confirmed by the NCAR fftpack software curators, the following
   FFTPACKv5 license applies to FFTPACKv4 sources. My changes are
   released under the same terms.

   FFTPACK license:

   http://www.cisl.ucar.edu/css/software/fftpack5/ftpk.html

   Copyright (c) 2004 the University Corporation for Atmospheric
   Research ("UCAR"). All rights reserved. Developed by NCAR's
   Computational and Information Systems Laboratory, UCAR,
   www.cisl.ucar.edu.

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


   PFFFT : a Pretty Fast FFT.

   This file is largerly based on the original FFTPACK implementation, modified in
   order to take advantage of SIMD instructions of modern CPUs.
*/

/*
  ChangeLog: 
  - 2011/10/02, version 1: This is the very first release of this file.
*/

#include "pffft.h"

/* detect compiler flavour */
#if defined(_MSC_VER)
#  define COMPILER_MSVC
#elif defined(__GNUC__)
#  define COMPILER_GCC
#endif

#include <stdlib.h>
#include <stdint.h>
#include <stdio.h>
#include <math.h>
#include <assert.h>

#if defined(COMPILER_GCC)
#  define ALWAYS_INLINE(return_type) inline return_type __attribute__ ((always_inline))
#  define NEVER_INLINE(return_type) return_type __attribute__ ((noinline))
#  define RESTRICT __restrict
#  define VLA_ARRAY_ON_STACK(type__, varname__, size__) type__ varname__[size__];
#elif defined(COMPILER_MSVC)
#  define ALWAYS_INLINE(return_type) __forceinline return_type
#  define NEVER_INLINE(return_type) __declspec(noinline) return_type
#  define RESTRICT __restrict
#  define VLA_ARRAY_ON_STACK(type__, varname__, size__) type__ *varname__ = (type__*)_alloca(size__ * sizeof(type__))
#endif


#ifdef COMPILER_MSVC
#pragma warning( disable : 4244 4305 4204 4456 )
#endif

/* 
   vector support macros: the rest of the code is independant of
   SSE/Altivec/NEON -- adding support for other platforms with 4-element
   vectors should be limited to these macros 
*/
#include "simd/pf_float.h"

/* have code comparable with this definition */
#define SETUP_STRUCT               PFFFT_Setup
#define FUNC_NEW_SETUP             pffft_new_setup
#define FUNC_DESTROY               pffft_destroy_setup
#define FUNC_TRANSFORM_UNORDRD     pffft_transform
#define FUNC_TRANSFORM_ORDERED     pffft_transform_ordered
#define FUNC_ZREORDER              pffft_zreorder
#define FUNC_ZCONVOLVE_ACCUMULATE  pffft_zconvolve_accumulate
#define FUNC_ZCONVOLVE_NO_ACCU     pffft_zconvolve_no_accu

#define FUNC_ALIGNED_MALLOC        pffft_aligned_malloc
#define FUNC_ALIGNED_FREE          pffft_aligned_free
#define FUNC_SIMD_SIZE             pffft_simd_size
#define FUNC_MIN_FFT_SIZE          pffft_min_fft_size
#define FUNC_IS_VALID_SIZE         pffft_is_valid_size
#define FUNC_NEAREST_SIZE          pffft_nearest_transform_size
#define FUNC_SIMD_ARCH             pffft_simd_arch
#define FUNC_VALIDATE_SIMD_A       validate_pffft_simd
#define FUNC_VALIDATE_SIMD_EX      validate_pffft_simd_ex

#define FUNC_CPLX_FINALIZE         pffft_cplx_finalize
#define FUNC_CPLX_PREPROCESS       pffft_cplx_preprocess
#define FUNC_REAL_PREPROCESS_4X4   pffft_real_preprocess_4x4
#define FUNC_REAL_PREPROCESS       pffft_real_preprocess
#define FUNC_REAL_FINALIZE_4X4     pffft_real_finalize_4x4
#define FUNC_REAL_FINALIZE         pffft_real_finalize
#define FUNC_TRANSFORM_INTERNAL    pffft_transform_internal

#define FUNC_COS  cosf
#define FUNC_SIN  sinf


#include "pffft_priv_impl.h"


