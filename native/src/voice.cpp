#include "synth/voice.hpp"

namespace synth {

void Voice::start(int note, float velocity, const Adsr &envInit, unsigned long long order) {
  note_ = note;
  freq_ = 440.f * std::pow(2.f, (note - 69) / 12.f);
  vel_ = velocity / 127.f;
  order_ = order;
  osc_.reset(0.f);
  env_ = envInit;
  env_.gateOn();
}

}  // namespace synth
