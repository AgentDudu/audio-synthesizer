#pragma once
#include "synth/dsp.hpp"

namespace synth {

class Voice {
 public:
  void start(int note, float velocity, const Adsr &envInit, unsigned long long order);
  void release() { env_.gateOff(); }
  bool active() const { return env_.active(); }
  int note() const { return note_; }
  unsigned long long order() const { return order_; }

  // One mono sample; caller sums it into both buses (PLAN.md §8.2: mono voices, stereo out).
  inline float render(float fs, float oscMix) {
    osc_.step(freq_, fs);
    const float wave = oscMix * osc_.sine() + (1.f - oscMix) * osc_.saw();
    return wave * env_.tick(fs) * vel_;
  }

 private:
  Osc osc_;
  Adsr env_;
  int note_ = 0;
  float freq_ = 0.f;
  float vel_ = 0.f;
  unsigned long long order_ = 0;
};

}  // namespace synth
