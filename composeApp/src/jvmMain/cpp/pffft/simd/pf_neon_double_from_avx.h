/*
 * Copyright (C) 2020. Huawei Technologies Co., Ltd. All rights reserved.

 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at

 * http://www.apache.org/licenses/LICENSE-2.0

 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.

 */

//see https://github.com/kunpengcompute/AvxToNeon

#ifndef PF_NEON_DBL_FROM_AVX_H
#define PF_NEON_DBL_FROM_AVX_H
#include <arm_neon.h>


#if defined(__GNUC__) || defined(__clang__)

#pragma push_macro("FORCE_INLINE")
#define FORCE_INLINE static inline __attribute__((always_inline))

#else

#error "Macro name collisions may happens with unknown compiler"
#ifdef FORCE_INLINE
#undef FORCE_INLINE
#endif

#define FORCE_INLINE static inline

#endif

typedef struct {
    float32x4_t vect_f32[2];
} __m256;

typedef struct {
    float64x2_t vect_f64[2];
} __m256d;

typedef float64x2_t __m128d;

FORCE_INLINE __m256d _mm256_setzero_pd(void)
{
    __m256d ret;
    ret.vect_f64[0] = ret.vect_f64[1] = vdupq_n_f64(0.0);
    return ret;
}

FORCE_INLINE __m256d _mm256_mul_pd(__m256d a, __m256d b)
{
    __m256d res_m256d;
    res_m256d.vect_f64[0] = vmulq_f64(a.vect_f64[0], b.vect_f64[0]);
    res_m256d.vect_f64[1] = vmulq_f64(a.vect_f64[1], b.vect_f64[1]);
    return res_m256d;
}

FORCE_INLINE __m256d _mm256_add_pd(__m256d a, __m256d b)
{
    __m256d res_m256d;
    res_m256d.vect_f64[0] = vaddq_f64(a.vect_f64[0], b.vect_f64[0]);
    res_m256d.vect_f64[1] = vaddq_f64(a.vect_f64[1], b.vect_f64[1]);
    return res_m256d;
}

FORCE_INLINE __m256d _mm256_sub_pd(__m256d a, __m256d b)
{
    __m256d res_m256d;
    res_m256d.vect_f64[0] = vsubq_f64(a.vect_f64[0], b.vect_f64[0]);
    res_m256d.vect_f64[1] = vsubq_f64(a.vect_f64[1], b.vect_f64[1]);
    return res_m256d;
}

FORCE_INLINE __m256d _mm256_set1_pd(double a)
{
    __m256d ret;
    ret.vect_f64[0] = ret.vect_f64[1] = vdupq_n_f64(a);
    return ret;
}

FORCE_INLINE __m256d _mm256_load_pd (double const * mem_addr)
{
    __m256d res;
    res.vect_f64[0] = vld1q_f64((const double *)mem_addr);
    res.vect_f64[1] = vld1q_f64((const double *)mem_addr + 2);
    return res;
}
FORCE_INLINE __m256d _mm256_loadu_pd (double const * mem_addr)
{
    __m256d res;
    res.vect_f64[0] = vld1q_f64((const double *)mem_addr);
    res.vect_f64[1] = vld1q_f64((const double *)mem_addr + 2);
    return res;
}

FORCE_INLINE __m128d _mm256_castpd256_pd128(__m256d a)
{
    return a.vect_f64[0];
}

FORCE_INLINE __m128d _mm256_extractf128_pd (__m256d a, const int imm8)
{
    assert(imm8 >= 0 && imm8 <= 1);
    return a.vect_f64[imm8];
}

FORCE_INLINE __m256d _mm256_castpd128_pd256(__m128d a)
{
    __m256d res;
    res.vect_f64[0] = a;
    return res;
}

#endif /* PF_AVX_DBL_H */

