#include "synth/synth.hpp"

#include <cmath>

namespace synth {

Synth::Synth() {
  const float defaults[kParamCount] = {
      /*cutoff=*/8000.f, /*resonance=*/0.2f, /*attack=*/0.003f, /*decay=*/0.2f,
      /*sustain=*/0.7f, /*release=*/0.25f, /*oscMix=*/0.f, /*master=*/0.2f};
  for (int i = 0; i < kParamCount; ++i) params_[i].store(defaults[i], std::memory_order_relaxed);
}

Adsr Synth::envTemplate() const {
  Adsr a;
  a.a = getParam(kAttack);
  a.d = getParam(kDecay);
  a.s = getParam(kSustain);
  a.r = getParam(kRelease);
  return a;
}

void Synth::noteOn(int note, int vel, float fs) {
  note = (note < 0) ? 0 : (note > 127 ? 127 : note);
  vel = (vel <= 0) ? 1 : (vel > 127 ? 127 : vel);

  int slot = -1;
  for (int i = 0; i < kVoices; ++i) {
    if (!voices_[i].active()) {
      slot = i;
      break;
    }
  }
  if (slot < 0) {  // steal the oldest voice
    slot = 0;
    for (int i = 1; i < kVoices; ++i)
      if (voices_[i].order() < voices_[slot].order()) slot = i;
  }

  voices_[slot].start(note, static_cast<float>(vel), envTemplate(), ++order_);
  (void)fs;  // voice reads fs per sample; kept for API symmetry
}

void Synth::noteOff(int note) {
  for (int i = 0; i < kVoices; ++i)
    if (voices_[i].active() && voices_[i].note() == note) voices_[i].release();
}

void Synth::renderBlock(float *out, int frames, float fs) {
  const float cutoff = getParam(kCutoff);
  const float q = 0.5f + getParam(kResonance) * 6.f;  // resonance param 0..1 -> Q 0.5..6.5
  const float mix = getParam(kOscMix);
  const float master = getParam(kMaster);
  filter_.setLowpass(cutoff, q, fs);

  for (int i = 0; i < frames; ++i) {
    float sum = 0.f;
    for (int v = 0; v < kVoices; ++v)
      if (voices_[v].active()) sum += voices_[v].render(fs, mix);

    const float filtered = filter_.process(sum);
    const float x = filtered * master;
    const float soft = x / (1.f + std::fabs(x));  // cheap soft clip, keeps output in [-1,1]
    out[2 * i] = soft;
    out[2 * i + 1] = soft;
  }
}

int Synth::activeVoices() const {
  int n = 0;
  for (int i = 0; i < kVoices; ++i)
    if (voices_[i].active()) ++n;
  return n;
}

int Synth::snapshotVoices(int *notes, float *levels) const {
  int n = 0;
  for (int i = 0; i < kVoices; ++i) {
    if (!voices_[i].active()) continue;
    if (notes) notes[n] = voices_[i].note();
    if (levels) levels[n] = voices_[i].level();
    ++n;
  }
  return n;
}

}  // namespace synth
