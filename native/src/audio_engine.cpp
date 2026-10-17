#include "synth/audio_engine.hpp"

#include <sstream>

#include "synth/wav_writer.hpp"

namespace synth {

std::string probeDevices() {
  std::ostringstream os;
  os << "rtaudio " << RTAUDIO_VERSION << " api=" << (int)RtAudio::WINDOWS_WASAPI << "\n";

  RtAudio audio;
  const std::vector<unsigned int> ids = audio.getDeviceIds();
  os << "devices=" << ids.size() << "\n";
  for (unsigned int id : ids) {
    // RtAudio 6 device ids are NOT 0-based indices (this machine returns 129..137).
    RtAudio::DeviceInfo info = audio.getDeviceInfo(id);
    os << "  id=" << info.ID << " " << info.name << " out=" << info.outputChannels
       << " in=" << info.inputChannels << " rate=" << info.preferredSampleRate
       << (info.isDefaultOutput ? " DEFAULT-OUT" : "") << "\n";
  }
  return os.str();
}

AudioEngine &AudioEngine::instance() {
  static AudioEngine engine;
  return engine;
}

int AudioEngine::callback(void *out, void *, unsigned nFrames, double, RtAudioStreamStatus status,
                          void *userData) {
  AudioEngine &self = *static_cast<AudioEngine *>(userData);
  if (status) self.underruns_.fetch_add(1, std::memory_order_relaxed);

  const float fs = self.fs_.load(std::memory_order_relaxed);
  NoteEvent ev;
  while (self.queue_.pop(ev)) {
    if (ev.vel > 0)
      self.synth_.noteOn(ev.note, ev.vel, fs);
    else
      self.synth_.noteOff(ev.note);
  }
  self.activeVoices_.store(static_cast<unsigned>(self.synth_.activeVoices()),
                           std::memory_order_relaxed);

  float *buf = static_cast<float *>(out);
  self.synth_.renderBlock(buf, static_cast<int>(nFrames), fs);

  unsigned p = self.scopePos_.load(std::memory_order_relaxed);
  for (unsigned i = 0; i < nFrames; ++i) {
    self.scope_[p] = 0.5f * (buf[2 * i] + buf[2 * i + 1]);
    p = (p + 1) & (kScopeSize - 1);
  }
  self.scopePos_.store(p, std::memory_order_relaxed);

  // Per-voice snapshot for the waterfall display: 16 slots per block, nothing on the per-sample path.
  int vnotes[Synth::kVoices];
  float vlevels[Synth::kVoices];
  const int vn = self.synth_.snapshotVoices(vnotes, vlevels);
  for (int i = 0; i < Synth::kVoices; ++i) {
    self.voiceNote_[i].store(i < vn ? vnotes[i] : -1, std::memory_order_relaxed);
    self.voiceLevel_[i].store(i < vn ? vlevels[i] : 0.f, std::memory_order_relaxed);
  }
  return 0;
}

bool AudioEngine::start(unsigned deviceId, unsigned sampleRate, unsigned bufferFrames,
                        std::string *err) {
  if (audio_.isStreamOpen()) {
    *err = "stream already open";
    return false;
  }

  RtAudio::StreamParameters out;
  out.deviceId = deviceId;
  out.nChannels = 2;
  out.firstChannel = 0;

  unsigned frames = bufferFrames;
  RtAudio::StreamOptions options;
  options.flags = RTAUDIO_MINIMIZE_LATENCY;

  RtAudioErrorType e = audio_.openStream(&out, nullptr, RTAUDIO_FLOAT32, sampleRate, &frames,
                                         &AudioEngine::callback, this, &options);
  if (e != RTAUDIO_NO_ERROR) {
    *err = audio_.getErrorText();
    return false;
  }
  fs_.store(static_cast<float>(sampleRate), std::memory_order_relaxed);
  // RtAudio rewrites the buffer size it actually opened with - show that, not what we asked for.
  bufferFrames_.store(frames, std::memory_order_relaxed);

  e = audio_.startStream();
  if (e != RTAUDIO_NO_ERROR) {
    *err = audio_.getErrorText();
    audio_.closeStream();
    return false;
  }
  return true;
}

void AudioEngine::stop() {
  if (!audio_.isStreamOpen()) return;
  if (audio_.isStreamRunning()) audio_.abortStream();
  audio_.closeStream();
  bufferFrames_.store(0, std::memory_order_relaxed);
}

int AudioEngine::copyScope(float *dst, int n) const {
  if (n > kScopeSize) n = kScopeSize;
  const unsigned p = scopePos_.load(std::memory_order_relaxed);
  for (int i = 0; i < n; ++i) dst[i] = scope_[(p - static_cast<unsigned>(n) + i + kScopeSize) & (kScopeSize - 1)];
  return n;
}

int AudioEngine::voiceLevels(int *notes, float *levels) const {
  int n = 0;
  for (int i = 0; i < Synth::kVoices; ++i) {
    const int note = voiceNote_[i].load(std::memory_order_relaxed);
    if (note < 0) continue;
    if (notes) notes[n] = note;
    if (levels) levels[n] = voiceLevel_[i].load(std::memory_order_relaxed);
    ++n;
  }
  return n;
}

bool AudioEngine::renderWav(const std::string &path, double seconds, const std::vector<int> &notes,
                            const std::vector<int> &vels, std::string *err) {
  const unsigned sr = 48000;
  const int frames = static_cast<int>(seconds * sr);
  if (frames <= 0 || frames > 60 * static_cast<int>(sr)) {
    *err = "bad duration";
    return false;
  }

  Synth offline;
  for (int p = 0; p < kParamCount; ++p) offline.setParam(p, synth_.getParam(p));
  for (size_t i = 0; i < notes.size(); ++i) {
    const int vel = (i < vels.size()) ? vels[i] : 100;
    offline.noteOn(notes[i], vel, static_cast<float>(sr));
  }

  std::vector<float> buf(static_cast<size_t>(frames) * 2, 0.f);
  offline.renderBlock(buf.data(), frames, static_cast<float>(sr));

  if (!writeWav(path, buf.data(), static_cast<size_t>(frames), 2, sr)) {
    *err = "cannot write " + path;
    return false;
  }
  return true;
}

}  // namespace synth
