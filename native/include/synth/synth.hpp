#pragma once
#include <array>
#include <atomic>

#include "synth/params.hpp"
#include "synth/voice.hpp"

namespace synth {

// Fixed 16-voice poly, mono voices summed to a stereo bus. No allocation after construction.
class Synth {
 public:
  static constexpr int kVoices = 16;

  Synth();

  // Called from the RtAudio callback thread only (events arrive pre-drained).
  void noteOn(int note, int vel, float fs);
  void noteOff(int note);
  void renderBlock(float *out, int frames, float fs);  // interleaved stereo

  // Called from the JNI thread.
  void setParam(int id, float v) {
    if (id >= 0 && id < kParamCount) params_[id].store(v, std::memory_order_relaxed);
  }
  float getParam(int id) const {
    return (id >= 0 && id < kParamCount) ? params_[id].load(std::memory_order_relaxed) : 0.f;
  }

  int activeVoices() const;

 private:
  Adsr envTemplate() const;

  Voice voices_[kVoices];
  std::atomic<float> params_[kParamCount];
  Biquad filter_;
  unsigned long long order_ = 0;
};

}  // namespace synth
