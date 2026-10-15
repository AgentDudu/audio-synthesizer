// Offline DSP tests: no audio device, no JVM. Run via ctest or build/native/engine_tests.exe.
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "synth/synth.hpp"
#include "synth/wav_writer.hpp"

namespace {
int failures = 0;
void check(bool ok, const char *what) {
  printf("%-40s %s\n", what, ok ? "ok" : "FAIL");
  if (!ok) ++failures;
}
double rms(const std::vector<float> &buf) {
  double sum = 0;
  for (float x : buf) sum += static_cast<double>(x) * x;
  return std::sqrt(sum / buf.size());
}
bool finite(const std::vector<float> &buf) {
  for (float x : buf)
    if (std::isnan(x) || std::isinf(x)) return false;
  return true;
}
}  // namespace

int main() {
  using namespace synth;
  const float fs = 48000.f;
  const int frames = 48000;
  std::vector<float> buf(static_cast<size_t>(frames) * 2, 0.f);

  Synth s;
  s.noteOn(69, 110, fs);
  s.renderBlock(buf.data(), frames, fs);
  printf("rms=%.5f\n", rms(buf));
  check(finite(buf), "no NaN/Inf over 1s");
  check(rms(buf) > 0.01, "1s render is audible");

  s.noteOff(69);
  std::fill(buf.begin(), buf.end(), 0.f);
  s.renderBlock(buf.data(), frames, fs);
  // The first 100 ms of a release is legitimately loud; assert on the end of the tail.
  const double tailRms = rms(std::vector<float>(buf.end() - 4800, buf.end()));
  printf("tail_rms(last 100ms)=%.8f\n", tailRms);
  check(s.activeVoices() == 0, "note-off frees the voice");
  check(tailRms < 1e-4, "release is silent after r seconds");

  for (int note = 40; note < 60; ++note) s.noteOn(note, 100, fs);
  std::vector<float> shortBuf(512 * 2, 0.f);
  s.renderBlock(shortBuf.data(), 512, fs);
  check(s.activeVoices() <= Synth::kVoices, "polyphony capped at 16 voices");
  check(finite(shortBuf), "16 voices stay finite");

  // Parameter plumbing must reach the DSP: a 200 Hz cutoff must kill a 1.5 kHz fundamental.
  Synth a, b;
  a.setParam(kCutoff, 18000.f);
  b.setParam(kCutoff, 200.f);
  std::vector<float> hi(2048 * 2, 0.f), lo(2048 * 2, 0.f);
  a.noteOn(88, 120, fs);
  b.noteOn(88, 120, fs);
  a.renderBlock(hi.data(), 2048, fs);
  b.renderBlock(lo.data(), 2048, fs);
  // Skip the attack transient in both, so the comparison is about the filter, not the envelope.
  const double hiRms = rms(std::vector<float>(hi.end() - 1024, hi.end()));
  const double loRms = rms(std::vector<float>(lo.end() - 1024, lo.end()));
  printf("cutoff_hi_rms=%.5f cutoff_lo_rms=%.5f\n", hiRms, loRms);
  check(loRms < hiRms * 0.2, "cutoff param reaches the filter");

  // M8: the waterfall display reads a per-block snapshot of each voice's note + envelope level.
  {
    Synth v;
    int notes[Synth::kVoices];
    float levels[Synth::kVoices];
    check(v.snapshotVoices(notes, levels) == 0, "voice snapshot empty before any note");
    std::vector<float> blk(64 * 2, 0.f);
    v.noteOn(60, 127, fs);
    v.renderBlock(blk.data(), 64, fs);
    int n = v.snapshotVoices(notes, levels);
    printf("snapshot_n=%d note=%d level=%.4f\n", n, n ? notes[0] : -1, n ? levels[0] : 0.f);
    check(n == 1 && notes[0] == 60, "voice snapshot reports note 60");
    float first = n == 1 ? levels[0] : 0.f;
    v.renderBlock(blk.data(), 64, fs);
    n = v.snapshotVoices(notes, levels);
    check(n == 1 && levels[0] > first, "snapshot level rises through the attack");
    v.noteOn(64, 100, fs);
    v.renderBlock(blk.data(), 64, fs);
    n = v.snapshotVoices(notes, levels);
    bool has60 = false, has64 = false;
    for (int i = 0; i < n; ++i) {
      if (notes[i] == 60) has60 = true;
      if (notes[i] == 64) has64 = true;
    }
    check(n == 2 && has60 && has64, "two held notes both appear in the snapshot");
    v.noteOff(60);
    v.noteOff(64);
    std::vector<float> rel(48000 * 2, 0.f);
    v.renderBlock(rel.data(), 48000, fs);  // 1 s: the release is effectively silent after ~0.25 s
    check(v.snapshotVoices(notes, levels) == 0, "voice snapshot empty after the release");
  }

  // The filter must not blow up at the extremes (a Chamberlin SVF did exactly this).
  Synth extreme;
  extreme.setParam(kCutoff, 20000.f);
  extreme.setParam(kResonance, 1.f);
  std::vector<float> ex(4096 * 2, 0.f);
  extreme.noteOn(20, 127, fs);
  extreme.renderBlock(ex.data(), 4096, fs);
  check(finite(ex), "filter stable at max cutoff + max resonance");

  check(writeWav("engine_tests_tmp.wav", hi.data(), 2048, 2, 48000), "writeWav succeeds");
  std::FILE *f = std::fopen("engine_tests_tmp.wav", "rb");
  char magic[12] = {};
  if (f) {
    std::fread(magic, 1, 12, f);
    std::fclose(f);
  }
  check(std::string(magic, 4) == "RIFF" && std::string(magic + 8, 4) == "WAVE", "wav header parses");
  std::remove("engine_tests_tmp.wav");

  printf(failures ? "FAILED %d checks\n" : "ALL TESTS PASSED\n", failures);
  return failures ? 1 : 0;
}
