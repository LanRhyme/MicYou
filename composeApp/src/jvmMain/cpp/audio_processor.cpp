#include <jni.h>
#include <vector>
#include <cmath>
#include <complex>
#include <cstring>
#include <algorithm>
#include <memory>
#include <cstdint>
#include <string>

#ifdef _WIN32
#include <Windows.h>
#endif

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#include "onnxruntime_cxx_api.h"
#include "cpu_provider_factory.h"
#include "pffft/pffft.h"

class RingBuffer {
public:
    RingBuffer(size_t size) : size_(size), buffer_(size, 0.0f), write_pos_(0) {}

    void write(const float* data, size_t data_len) {
        if (data_len > size_) {
            return;
        }
        size_t end_pos = write_pos_ + data_len;
        if (end_pos <= size_) {
            std::memcpy(buffer_.data() + write_pos_, data, data_len * sizeof(float));
        } else {
            size_t first_part = size_ - write_pos_;
            std::memcpy(buffer_.data() + write_pos_, data, first_part * sizeof(float));
            std::memcpy(buffer_.data(), data + first_part, (data_len - first_part) * sizeof(float));
        }
        write_pos_ = end_pos % size_;
    }

    void read(float* dest, size_t size) {
        if (size > size_) {
            return;
        }
        size_t read_pos = (write_pos_ - size + size_) % size_;
        size_t end_pos = read_pos + size;
        if (end_pos <= size_) {
            std::memcpy(dest, buffer_.data() + read_pos, size * sizeof(float));
        } else {
            size_t first_part = size_ - read_pos;
            std::memcpy(dest, buffer_.data() + read_pos, first_part * sizeof(float));
            std::memcpy(dest + first_part, buffer_.data(), (size - first_part) * sizeof(float));
        }
    }

    void clear() {
        std::fill(buffer_.begin(), buffer_.end(), 0.0f);
        write_pos_ = 0;
    }

private:
    size_t size_;
    std::vector<float> buffer_;
    size_t write_pos_;
};

std::vector<float> hanning_window(size_t size) {
    std::vector<float> window(size);
    for (size_t i = 0; i < size; ++i) {
        window[i] = std::sqrt(0.5f - 0.5f * std::cos(2.0f * M_PI * i / (size - 1)));
    }
    return window;
}

static const int EQ_BANDS = 10;
static const float EQ_FREQS[EQ_BANDS] = {31.0f, 62.0f, 125.0f, 250.0f, 500.0f, 1000.0f, 2000.0f, 4000.0f, 8000.0f, 16000.0f};

struct BiquadCoeff {
    float b0, b1, b2, a1, a2;
    float x1, x2, y1, y2;
    BiquadCoeff() : b0(1), b1(0), b2(0), a1(0), a2(0), x1(0), x2(0), y1(0), y2(0) {}
    void reset_state() { x1 = x2 = y1 = y2 = 0; }
};

BiquadCoeff design_peaking_eq(float freq, float gain_db, float q, float sample_rate) {
    BiquadCoeff coeff;
    float A = std::pow(10.0f, gain_db / 40.0f);
    float w0 = 2.0f * M_PI * freq / sample_rate;
    float cos_w0 = std::cos(w0);
    float sin_w0 = std::sin(w0);
    float alpha = sin_w0 / (2.0f * q);
    float a0 = 1.0f + alpha / A;
    coeff.b0 = (1.0f + alpha * A) / a0;
    coeff.b1 = (-2.0f * cos_w0) / a0;
    coeff.b2 = (1.0f - alpha * A) / a0;
    coeff.a1 = (-2.0f * cos_w0) / a0;
    coeff.a2 = (1.0f - alpha / A) / a0;
    return coeff;
}

class AudioProcessor {
public:
    AudioProcessor(float pre_gain_db, float post_gain_db, const std::string& noise_model_path,
                   size_t frame_size, size_t hop_length)
        : pre_gain_(std::pow(10.0f, pre_gain_db / 20.0f)),
          post_gain_(std::pow(10.0f, post_gain_db / 20.0f)),
          frame_size_(frame_size),
          hop_length_(hop_length),
          sample_rate_(48000.0f),
          window_(hanning_window(frame_size)),
          previous_(hop_length, 0.0f),
          noise_model_path_(noise_model_path) {

        eq_filters_.resize(EQ_BANDS);
        for (int i = 0; i < EQ_BANDS; ++i) {
            eq_filters_[i] = design_peaking_eq(EQ_FREQS[i], 0.0f, 1.0f, sample_rate_);
        }

        Ort::Env env(ORT_LOGGING_LEVEL_WARNING, "AudioProcessor");
        env_ = std::make_shared<Ort::Env>(std::move(env));
        initialize_model();

        fft_plan_ = pffft_new_setup(static_cast<int>(frame_size), PFFFT_REAL);
        if (!fft_plan_) {
            return;
        }

        fft_in_ = static_cast<float*>(pffft_aligned_malloc(frame_size * sizeof(float)));
        fft_out_ = static_cast<float*>(pffft_aligned_malloc((frame_size / 2 + 1) * 2 * sizeof(float)));
        ifft_out_ = static_cast<float*>(pffft_aligned_malloc(frame_size * sizeof(float)));
        model_input_ = std::vector<float>((frame_size / 2 + 1) * 2, 0.0f);

        Ort::AllocatorWithDefaultOptions allocator;
        size_t num_inputs = session_->GetInputCount();
        size_t num_outputs = session_->GetOutputCount();

        for (size_t i = 0; i < num_inputs; ++i) {
            Ort::AllocatedStringPtr name_ptr = session_->GetInputNameAllocated(i, allocator);
            input_names_.push_back(std::string(name_ptr.get()));
            input_names_cstr_.push_back(input_names_.back().c_str());
            name_ptr.release();
        }

        for (size_t i = 0; i < num_outputs; ++i) {
            Ort::AllocatedStringPtr name_ptr = session_->GetOutputNameAllocated(i, allocator);
            output_names_.push_back(std::string(name_ptr.get()));
            output_names_cstr_.push_back(output_names_.back().c_str());
            name_ptr.release();
        }

        initialize_states();
        output_frame_.resize(hop_length_, 0.0f);
        ola_accumulator_.resize(frame_size_, 0.0f);
    }

    ~AudioProcessor() {
        cleanup();
    }

    void initialize_model() {
        if (noise_model_path_.empty()) {
            return;
        }
        Ort::SessionOptions session_options;
        session_options.SetIntraOpNumThreads(1);
        session_options.SetInterOpNumThreads(1);
        session_options.SetExecutionMode(ORT_SEQUENTIAL);
        session_options.SetGraphOptimizationLevel(ORT_ENABLE_BASIC);

        #ifdef _WIN32
        int wide_size = MultiByteToWideChar(CP_UTF8, 0, noise_model_path_.c_str(), -1, nullptr, 0);
        std::wstring wide_model_path(wide_size, 0);
        MultiByteToWideChar(CP_UTF8, 0, noise_model_path_.c_str(), -1, &wide_model_path[0], wide_size);
        Ort::Session session(*env_, wide_model_path.c_str(), session_options);
        #else
        Ort::Session session(*env_, noise_model_path_.c_str(), session_options);
        #endif
        session_ = std::make_shared<Ort::Session>(std::move(session));
    }

    void initialize_states() {
        states_[0].resize(1 * 1 * 2 * 121, 0.0f);
        states_[1].resize(1 * 24 * 1 * 61, 0.0f);
        states_[2].resize(1 * 24 * 1 * 31, 0.0f);
        states_[3].resize(1 * 1 * 24, 0.0f);
        states_[4].resize(1 * 1 * 48, 0.0f);
        states_[5].resize(1 * 1 * 48, 0.0f);
        states_[6].resize(1 * 1 * 64, 0.0f);
        states_[7].resize(1 * 1 * 32, 0.0f);
        states_[8].resize(1 * 31 * 16, 0.0f);
        states_[9].resize(1 * 31 * 16, 0.0f);
        states_[10].resize(1 * 24 * 1 * 31, 0.0f);
        states_[11].resize(1 * 12 * 1 * 31, 0.0f);
        states_[12].resize(1 * 12 * 2 * 61, 0.0f);
        states_[13].resize(1 * 1 * 64, 0.0f);
        states_[14].resize(1 * 1 * 48, 0.0f);
        states_[15].resize(1 * 1 * 48, 0.0f);
        states_[16].resize(1 * 1 * 24, 0.0f);
        states_[17].resize(1 * 1 * 2, 0.0f);
    }

    std::vector<float> process(const std::vector<float>& audio_chunk) {
        size_t chunk_len = audio_chunk.size();
        if (chunk_len != hop_length_) {
            return audio_chunk;
        }

        std::vector<float> stage0(hop_length_);
        std::memcpy(stage0.data(), audio_chunk.data(), hop_length_ * sizeof(float));

        std::vector<float> stage1(hop_length_);
        std::memcpy(stage1.data(), stage0.data(), hop_length_ * sizeof(float));
        apply_eq(stage1.data(), stage1.size());

        std::vector<float> stage2(hop_length_);
        for (size_t i = 0; i < hop_length_; ++i) {
            stage2[i] = stage1[i] * pre_gain_;
        }

        std::vector<float> stage3 = noise_reduction(stage2);

        std::vector<float> result(hop_length_);
        for (size_t i = 0; i < hop_length_; ++i) {
            result[i] = stage3[i] * post_gain_;
        }

        return result;
    }

    std::vector<float> noise_reduction(const std::vector<float>& audio_chunk) {
        if (!session_) {
            return audio_chunk;
        }

        std::memcpy(fft_in_, previous_.data(), hop_length_ * sizeof(float));
        std::memcpy(fft_in_ + hop_length_, audio_chunk.data(), hop_length_ * sizeof(float));
        std::memcpy(previous_.data(), audio_chunk.data(), hop_length_ * sizeof(float));

        for (size_t i = 0; i < frame_size_; ++i) {
            fft_in_[i] *= window_[i];
        }

        pffft_transform_ordered(fft_plan_, fft_in_, fft_out_, nullptr, PFFFT_FORWARD);

        model_input_[0] = fft_out_[0];
        model_input_[1] = 0.0f;
        model_input_[2] = fft_out_[2];
        model_input_[3] = fft_out_[3];

        for (size_t i = 1; i < frame_size_ / 2; ++i) {
            size_t src_idx = 2 + i * 2;
            size_t dst_idx = (i + 1) * 2;
            model_input_[dst_idx] = fft_out_[src_idx];
            model_input_[dst_idx + 1] = fft_out_[src_idx + 1];
        }

        model_input_[frame_size_] = fft_out_[1];
        model_input_[frame_size_ + 1] = 0.0f;

        Ort::MemoryInfo mem_info = Ort::MemoryInfo::CreateCpu(
            OrtAllocatorType::OrtArenaAllocator, OrtMemType::OrtMemTypeDefault);

        std::vector<int64_t> spec_shape = {1, static_cast<int64_t>(frame_size_ / 2 + 1), 1, 2};

        std::vector<std::vector<int64_t>> state_shapes = {
            {1, 1, 2, 121},
            {1, 24, 1, 61},
            {1, 24, 1, 31},
            {1, 1, 24},
            {1, 1, 48},
            {1, 1, 48},
            {1, 1, 64},
            {1, 1, 32},
            {1, 31, 16},
            {1, 31, 16},
            {1, 24, 1, 31},
            {1, 12, 1, 31},
            {1, 12, 2, 61},
            {1, 1, 64},
            {1, 1, 48},
            {1, 1, 48},
            {1, 1, 24},
            {1, 1, 2}
        };

        std::vector<Ort::Value> input_tensors;
        input_tensors.reserve(19);

        input_tensors.push_back(Ort::Value::CreateTensor<float>(
            mem_info, model_input_.data(), model_input_.size(),
            spec_shape.data(), spec_shape.size()));

        for (int i = 0; i < 18; ++i) {
            input_tensors.push_back(Ort::Value::CreateTensor<float>(
                mem_info, states_[i].data(), states_[i].size(),
                state_shapes[i].data(), state_shapes[i].size()));
        }

        std::vector<const char*> input_names_cstr(input_names_.size());
        for (size_t i = 0; i < input_names_.size(); ++i) {
            input_names_cstr[i] = input_names_[i].c_str();
        }

        std::vector<const char*> output_names_cstr(output_names_.size());
        for (size_t i = 0; i < output_names_.size(); ++i) {
            output_names_cstr[i] = output_names_[i].c_str();
        }

        std::vector<Ort::Value> outputs = session_->Run(
            Ort::RunOptions{nullptr},
            input_names_cstr.data(),
            input_tensors.data(),
            input_tensors.size(),
            output_names_cstr.data(),
            output_names_cstr.size());

        float* output_data = outputs[0].GetTensorMutableData<float>();

        for (int i = 0; i < 18; ++i) {
            float* state_out_ptr = outputs[i + 1].GetTensorMutableData<float>();
            size_t state_size = states_[i].size();
            Ort::TensorTypeAndShapeInfo tensor_info = outputs[i + 1].GetTensorTypeAndShapeInfo();
            size_t actual_size = tensor_info.GetElementCount();
            size_t copy_size = (state_size < actual_size) ? state_size : actual_size;
            std::memcpy(states_[i].data(), state_out_ptr, copy_size * sizeof(float));
        }

        fft_out_[0] = output_data[0];
        fft_out_[1] = output_data[frame_size_];

        for (size_t i = 1; i < frame_size_ / 2; ++i) {
            size_t src_idx = i * 2;
            size_t dst_idx = 2 + (i - 1) * 2;
            fft_out_[dst_idx] = output_data[src_idx];
            fft_out_[dst_idx + 1] = output_data[src_idx + 1];
        }

        pffft_transform_ordered(fft_plan_, fft_out_, ifft_out_, nullptr, PFFFT_BACKWARD);

        float scale = 1.0f / (frame_size_ * 2.0f);
        for (size_t i = 0; i < frame_size_; ++i) {
            ifft_out_[i] *= scale * window_[i];
        }

        for (size_t i = 0; i < frame_size_; ++i) {
            ola_accumulator_[i] += ifft_out_[i];
        }

        for (size_t i = 0; i < hop_length_; ++i) {
            output_frame_[i] = ola_accumulator_[i];
        }

        for (size_t i = 0; i < frame_size_ - hop_length_; ++i) {
            ola_accumulator_[i] = ola_accumulator_[i + hop_length_];
        }

        for (size_t i = frame_size_ - hop_length_; i < frame_size_; ++i) {
            ola_accumulator_[i] = 0.0f;
        }

        return output_frame_;
    }

    void set_eq_gains(const std::vector<float>& gains) {
        if (gains.size() != EQ_BANDS) {
            return;
        }
        for (int i = 0; i < EQ_BANDS; ++i) {
            eq_filters_[i] = design_peaking_eq(EQ_FREQS[i], gains[i], 1.0f, sample_rate_);
        }
    }

    void set_pre_gain(float gain_db) {
        pre_gain_ = std::pow(10.0f, gain_db / 20.0f);
    }

    void set_post_gain(float gain_db) {
        post_gain_ = std::pow(10.0f, gain_db / 20.0f);
    }

    void cleanup() {
        if (fft_plan_) {
            pffft_destroy_setup(fft_plan_);
            fft_plan_ = nullptr;
        }
        if (fft_in_) {
            pffft_aligned_free(fft_in_);
            fft_in_ = nullptr;
        }
        if (fft_out_) {
            pffft_aligned_free(fft_out_);
            fft_out_ = nullptr;
        }
        if (ifft_out_) {
            pffft_aligned_free(ifft_out_);
            ifft_out_ = nullptr;
        }
        session_.reset();
        env_.reset();
    }

private:
    float pre_gain_;
    float post_gain_;
    size_t frame_size_;
    size_t hop_length_;
    float sample_rate_;
    std::vector<float> window_;
    std::vector<float> previous_;
    std::string noise_model_path_;
    std::vector<BiquadCoeff> eq_filters_;

    std::shared_ptr<Ort::Env> env_;
    std::shared_ptr<Ort::Session> session_;
    std::vector<std::string> input_names_;
    std::vector<std::string> output_names_;
    std::vector<const char*> input_names_cstr_;
    std::vector<const char*> output_names_cstr_;
    std::vector<float> model_input_;
    std::vector<float> output_frame_;
    std::vector<float> ola_accumulator_;
    std::vector<float> states_[18];
    PFFFT_Setup* fft_plan_;
    float* fft_in_;
    float* fft_out_;
    float* ifft_out_;

    void apply_eq(float* data, size_t len) {
        for (size_t n = 0; n < len; ++n) {
            float x = data[n];
            float y = x;
            for (auto& f : eq_filters_) {
                float out = f.b0 * x + f.b1 * f.x1 + f.b2 * f.x2 - f.a1 * f.y1 - f.a2 * f.y2;
                f.x2 = f.x1;
                f.x1 = x;
                f.y2 = f.y1;
                f.y1 = out;
                x = out;
                y = out;
            }
            data[n] = y;
        }
    }
};

// JNI 接口实现
extern "C" {

JNIEXPORT jlong JNICALL
Java_com_lanrhyme_micyou_audio_AudioProcessor_nativeCreate(JNIEnv* env, jobject thiz,
    jfloat preGainDb, jfloat postGainDb, jstring modelPath, jlong frameSize, jlong hopLength)
{
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    std::string model_path(path);
    env->ReleaseStringUTFChars(modelPath, path);

    AudioProcessor* processor = new AudioProcessor(preGainDb, postGainDb, model_path,
                                                   static_cast<size_t>(frameSize),
                                                   static_cast<size_t>(hopLength));
    return reinterpret_cast<jlong>(processor);
}

JNIEXPORT void JNICALL
Java_com_lanrhyme_micyou_audio_AudioProcessor_nativeDestroy(JNIEnv* env, jobject thiz, jlong handle)
{
    AudioProcessor* processor = reinterpret_cast<AudioProcessor*>(handle);
    if (processor) {
        delete processor;
    }
}

JNIEXPORT jfloatArray JNICALL
Java_com_lanrhyme_micyou_audio_AudioProcessor_nativeProcess(JNIEnv* env, jobject thiz,
    jlong handle, jfloatArray inputArray)
{
    AudioProcessor* processor = reinterpret_cast<AudioProcessor*>(handle);
    if (!processor) {
        return nullptr;
    }

    jsize len = env->GetArrayLength(inputArray);
    jfloat* input = env->GetFloatArrayElements(inputArray, nullptr);

    std::vector<float> input_vec(input, input + len);
    env->ReleaseFloatArrayElements(inputArray, input, JNI_ABORT);

    std::vector<float> output_vec = processor->process(input_vec);

    jfloatArray result = env->NewFloatArray(output_vec.size());
    env->SetFloatArrayRegion(result, 0, output_vec.size(), output_vec.data());

    return result;
}

JNIEXPORT void JNICALL
Java_com_lanrhyme_micyou_audio_AudioProcessor_nativeSetPreGain(JNIEnv* env, jobject thiz,
    jlong handle, jfloat gainDb)
{
    AudioProcessor* processor = reinterpret_cast<AudioProcessor*>(handle);
    if (processor) {
        processor->set_pre_gain(gainDb);
    }
}

JNIEXPORT void JNICALL
Java_com_lanrhyme_micyou_audio_AudioProcessor_nativeSetPostGain(JNIEnv* env, jobject thiz,
    jlong handle, jfloat gainDb)
{
    AudioProcessor* processor = reinterpret_cast<AudioProcessor*>(handle);
    if (processor) {
        processor->set_post_gain(gainDb);
    }
}

JNIEXPORT void JNICALL
Java_com_lanrhyme_micyou_audio_AudioProcessor_nativeSetEqGains(JNIEnv* env, jobject thiz,
    jlong handle, jfloatArray gainsArray)
{
    AudioProcessor* processor = reinterpret_cast<AudioProcessor*>(handle);
    if (!processor) {
        return;
    }

    jsize len = env->GetArrayLength(gainsArray);
    jfloat* gains = env->GetFloatArrayElements(gainsArray, nullptr);

    std::vector<float> gains_vec(gains, gains + len);
    env->ReleaseFloatArrayElements(gainsArray, gains, JNI_ABORT);

    processor->set_eq_gains(gains_vec);
}

} // extern "C"
