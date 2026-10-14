#pragma once
#include <cstddef>
#include <string>

namespace synth {

// Canonical 44-byte header, 16-bit PCM, interleaved. Returns false if the file cannot be written.
bool writeWav(const std::string &path, const float *interleaved, std::size_t frames, int channels,
              unsigned sampleRate);

}  // namespace synth
