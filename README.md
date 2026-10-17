# audio-synthesizer

A software synthesizer: a Java Swing interface driving a C++ audio engine over JNI, with real-time
output through RtAudio (WASAPI on Windows). 88-key keyboard, 16-voice polyphony, and a scope strip
that doubles as a per-key reverse waterfall.

Windows only in this state — the audio layer is WASAPI.

## Requirements

| what | why |
|---|---|
| JDK 21 (`javac` on PATH) | UI + `javac -h` generates the JNI header |
| MinGW-w64 `g++` and `cmake` | builds `synthjni.dll` and the offline tests (C++17) |
| Git | RtAudio 6.0.1 is vendored under `native/third_party/` |

`build.ps1` pins the toolchain to `C:/Strawberry/c/bin` when that path exists, because two MinGW
installs on PATH otherwise make the build silently pick a different compiler. Edit `$gccDir` at the
top of the script for a different one.

## Build and run

```powershell
.\build.ps1            # build, then print the output devices it found
.\build.ps1 run        # build, then open the window
.\build.ps1 test       # build, then ctest + UI self-test + quit check
```

Every target compiles first: `javac -h native/include` (which regenerates `synth_Engine.h`), then
CMake configure + build into `build/native`, then the Java run. Nothing needs installing beyond the
requirements, and there is no internet access during the build.

## Playing

- **Mouse** — click or drag across the keyboard.
- **Computer keys** — tracker-style row, `a` is middle C: `a w s e d f t g y h u j k o l p ;`
  (`w`, `e`, `t`, `y`, `u`, `o`, `p` are the black keys).
- **Left / Right arrows** — shift the computer-key row an octave. The on-screen keyboard always shows
  the full A0–C8 range and marks the octave the keys are currently in.
- **Esc** — all notes off. Losing window focus does the same, so a stray Alt-Tab never leaves a note
  sounding. Neither touches a `.mid` that is still playing.
- **`midi file…`** — play a Standard MIDI File. No MIDI hardware or driver needed: it uses the JDK
  `Sequencer`, whose transmitter feeds the same receiver as a real device would.
- **`waterfall`** — switch the strip above the keys from the oscilloscope to one column per key,
  newest row at the keyboard edge, brightness = that note's envelope level.
- **Device / buffer combos** — which output endpoint is live and how big a buffer to ask for
  (128/256/512/1024 frames). Changing either restarts the stream; the label next to them names the
  device and the buffer size that actually opened.
- **Sliders** — cutoff, resonance, attack, decay, sustain, release, sine/saw mix, master. Live: move
  one while a note sustains and you hear it.

## Modes

`java -Djava.library.path=build/native -cp build/classes synth.App <mode>`

| mode | what it does |
|---|---|
| *(none)* / `run` | the window |
| `probe` | prints every output device, its id, channels and preferred rate |
| `smoke` | no window: starts the stream, plays four notes, reports underruns, writes `build/out.wav` |
| `close` | opens the window with a `.mid` playing, closes the window, and must exit on its own — the quit-cleanup check |

`synth.UiSelfTest` is the UI test suite (paints offscreen, dispatches real mouse and key events,
counts pixels). `build/native/engine_tests.exe` is the offline DSP test — no audio device, no JVM.

## Layout

```
java/src/main/java/synth/   App, Engine (the whole JNI surface), KeyboardPanel, KnobPanel,
                            ScopePanel, OutputPanel, MidiIn, UiSelfTest
native/include/synth/       dsp.hpp, voice.hpp, params.hpp, synth.hpp, audio_engine.hpp,
                            spsc_queue.hpp, wav_writer.hpp
native/src/                 audio_engine.cpp, synth.cpp, voice.cpp, wav_writer.cpp,
                            jni_bridge.cpp (the only JNIEnv file)
native/tests/               engine_tests.cpp
native/third_party/rtaudio/ vendored RtAudio 6.0.1
PLAN.md                     the plan this follows, including the milestone evidence
```

The audio callback thread is the only reader of the synth. Java pushes note events into a lock-free
single-producer ring and reads only atomics (scope ring, voice snapshot, counters), so the audio
thread never waits on the UI thread.

## Troubleshooting

- **`cannot open file ... synthjni.dll` during a build** — a running instance has the DLL loaded.
  Close the window (or `taskkill /IM java.exe /F`).
- **No sound** — check the device combo. This kind of machine exposes HDMI, virtual-cable and
  streaming endpoints next to the speakers; the app starts on the system default but you can pick
  any of them.
- **`audio start failed: ...`** — that endpoint refused the stream. Pick another; the combo snaps
  back to the one still sounding.
- **Clicking or dropouts** — raise the buffer size and watch the underrun count in `smoke` mode.

## License

MIT — see `LICENSE`.
