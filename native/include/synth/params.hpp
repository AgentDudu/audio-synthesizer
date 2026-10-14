#pragma once

namespace synth {

// Shared with Java (synth.Engine constants). Keep in sync; order matters.
enum ParamId : int {
  kCutoff = 0,
  kResonance = 1,
  kAttack = 2,
  kDecay = 3,
  kSustain = 4,
  kRelease = 5,
  kOscMix = 6,
  kMaster = 7,
  kParamCount = 8,
};

}  // namespace synth
