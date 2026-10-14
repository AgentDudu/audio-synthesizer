#include "synth/wav_writer.hpp"

#include <cstdint>
#include <cstdio>
#include <vector>

namespace synth {

namespace {
void put32(std::vector<unsigned char> &b, uint32_t v) {
  b.push_back(v & 0xFF);
  b.push_back((v >> 8) & 0xFF);
  b.push_back((v >> 16) & 0xFF);
  b.push_back((v >> 24) & 0xFF);
}
void put16(std::vector<unsigned char> &b, uint16_t v) {
  b.push_back(v & 0xFF);
  b.push_back((v >> 8) & 0xFF);
}
}  // namespace

bool writeWav(const std::string &path, const float *interleaved, std::size_t frames, int channels,
              unsigned sampleRate) {
  const std::size_t samples = frames * static_cast<std::size_t>(channels);
  const uint32_t dataBytes = static_cast<uint32_t>(samples * 2);

  std::vector<unsigned char> header;
  header.insert(header.end(), {'R', 'I', 'F', 'F'});
  put32(header, 36 + dataBytes);
  header.insert(header.end(), {'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
  put32(header, 16);
  put16(header, 1);                                    // PCM
  put16(header, static_cast<uint16_t>(channels));
  put32(header, sampleRate);
  put32(header, sampleRate * static_cast<uint32_t>(channels) * 2);
  put16(header, static_cast<uint16_t>(channels * 2));  // block align
  put16(header, 16);                                   // bits per sample
  header.insert(header.end(), {'d', 'a', 't', 'a'});
  put32(header, dataBytes);

  std::vector<int16_t> pcm(samples);
  for (std::size_t i = 0; i < samples; ++i) {
    float x = interleaved[i];
    if (x > 1.f) x = 1.f;
    if (x < -1.f) x = -1.f;
    pcm[i] = static_cast<int16_t>(x * 32767.f);
  }

  std::FILE *f = std::fopen(path.c_str(), "wb");
  if (!f) return false;
  const bool ok = std::fwrite(header.data(), 1, header.size(), f) == header.size() &&
                  std::fwrite(pcm.data(), 2, samples, f) == samples;
  std::fclose(f);
  return ok;
}

}  // namespace synth
