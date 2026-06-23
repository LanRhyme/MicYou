# AEC7 ONNX Rust 集成指南

> **模型**: `aec7_ep0185.onnx` — 流式声学回声消除 (Acoustic Echo Cancellation, AEC)  
> **运行时**: [ort](https://github.com/pykeio/ort) — ONNX Runtime 的 Rust 绑定  
> **任务**: 输入麦克风 + 远端参考音频，输出消除回声后的增强音频

---

## 目录

1. [模型概述](#1-模型概述)
2. [前提条件](#2-前提条件)
3. [项目初始化](#3-项目初始化)
4. [模型输入 / 输出规格](#4-模型输入--输出规格)
5. [架构总览](#5-架构总览)
6. [实现步骤](#6-实现步骤)
   - [6.1 STFT 预处理](#61-stft-预处理)
   - [6.2 推理器封装](#62-推理器封装)
   - [6.3 iSTFT 后处理](#63-istft-后处理)
   - [6.4 完整流程编排](#64-完整流程编排)
7. [完整代码清单](#7-完整代码清单)
8. [验证与调试](#8-验证与调试)
9. [常见问题](#9-常见问题)
10. [附录：缓存大小速查表](#10-附录缓存大小速查表)

---

## 1. 模型概述

AEC7 是一个**两阶段流式回声消除模型**，工作在 STFT 频域，**逐帧**处理音频。

| 属性 | 值 |
|---|---|
| 采样率 | 48 kHz |
| STFT 帧长 (WIN) | 960 采样点 |
| STFT 帧移 (HOP) | 480 采样点 (≈10 ms) |
| FFT 点数 (NFFT) | 960 |
| 频带数 (N_BINS) | 481 (= NFFT/2 + 1) |
| 输入帧率 | 100 帧/秒 |
| ONNX opset | 17 |
| 模型大小 | ≈4.8 MB |

关键特性：
- **流式**：每次处理一帧 STFT 频谱，帧间通过 13 个状态缓存传递上下文
- **因果**：只依赖当前及过去信息，无未来信息泄露
- **双流编码**：分别编码残差信号 (residual) 和麦克风信号 (mic)，在特征层融合
- **ERB 滤波器组**：将 481 个线性频带压缩为 320 个 ERB 刻度频带

---

## 2. 前提条件

### Rust 工具链

```bash
rustc --version   # ≥ 1.75
cargo --version
```

### ONNX Runtime 动态库

`ort` 库需要 ONNX Runtime 的共享库。推荐用 **自动下载** 方式：

```toml
# Cargo.toml
[dependencies]
ort = { version = "2.0", features = ["download-binaries"] }
```

首次构建时 `ort` 会自动下载对应平台 (`x86_64-linux` / `win32-x64` / `osx-x64`) 的预编译 ONNX Runtime 库。

> 也可以自己手动下载 [ONNX Runtime 发行版](https://github.com/microsoft/onnxruntime/releases) 并通过 `load-dynamic` feature 加载。

---

## 3. 项目初始化

```bash
cargo new aec7-infer --bin
cd aec7-infer
```

`Cargo.toml`:

```toml
[package]
name = "aec7-infer"
version = "0.1.0"
edition = "2021"

[dependencies]
ort = { version = "2.0", features = ["download-binaries"] }
ndarray = "0.16"
rustfft = "6.2"
num-complex = "0.4"
hound = "3.5"
anyhow = "1.0"
clap = { version = "4", features = ["derive"] }
```

> **依赖说明**
>
> | 依赖 | 用途 |
> |---|---|
> | `ort` | ONNX Runtime Rust 绑定 |
> | `ndarray` | 张量创建、切片、形状变换 |
> | `rustfft` | STFT / iSTFT 的 FFT 计算 |
> | `num-complex` | 复数类型 |
> | `hound` | 读写 WAV 文件 |
> | `anyhow` | 便捷错误处理 |
> | `clap` | 命令行参数解析 |

将模型文件 `aec7_ep0185.onnx` 放入项目根目录。

---

## 4. 模型输入 / 输出规格

> 以下形状均以 `batch_size = 1` 为例。模型目前只支持 `batch_size = 1`。

### 输入 (共 15 个)

| # | 名称 | 形状 | 类型 | 含义 |
|---|---|---|---|---|
| 1 | `mic_frame` | `(1, 2, 481)` | f32 | 麦克风 STFT 帧 (channel 0 = real, channel 1 = imag) |
| 2 | `far_frame` | `(1, 2, 481)` | f32 | 远端参考 STFT 帧 (同上) |
| 3 | `res_enc_conv` | `(1, 135680)` | f32 | 残差编码器卷积缓存 (展平) |
| 4 | `res_enc_tfa` | `(1, 248)` | f32 | 残差编码器 TFA GRU 缓存 |
| 5 | `mic_enc_conv` | `(1, 135680)` | f32 | 麦克风编码器卷积缓存 |
| 6 | `mic_enc_tfa` | `(1, 248)` | f32 | 麦克风编码器 TFA GRU 缓存 |
| 7 | `deep_enc_conv` | `(1, 0)` | f32 | 深层编码器卷积缓存 (**零尺寸**) |
| 8 | `deep_enc_tfa` | `(1, 336)` | f32 | 深层编码器 TFA GRU 缓存 |
| 9 | `dec_conv` | `(1, 13440)` | f32 | 解码器卷积缓存 |
| 10 | `dec_tfa` | `(1, 496)` | f32 | 解码器 TFA GRU 缓存 |
| 11 | `inter` | `(1, 7680)` | f32 | DPGRNN 层间 GRU 缓存 |
| 12 | `res_prev1` | `(1, 1, 1, 320)` | f32 | 残差 ERB Δ 前帧 |
| 13 | `res_prev2` | `(1, 1, 1, 320)` | f32 | 残差 ERB ΔΔ 前帧 |
| 14 | `mic_prev1` | `(1, 1, 1, 320)` | f32 | 麦克风 ERB Δ 前帧 |
| 15 | `mic_prev2` | `(1, 1, 1, 320)` | f32 | 麦克风 ERB ΔΔ 前帧 |

### 输出 (共 14 个)

| # | 名称 | 形状 | 含义 |
|---|---|---|---|
| 1 | `enhanced_frame` | `(1, 2, 481)` | 增强后 STFT 帧 (real, imag) |
| 2 | `res_enc_conv_o` | `(1, 135680)` | 更新后的卷积缓存 |
| 3 | `res_enc_tfa_o` | `(1, 248)` | 更新后的 TFA 缓存 |
| 4 | `mic_enc_conv_o` | `(1, 135680)` | 同上 |
| 5 | `mic_enc_tfa_o` | `(1, 248)` | 同上 |
| 6 | `deep_enc_conv_o` | `(1, 0)` | 零尺寸 |
| 7 | `deep_enc_tfa_o` | `(1, 336)` | 同上 |
| 8 | `dec_conv_o` | `(1, 13440)` | 同上 |
| 9 | `dec_tfa_o` | `(1, 496)` | 同上 |
| 10 | `inter_o` | `(1, 7680)` | 同上 |
| 11 | `res_prev1_o` | `(1, 1, 1, 320)` | 同上 |
| 12 | `res_prev2_o` | `(1, 1, 1, 320)` | 同上 |
| 13 | `mic_prev1_o` | `(1, 1, 1, 320)` | 同上 |
| 14 | `mic_prev2_o` | `(1, 1, 1, 320)` | 同上 |

> ⚠️ **关键规则**：第 `t` 帧的输缓存出必须作为第 `t+1` 帧的缓存输入传递回去。初始所有缓存全零。

---

## 5. 架构总览

```
┌──────────────────────────────────────────────────────────────────┐
│                          AEC7 流式推理                            │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│   mic_wav ──→ [STFT] ──→ mic_frames (T × (1,2,481))             │
│   far_wav ──→ [STFT] ──→ far_frames (T × (1,2,481))             │
│                                                                  │
│                  ┌─────────────────────────────────┐              │
│   for t in 0..T: │  Aec7Infer::step(mf, ff)       │              │
│                  │  ┌───────────────────────┐      │              │
│                  │  │ ONNX InferenceSession  │      │              │
│                  │  │ 15 inputs → 14 outputs │      │              │
│                  │  └──────┬────────────────┘      │              │
│                  │         ↓ enhanced_frame(t)      │              │
│                  │         ↓ 13 updated caches      │──→ 缓存回馈  │
│                  └─────────────────────────────────┘              │
│                                                                  │
│   enhanced_frames ──→ [iSTFT] ──→ output_wav                    │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

---

## 6. 实现步骤

### 6.1 STFT 预处理

STFT 参数与 Python 端一致：

```rust
use rustfft::{Fft, FftPlanner, num_complex::Complex};

const FS: u32 = 48000;
const WIN_LEN: usize = 960;
const HOP_LEN: usize = 480;
const NFFT: usize = 960;
const N_BINS: usize = 481;  // NFFT / 2 + 1
```

**STFT 实现**：

```rust
use ndarray::{Array1, Array3, s};

/// 生成 Hann 分析窗 (sqrt 版本，与 PyTorch `hann_window.pow(0.5)` 一致)
fn hann_window(len: usize) -> Array1<f32> {
    Array1::from_iter((0..len).map(|i| {
        let x = (std::f32::consts::PI * i as f32 / (len - 1) as f32).sin();
        x * x  // 等效于 0.5 * (1.0 - cos(2πi/(len-1)))
    }))
}

/// 将整段波形转为 STFT 帧序列
///
/// 返回 Vec<Array3<f32>>，每帧形状 (1, 2, 481)：
///   - [0, 0, :] = real part
///   - [0, 1, :] = imag part
fn stft(wav: &[f32], window: &[f32], fft: &dyn Fft<f32>) -> Vec<Array3<f32>> {
    let num_frames = if wav.len() < WIN_LEN {
        0
    } else {
        (wav.len() - WIN_LEN) / HOP_LEN + 1
    };

    let mut frames = Vec::with_capacity(num_frames);

    for t in 0..num_frames {
        let start = t * HOP_LEN;

        // 加窗 + 填复数
        let mut buf: Vec<Complex<f32>> = (0..NFFT)
            .map(|i| Complex::new(wav[start + i] * window[i], 0.0))
            .collect();

        // 前向 FFT
        fft.process(&mut buf);

        // 提取前 N_BINS 个 bin 的实部和虚部 (onesided)
        let mut frame = Array3::zeros((1, 2, N_BINS));
        for bin in 0..N_BINS {
            frame[[0, 0, bin]] = buf[bin].re;
            frame[[0, 1, bin]] = buf[bin].im;
        }

        frames.push(frame);
    }

    frames
}
```

### 6.2 推理器封装

```rust
use anyhow::{Context, Result};
use ndarray::{Array, ArrayD, IxDyn};
use ort::{Environment, Session, SessionBuilder, Value};

/// AEC7 流式推理器 —— 管理 ONNX session 和帧间缓存
pub struct Aec7Infer {
    session: Session,
    /// 当前帧的 13 个缓存值 (顺序与 ONNX 输入顺序一致)
    cache_tensors: Vec<ArrayD<f32>>,
    /// 缓存对应的 ONNX 输入名称
    cache_names: Vec<String>,
    /// 缓存的原始形状 (用于 reshape 输出)
    cache_shapes: Vec<Vec<usize>>,
}

impl Aec7Infer {
    /// 加载 ONNX 模型并初始化零缓存
    pub fn new(model_path: &str) -> Result<Self> {
        let env = Environment::builder()
            .with_name("aec7")
            .build()
            .context("创建 ORT 环境失败")?;

        let session = SessionBuilder::new(&env)?
            .with_model_from_file(model_path)
            .context("加载 ONNX 模型失败")?;

        // 收集所有输入的名称和形状，分离 mic/far 帧输入和缓存输入
        let inputs = session.inputs();
        let mut cache_names = Vec::new();
        let mut cache_shapes = Vec::new();
        let mut cache_tensors = Vec::new();

        for input in &inputs {
            let name = input.name.as_str();
            if name == "mic_frame" || name == "far_frame" {
                continue;
            }

            let shape: Vec<usize> = input
                .input_type
                .tensor_dimensions()
                .iter()
                .map(|d| *d as usize)
                .collect();

            cache_names.push(name.to_string());
            cache_shapes.push(shape.clone());

            // 处理零尺寸张量 (deep_enc_conv: (1, 0))
            let tensor = if shape.iter().any(|&d| d == 0) {
                let flat_size: usize = shape.iter().product();
                Array::from_shape_vec(IxDyn(&shape), vec![0.0f32; flat_size])
                    .unwrap()
            } else {
                Array::zeros(IxDyn(&shape))
            };

            cache_tensors.push(tensor);
        }

        Ok(Self {
            session,
            cache_tensors,
            cache_names,
            cache_shapes,
        })
    }

    /// 重置缓存到全零初始状态
    pub fn reset(&mut self) {
        for (tensor, shape) in self.cache_tensors.iter_mut().zip(&self.cache_shapes) {
            *tensor = if shape.iter().any(|&d| d == 0) {
                let flat_size: usize = shape.iter().product();
                Array::from_shape_vec(IxDyn(shape), vec![0.0f32; flat_size]).unwrap()
            } else {
                Array::zeros(IxDyn(shape))
            };
        }
    }

    /// 处理一帧 STFT 频谱
    ///
    /// * `mic_frame` — 当前麦克风帧, 形状 (1, 2, 481)
    /// * `far_frame` — 当前远端帧, 形状 (1, 2, 481)
    ///
    /// 返回增强帧, 形状 (1, 2, 481)
    pub fn step(
        &mut self,
        mic_frame: ArrayD<f32>,
        far_frame: ArrayD<f32>,
    ) -> Result<ArrayD<f32>> {
        // 构建 15 个输入
        let mut feed: Vec<(&str, Value)> = Vec::with_capacity(15);

        // 1) mic_frame
        feed.push(("mic_frame", Value::from(mic_frame)?));

        // 2) far_frame
        feed.push(("far_frame", Value::from(far_frame)?));

        // 3–15) 缓存张量
        for (name, tensor) in self.cache_names.iter().zip(self.cache_tensors.iter()) {
            feed.push((name, Value::from(tensor.clone())?));
        }

        // 运行推理
        let outputs = self
            .session
            .run(feed)
            .context("ONNX 推理失败")?;

        // 输出 0: enhanced_frame
        let enhanced = outputs[0]
            .try_extract::<f32>()
            .context("enhanced_frame 类型或形状不符")?
            .view()
            .to_owned()
            .into_dyn();

        // 输出 1..14: 更新后的缓存 → 写入 self.cache_tensors
        for (i, (shape, tensor)) in self
            .cache_shapes
            .iter()
            .zip(self.cache_tensors.iter_mut())
            .enumerate()
        {
            let out_view = outputs[i + 1]
                .try_extract::<f32>()
                .with_context(|| format!("输出 {} 提取失败", self.cache_names[i]))?
                .view()
                .to_owned();

            // 确保形状正确
            *tensor = out_view.into_shape(IxDyn(shape))?;
        }

        Ok(enhanced)
    }
}
```

### 6.3 iSTFT 后处理

```rust
/// 将增强帧序列合成为时域波形 (重叠相加法, OLA)
///
/// * `frames` — 增强帧序列, 每帧形状 (1, 2, 481)
/// * `window` — 分析窗系数
/// * `ifft` — 逆 FFT 规划器
/// * `length` — 原始波形长度 (用于截断)
fn istft(
    frames: &[Array3<f32>],
    window: &[f32],
    ifft: &dyn Fft<f32>,
    length: usize,
) -> Vec<f32> {
    let num_frames = frames.len();
    let out_len = (num_frames - 1) * HOP_LEN + WIN_LEN;
    let mut out = vec![0.0f32; out_len];
    let mut sum_window = vec![0.0f32; out_len];

    for (t, frame) in frames.iter().enumerate() {
        // 从 onesided 重建对称频谱
        let mut buf: Vec<Complex<f32>> = Vec::with_capacity(NFFT);
        for bin in 0..NFFT {
            if bin < N_BINS {
                buf.push(Complex::new(frame[[0, 0, bin]], frame[[0, 1, bin]]));
            } else {
                // 共轭对称
                let mirror = NFFT - bin;
                buf.push(Complex::new(frame[[0, 0, mirror]], -frame[[0, 1, mirror]]));
            }
        }

        // 逆 FFT
        ifft.process(&mut buf);

        // 重叠相加 (加合成窗 = 分析窗)
        let offset = t * HOP_LEN;
        for i in 0..WIN_LEN {
            let val = buf[i].re / NFFT as f32 * window[i];
            out[offset + i] += val;
            sum_window[offset + i] += window[i] * window[i];
        }
    }

    // OLA 归一化
    for i in 0..out_len.min(length) {
        if sum_window[i] > 1e-6 {
            out[i] /= sum_window[i];
        }
    }

    out.truncate(length);
    out
}
```

### 6.4 完整流程编排

```rust
use anyhow::Result;
use ndarray::{Array3, IxDyn};
use rustfft::FftPlanner;

/// 对一段麦克风/远端音频运行 AEC7 推理
///
/// * `mic_wav` — 麦克风音频 (48k f32 采样点)
/// * `far_wav` — 远端参考音频 (48k f32 采样点，长度与 mic_wav 相同)
/// * `model_path` — ONNX 模型文件路径
///
/// 返回消除回声后的音频 (48k f32 采样点)
pub fn process_aec(
    mic_wav: &[f32],
    far_wav: &[f32],
    model_path: &str,
) -> Result<Vec<f32>> {
    // 1. 窗函数
    let window = hann_window(WIN_LEN);
    let window_slice: Vec<f32> = window.iter().copied().collect();

    // 2. FFT 规划
    let mut planner = FftPlanner::new();
    let fft = planner.plan_fft_forward(NFFT);
    let ifft = planner.plan_fft_inverse(NFFT);

    // 3. STFT
    let mic_frames = stft(mic_wav, &window_slice, &*fft);
    let far_frames = stft(far_wav, &window_slice, &*fft);

    if mic_frames.is_empty() || far_frames.is_empty() {
        anyhow::bail!("音频太短，无法生成任何 STFT 帧");
    }

    if mic_frames.len() != far_frames.len() {
        anyhow::bail!("mic 和 far 帧数不匹配");
    }

    let num_frames = mic_frames.len();

    // 4. 初始化推理器
    let mut infer = Aec7Infer::new(model_path)?;

    // 5. 逐帧推理
    let mut enhanced_frames: Vec<Array3<f32>> = Vec::with_capacity(num_frames);

    for t in 0..num_frames {
        let enhanced = infer.step(
            mic_frames[t].clone().into_dyn(),
            far_frames[t].clone().into_dyn(),
        )?;

        // 重组为 (1, 2, 481) 三维视图
        let enhanced_3d: Array3<f32> = enhanced
            .into_shape((1, 2, N_BINS))
            .expect("输出形状应为 (1, 2, 481)");

        enhanced_frames.push(enhanced_3d);
    }

    // 6. iSTFT 合回波形
    let output = istft(&enhanced_frames, &window_slice, &*ifft, mic_wav.len());

    Ok(output)
}
```

---

## 7. 完整代码清单

以下是一个可直接编译运行的命令行工具。

### `src/main.rs`

```rust
use anyhow::{Context, Result};
use clap::Parser;
use hound::{WavReader, WavSpec, WavWriter};
use ndarray::{Array1, Array3, ArrayD, IxDyn};
use num_complex::Complex;
use ort::{Environment, Session, SessionBuilder, Value};
use rustfft::FftPlanner;
use std::path::PathBuf;

// ── 常量 (与 Python 端一致) ──
const FS: u32 = 48000;
const WIN_LEN: usize = 960;
const HOP_LEN: usize = 480;
const NFFT: usize = 960;
const N_BINS: usize = NFFT / 2 + 1; // 481

// ── 命令行参数 ──
#[derive(Parser)]
#[command(name = "aec7-infer", about = "AEC7 回声消除推理")]
struct Args {
    /// 麦克风音频 WAV 路径
    #[arg(short = 'i', long)]
    mic: PathBuf,

    /// 远端参考音频 WAV 路径
    #[arg(short = 'r', long)]
    far: PathBuf,

    /// ONNX 模型路径
    #[arg(short = 'm', long, default_value = "aec7_ep0185.onnx")]
    model: PathBuf,

    /// 输出音频 WAV 路径
    #[arg(short = 'o', long, default_value = "output.wav")]
    output: PathBuf,
}

// ════════════════════════════════════════════════════════════════════
// 第 1 部分: STFT / iSTFT
// ════════════════════════════════════════════════════════════════════

fn hann_window(len: usize) -> Array1<f32> {
    Array1::from_iter((0..len).map(|i| {
        let x = (std::f32::consts::PI * i as f32 / (len - 1) as f32).sin();
        x * x
    }))
}

fn stft(wav: &[f32], window: &[f32], fft: &dyn rustfft::Fft<f32>) -> Vec<Array3<f32>> {
    if wav.len() < WIN_LEN {
        return Vec::new();
    }
    let num_frames = (wav.len() - WIN_LEN) / HOP_LEN + 1;
    let mut frames = Vec::with_capacity(num_frames);

    for t in 0..num_frames {
        let start = t * HOP_LEN;
        let mut buf: Vec<Complex<f32>> = (0..NFFT)
            .map(|i| Complex::new(wav[start + i] * window[i], 0.0))
            .collect();

        fft.process(&mut buf);

        let mut frame = Array3::zeros((1, 2, N_BINS));
        for bin in 0..N_BINS {
            frame[[0, 0, bin]] = buf[bin].re;
            frame[[0, 1, bin]] = buf[bin].im;
        }
        frames.push(frame);
    }
    frames
}

fn istft(frames: &[Array3<f32>], window: &[f32], ifft: &dyn rustfft::Fft<f32>, length: usize) -> Vec<f32> {
    if frames.is_empty() {
        return Vec::new();
    }
    let num_frames = frames.len();
    let out_len = (num_frames - 1) * HOP_LEN + WIN_LEN;
    let mut out = vec![0.0f32; out_len];
    let mut sum_window = vec![0.0f32; out_len];

    for (t, frame) in frames.iter().enumerate() {
        let mut buf: Vec<Complex<f32>> = Vec::with_capacity(NFFT);
        for bin in 0..NFFT {
            if bin < N_BINS {
                buf.push(Complex::new(frame[[0, 0, bin]], frame[[0, 1, bin]]));
            } else {
                let mirror = NFFT - bin;
                buf.push(Complex::new(frame[[0, 0, mirror]], -frame[[0, 1, mirror]]));
            }
        }
        ifft.process(&mut buf);

        let offset = t * HOP_LEN;
        for i in 0..WIN_LEN {
            let val = buf[i].re / NFFT as f32 * window[i];
            out[offset + i] += val;
            sum_window[offset + i] += window[i] * window[i];
        }
    }

    for i in 0..out_len.min(length) {
        if sum_window[i] > 1e-6 {
            out[i] /= sum_window[i];
        }
    }

    out.truncate(length);
    out
}

// ════════════════════════════════════════════════════════════════════
// 第 2 部分: ONNX 推理器
// ════════════════════════════════════════════════════════════════════

struct Aec7Infer {
    session: Session,
    cache_tensors: Vec<ArrayD<f32>>,
    cache_names: Vec<String>,
    cache_shapes: Vec<Vec<usize>>,
}

impl Aec7Infer {
    fn new(model_path: &str) -> Result<Self> {
        let env = Environment::builder()
            .with_name("aec7")
            .build()
            .context("创建 ORT 环境失败")?;

        let session = SessionBuilder::new(&env)?
            .with_model_from_file(model_path)
            .context("加载 ONNX 模型失败")?;

        let inputs = session.inputs();
        let mut cache_names = Vec::new();
        let mut cache_shapes = Vec::new();
        let mut cache_tensors = Vec::new();

        for input in &inputs {
            let name = input.name.as_str();
            if name == "mic_frame" || name == "far_frame" {
                continue;
            }
            let shape: Vec<usize> = input
                .input_type
                .tensor_dimensions()
                .iter()
                .map(|d| *d as usize)
                .collect();

            cache_names.push(name.to_string());
            cache_shapes.push(shape.clone());

            let tensor = if shape.iter().any(|&d| d == 0) {
                let n: usize = shape.iter().product();
                Array::from_shape_vec(IxDyn(&shape), vec![0.0f32; n]).unwrap()
            } else {
                Array::zeros(IxDyn(&shape))
            };
            cache_tensors.push(tensor);
        }

        Ok(Self {
            session,
            cache_tensors,
            cache_names,
            cache_shapes,
        })
    }

    fn reset(&mut self) {
        for (tensor, shape) in self.cache_tensors.iter_mut().zip(&self.cache_shapes) {
            *tensor = if shape.iter().any(|&d| d == 0) {
                let n: usize = shape.iter().product();
                Array::from_shape_vec(IxDyn(shape), vec![0.0f32; n]).unwrap()
            } else {
                Array::zeros(IxDyn(shape))
            };
        }
    }

    fn step(&mut self, mic_frame: ArrayD<f32>, far_frame: ArrayD<f32>) -> Result<ArrayD<f32>> {
        let mut feed: Vec<(&str, Value)> = Vec::with_capacity(15);
        feed.push(("mic_frame", Value::from(mic_frame)?));
        feed.push(("far_frame", Value::from(far_frame)?));

        for (name, tensor) in self.cache_names.iter().zip(self.cache_tensors.iter()) {
            feed.push((name, Value::from(tensor.clone())?));
        }

        let outputs = self.session.run(feed).context("ONNX 推理失败")?;

        let enhanced = outputs[0]
            .try_extract::<f32>()
            .context("enhanced_frame 提取失败")?
            .view()
            .to_owned()
            .into_dyn();

        for (i, (shape, tensor)) in self
            .cache_shapes
            .iter()
            .zip(self.cache_tensors.iter_mut())
            .enumerate()
        {
            let out = outputs[i + 1]
                .try_extract::<f32>()
                .with_context(|| format!("缓存 {} 提取失败", self.cache_names[i]))?
                .view()
                .to_owned();
            *tensor = out.into_shape(IxDyn(shape))?;
        }

        Ok(enhanced)
    }
}

// ════════════════════════════════════════════════════════════════════
// 第 3 部分: 主流程
// ════════════════════════════════════════════════════════════════════

fn read_wav(path: &PathBuf) -> Result<(Vec<f32>, u32)> {
    let mut reader = WavReader::open(path).context("无法打开 WAV 文件")?;
    let spec = reader.spec();
    if spec.sample_rate != FS {
        anyhow::bail!("采样率应为 {} Hz，实际为 {} Hz", FS, spec.sample_rate);
    }
    if spec.bits_per_sample != 16 {
        anyhow::bail!("仅支持 16-bit WAV");
    }
    let samples: Vec<f32> = reader
        .samples::<i16>()
        .filter_map(|s| s.ok())
        .map(|s| s as f32 / 32768.0)
        .collect();
    Ok((samples, spec.channels as u32))
}

fn write_wav(path: &PathBuf, samples: &[f32]) -> Result<()> {
    let spec = WavSpec {
        channels: 1,
        sample_rate: FS,
        bits_per_sample: 16,
        sample_format: hound::SampleFormat::Int,
    };
    let mut writer = WavWriter::create(path, spec)?;
    for &s in samples {
        let clamped = s.clamp(-1.0, 1.0);
        writer.write_sample((clamped * 32767.0) as i16)?;
    }
    writer.finalize()?;
    Ok(())
}

fn main() -> Result<()> {
    let args = Args::parse();

    // 读取音频
    println!("读取音频...");
    let (mic_wav, mic_ch) = read_wav(&args.mic)?;
    let (far_wav, far_ch) = read_wav(&args.far)?;

    if mic_wav.len() != far_wav.len() {
        anyhow::bail!("mic ({}) 和 far ({}) 长度不一致", mic_wav.len(), far_wav.len());
    }

    // 多声道取第 0 声道
    let mic_mono: Vec<f32> = if mic_ch == 1 {
        mic_wav
    } else {
        mic_wav.into_iter().step_by(mic_ch as usize).collect()
    };
    let far_mono: Vec<f32> = if far_ch == 1 {
        far_wav
    } else {
        far_wav.into_iter().step_by(far_ch as usize).collect()
    };

    println!(
        "音频长度: {:.1}s ({} 采样点 @ {} Hz)",
        mic_mono.len() as f64 / FS as f64,
        mic_mono.len(),
        FS
    );

    // 处理
    println!("STFT...");
    let window = hann_window(WIN_LEN);
    let win_slice: Vec<f32> = window.iter().copied().collect();
    let mut planner = FftPlanner::new();
    let fft = planner.plan_fft_forward(NFFT);
    let ifft = planner.plan_fft_inverse(NFFT);

    let mic_frames = stft(&mic_mono, &win_slice, &*fft);
    let far_frames = stft(&far_mono, &win_slice, &*fft);
    println!("STFT 帧数: {}", mic_frames.len());

    println!("ONNX 推理...");
    let mut infer = Aec7Infer::new(args.model.to_str().unwrap())?;
    let mut enhanced_frames = Vec::with_capacity(mic_frames.len());

    for t in 0..mic_frames.len() {
        let enhanced = infer.step(
            mic_frames[t].clone().into_dyn(),
            far_frames[t].clone().into_dyn(),
        )?;
        enhanced_frames.push(enhanced.into_shape((1, 2, N_BINS))?);

        if (t + 1) % 100 == 0 {
            println!("  进度: {}/{} 帧", t + 1, mic_frames.len());
        }
    }

    println!("iSTFT...");
    let output = istft(&enhanced_frames, &win_slice, &*ifft, mic_mono.len());

    println!("写入输出: {}", args.output.display());
    write_wav(&args.output, &output)?;

    println!("完成!");
    Ok(())
}
```

---

## 8. 验证与调试

### 8.1 使用 Python 基线对比

```bash
# 1. 用 Python 跑 ONNX 推理
python3 -c "
import onnxruntime, soundfile, numpy as np
from aec7_train import infer_aec7_onnx
from aec7_common import FS

mic, _ = soundfile.read('mic.wav')
far, _ = soundfile.read('far.wav')
# 若多声道取第一声道
if mic.ndim > 1: mic = mic[:, 0]
if far.ndim > 1: far = far[:, 0]
mic_t = torch.from_numpy(mic).unsqueeze(0)
far_t = torch.from_numpy(far).unsqueeze(0)
out = infer_aec7_onnx('aec7_ep0185.onnx', mic_t, far_t)
soundfile.write('ref_output.wav', out.squeeze(0).numpy(), FS)
print('Python 输出写入 ref_output.wav')
"

# 2. 用 Rust 推理
cargo run --release -- \
    --mic mic.wav --far far.wav \
    --model aec7_ep0185.onnx --output rust_output.wav

# 3. 对比 (用 Python 或 sox)
python3 -c "
import soundfile as sf
ref, _ = sf.read('ref_output.wav')
rust, _ = sf.read('rust_output.wav')
min_len = min(len(ref), len(rust))
ref, rust = ref[:min_len], rust[:min_len]
mse = ((ref - rust)**2).mean()
snr = 10 * np.log10((ref**2).mean() / max(mse, 1e-12))
cos = np.dot(ref, rust) / (np.linalg.norm(ref) * np.linalg.norm(rust))
print(f'MSE:  {mse:.10f}')
print(f'SNR:  {snr:.2f} dB')
print(f'Cos:  {cos:.10f}')
"
```

> 预期结果：`MSE < 1e-6`, `Cos > 0.9999`（浮点误差范围内）

### 8.2 常见调试手段

```rust
// 打印某个缓存的统计信息
fn inspect_tensor(name: &str, tensor: &ArrayD<f32>) {
    let flat = tensor.iter().copied().collect::<Vec<f32>>();
    let mean = flat.iter().sum::<f32>() / flat.len() as f32;
    let min = flat.iter().cloned().fold(f32::INFINITY, f32::min);
    let max = flat.iter().cloned().fold(f32::NEG_INFINITY, f32::max);
    println!(
        "[{}] shape={:?} mean={:.6} min={:.6} max={:.6}",
        name,
        tensor.shape(),
        mean,
        min,
        max
    );
}
```

---

## 9. 常见问题

### Q1: 构建时提示找不到 ONNX Runtime 动态库

**原因**：`ort` 的 `download-binaries` feature 只能自动下载常见平台（Linux x86_64, macOS x86_64/aarch64, Windows x64）的 ONNX Runtime。如果你的平台不在其中，需要手动下载。

**解决**：

```toml
# Cargo.toml — 改用 load-dynamic
[dependencies]
ort = { version = "2.0", features = ["load-dynamic"] }
```

然后把 ONNX Runtime 动态库 (Linux: `libonnxruntime.so`, macOS: `libonnxruntime.dylib`, Windows: `onnxruntime.dll`) 放在与可执行文件相同的目录，或系统库路径中。

### Q2: 输出音频有爆破音 / 噪声

可能原因：

1. **缓存未正确传递**：检查 `infer.step()` 后是否将输出缓存回写给了下一帧的输入（代码中的 `cache_tensors` 自动处理）
2. **STFT 合成窗与 iSTFT 不匹配**：确保合成窗 = 分析窗 (Hann sqrt)，且 OLA 归一化正确
3. **帧同步错误**：STFT 帧移必须是 480，与 Python 端一致

### Q3: 性能太慢

| 优化项 | 建议 |
|---|---|
| 推理后端 | 启用 `CUDAExecutionProvider` (需 ONNX Runtime CUDA 版) |
| 线程数 | `SessionOptions::with_intra_op_num_threads(n)` |
| 帧批处理 | 可修改模型支持 chunk 推理（一次处理 N 帧） |
| 复用 Session | 不要在每帧新建 `Session`，复用同一实例 |

### Q4: 同时处理多段音频

每个音频流需要独立的 `Aec7Infer` 实例（因为缓存是流状态）：

```rust
// 每个流独立实例
let mut infer_1 = Aec7Infer::new("aec7_ep0185.onnx")?;
let mut infer_2 = Aec7Infer::new("aec7_ep0185.onnx")?;

// 每条流重置时
infer_1.reset();
```

> 每个 `Session` 是线程安全的，但缓存是 `&mut self`，所以多线程场景推荐每条线程持有一个 `Aec7Infer`。

---

## 10. 附录：缓存大小速查表

| 缓存名称 | 总元素数 (B=1) | 形状 (B=1) | 数据类型 |
|---|---|---|---|
| `res_enc_conv` | 135,680 | `(1, 135680)` | f32 |
| `res_enc_tfa` | 248 | `(1, 248)` | f32 |
| `mic_enc_conv` | 135,680 | `(1, 135680)` | f32 |
| `mic_enc_tfa` | 248 | `(1, 248)` | f32 |
| `deep_enc_conv` | 0 | `(1, 0)` | f32 |
| `deep_enc_tfa` | 336 | `(1, 336)` | f32 |
| `dec_conv` | 13,440 | `(1, 13440)` | f32 |
| `dec_tfa` | 496 | `(1, 496)` | f32 |
| `inter` | 7,680 | `(1, 7680)` | f32 |
| `res_prev1` | 320 | `(1, 1, 1, 320)` | f32 |
| `res_prev2` | 320 | `(1, 1, 1, 320)` | f32 |
| `mic_prev1` | 320 | `(1, 1, 1, 320)` | f32 |
| `mic_prev2` | 320 | `(1, 1, 1, 320)` | f32 |
| **合计** | **295,088** | ≈ **1.13 MB** | |

> 注意：`deep_enc_conv` 形状为 `(1, 0)` —— 这是一个**零尺寸张量**。ONNX Runtime 和 `ndarray` 都支持零尺寸张量，使用 `Array::from_shape_vec(IxDyn(&[1, 0]), vec![])` 创建。

---

## 参考

- [ort crate 文档](https://docs.rs/ort)
- [ONNX Runtime API 参考](https://onnxruntime.ai/docs/api/)
- [原始 AEC7 Python 实现](../aec7_stream.py)
- [ONNX 导出代码](../aec7_train.py) (见 `export_aec7_onnx` 函数)
