#include "synth/audio_engine.hpp"

#include <RtAudio.h>

#include <sstream>

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

}  // namespace synth
