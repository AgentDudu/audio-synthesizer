#pragma once
#include <atomic>
#include <string>
#include <vector>

#include <RtAudio.h>

#include "synth/params.hpp"
#include "synth/spsc_queue.hpp"
#include "synth/synth.hpp"

namespace synth {

// Human-readable WASAPI device report, for M0 verification and the M6 device picker.
std::string probeDevices();

struct NoteEvent {
  int note;
  int vel;  // vel > 0 => note on, else note off
};

// Owns the RtAudio stream and the Synth. The callback thread is the only reader of Synth;
// the JNI thread only writes the queue and the parameter atomics (PLAN.md §2.1).
class AudioEngine {
 public:
  static constexpr int kScopeSize = 1024;  // power of two

  static AudioEngine &instance();

  bool start(unsigned deviceId, unsigned sampleRate, unsigned bufferFrames, std::string *err);
  void stop();
  bool running() const { return audio_.isStreamRunning(); }

  void noteOn(int note, int vel) { queue_.push(NoteEvent{note, vel}); }
  void noteOff(int note) { queue_.push(NoteEvent{note, 0}); }

  void setParam(int id, float v) { synth_.setParam(id, v); }
  float getParam(int id) const { return synth_.getParam(id); }
  int activeVoices() const { return activeVoices_.load(std::memory_order_relaxed); }
  unsigned long long underruns() const { return underruns_.load(std::memory_order_relaxed); }

  // shortcut: scope taps a non-atomic ring; a torn sample is visually irrelevant.
  // shortcut: upgrade to a second atomic cursor pair if the scope ever needs sample accuracy.
  int copyScope(float *dst, int n) const;

  // Offline bounce of the given notes with the current parameters. Race-free by construction:
  // Java is the source of truth for what is held, so nothing is read from the live Synth here.
  bool renderWav(const std::string &path, double seconds, const std::vector<int> &notes,
                 const std::vector<int> &vels, std::string *err);

 private:
  AudioEngine() = default;
  static int callback(void *out, void *in, unsigned nFrames, double streamTime,
                      RtAudioStreamStatus status, void *userData);

  RtAudio audio_;
  Synth synth_;
  Spsc<NoteEvent, 256> queue_;
  std::atomic<float> fs_{48000.f};
  float scope_[kScopeSize]{};
  std::atomic<unsigned> scopePos_{0};
  std::atomic<unsigned> activeVoices_{0};
  std::atomic<unsigned long long> underruns_{0};
};

}  // namespace synth
