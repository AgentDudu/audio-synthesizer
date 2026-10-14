#pragma once
#include <algorithm>
#include <cmath>

namespace synth {

// Header-only DSP: small, per-sample, allocation-free (RT-safe).

struct Osc {
  float phase = 0.f;  // 0..1

  void reset(float phase0 = 0.f) { phase = phase0; }

  inline void step(float freq, float fs) {
    phase += freq / fs;
    if (phase >= 1.f) phase -= 1.f;
  }

  // shortcut: naive band-limited-less oscillators, audible aliasing on the saw.
  // shortcut: upgrade to polyBLEP at M7 (PLAN.md R7).
  inline float saw() const { return 2.f * phase - 1.f; }
  inline float sine() const { return std::sin(6.283185307179586f * phase); }
};

// One-pole-per-stage ADSR: exponential, click-free. a/d/r are the e-folding times in seconds
// except release, which is scaled so the voice is effectively silent after r seconds.
struct Adsr {
  float a = 0.003f, d = 0.2f, s = 0.7f, r = 0.25f;
  float level = 0.f;
  int stage = 0;  // 0 idle, 1 attack, 2 decay, 3 sustain, 4 release

  void gateOn() { stage = 1; }
  void gateOff() {
    if (stage) stage = 4;
  }
  bool active() const { return stage != 0; }

  inline float tick(float fs) {
    switch (stage) {
      case 1:
        level += (1.f - level) / (a * fs + 1.f);
        if (level > 0.999f) {
          level = 1.f;
          stage = 2;
        }
        break;
      case 2:
        level += (s - level) / (d * fs + 1.f);
        if (std::fabs(level - s) < 1e-4f) stage = 3;
        break;
      case 3:
        level = s;
        break;
      case 4:
        level -= level / (r * fs * 0.2f + 1.f);  // ~5 time constants across the release
        if (level < 1e-4f) {
          level = 0.f;
          stage = 0;
        }
        break;
      default:
        break;
    }
    return level;
  }
};

// RBJ biquad lowpass. Chosen over a Chamberlin SVF, which went unstable (NaN) past ~8 kHz at
// Q > 1 — caught by engine_tests. Coefficients are set once per block.
struct Biquad {
  float b0 = 1.f, b1 = 0.f, b2 = 0.f, a1 = 0.f, a2 = 0.f;
  float x1 = 0.f, x2 = 0.f, y1 = 0.f, y2 = 0.f;

  void reset() { x1 = x2 = y1 = y2 = 0.f; }

  void setLowpass(float cutoffHz, float q, float fs) {
    const float fc = std::clamp(cutoffHz, 20.f, fs * 0.45f);
    const float w0 = 6.283185307179586f * fc / fs;
    const float alpha = std::sin(w0) / (2.f * std::max(q, 0.5f));
    const float cosw = std::cos(w0);
    const float a0 = 1.f + alpha;
    b0 = (1.f - cosw) / 2.f / a0;
    b1 = (1.f - cosw) / a0;
    b2 = b0;
    a1 = -2.f * cosw / a0;
    a2 = (1.f - alpha) / a0;
  }

  inline float process(float in) {
    const float out = b0 * in + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
    x2 = x1; x1 = in; y2 = y1; y1 = out;
    return out;
  }
};

}  // namespace synth
