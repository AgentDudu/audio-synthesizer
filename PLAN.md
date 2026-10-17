# Audio Synthesizer / DAW — Build Plan

Java (Swing) UI ⇄ JNI ⇄ C++ audio engine (RtAudio). Windows-first, designed so Linux/macOS
later is a CMake flag change, not a rewrite.

Status: **v0.5.0 on `main`** — 88-key piano (A0-C8) with mouse + computer keys that scales with the
window, eight live param sliders, a scope strip that toggles between the waveform and a per-key
reverse waterfall, MIDI-file input that lights the keys, output-device and buffer-size pickers, and
a window close that is proven to stop the audio thread and exit. `main` takes `v0.5.0` from
`release/0.5.0`; `develop` tracks it.
Next: M7 *(optional)* — polyBLEP, stereo voices, delay — or whatever gets pointed at.

---

## 0. Machine facts this plan was written against

Verified on `DESKTOP-F2345QN` (2026-10-15):

| Item | Value | Consequence |
|---|---|---|
| OS | Windows 10.0.22631, x86_64 (MSYS2/MinGW bash) | WASAPI is the audio backend to target |
| JDK | Temurin **21.0.12** LTS, `JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\` | `javac -h` available (JDK 10+), Swing built in, 64-bit ABI |
| CMake | 3.29.2 (`C:\mingw64\bin\cmake.exe`, also Strawberry's) | `FindJNI` works; use MinGW's to avoid mixing toolchains |
| Compiler | **MinGW-w64 g++ 13.2.0** (`x86_64-ucrt-posix-seh`) | No MSVC `cl` on PATH → build with MinGW, or install VS Build Tools |
| Gradle / Maven | **not installed** | Build = CMake + PowerShell script (no new toolchain) |
| Repo | `C:/Users/Rafael/VSC_Projects/audio-synthesizer` — empty, not a git repo | `git init` is step 0 |
| Remote | `https://github.com/AgentDudu/audio-synthesizer.git` — reachable, 0 refs (empty, public) | push works: Git Credential Manager has cached creds for `AgentDudu` |
| git / gh | git 2.54.0, `gh` 2.102.0, GCM installed | no auth setup needed |
| make / ninja | `C:\mingw64\bin\make.exe`, `mingw32-make.exe`, `ninja.exe` all present | `MinGW Makefiles` and `Ninja` generators both available |
| winget | v1.29.380 (choco absent) | escape hatch if a tool ever has to be installed |
| **Two MinGW installs on PATH** | `C:\Strawberry\c\bin` = g++ 13.2.0 + CMake 3.29.2; `C:\mingw64\bin` = g++ 15.2.0 + CMake 4.3.1 | `build.ps1` pins the **Strawberry** pair (the one every smoke test above ran with). Unpinned builds silently pick whichever is first in PATH. |

### 0.1 Toolchain audit — actually executed, not assumed

Smoke tests run in a scratch dir on 2026-10-15. **Nothing needs installing for M0–M3 or M5.**

| Test | Result | What it proves |
|---|---|---|
| `g++ -shared` JNI DLL linking against MSVC-built `jdk.../lib/jvm.lib` | **links** | MinGW `ld` consumes the MSVC import lib. No MSVC/Visual Studio needed. |
| `java -Djava.library.path=. ToolProbe` → `JNI RUNS: jni-ok` | **passes** | Full Java→native round trip on this machine, incl. `-static-libgcc -static-libstdc++` variant (R5 satisfied). |
| `RtAudio.cpp` 6.0.1 compiled `-D__WINDOWS_WASAPI__` (MinGW g++ 13.2) | **compiles**, 11 s | R1 compile half is a non-issue. |
| Linked with `-lksuser -lmfplat -lmfuuid -lwmcodecdspuuid -lole32 -luuid -luser32` | **links** | R1 link half is a non-issue with those libs. |
| Ran it: `api=7 count=9`, 9 named WASAPI devices (Realtek Speakers @192 kHz, NVIDIA HDMI, VB-Cable, AudioRelay, Steam Streaming, mic in @192 kHz) | **works** | WASAPI is viable here; no DirectSound fallback needed. |
| DirectSound fallback compile+link | needs **`-lwinmm`** in addition to `-ldsound` (`__imp_timeBeginPeriod` / `__imp_timeEndPeriod` undefined otherwise) | Fallback line in §5.1 corrected. |
| `javax.sound.midi` probe | 4 devices (Gervill, Real Time Sequencer, Microsoft MIDI Mapper, GS Wavetable Synth); `MidiSystem.getTransmitter()` → `MidiUnavailableException` | API works, but **no physical MIDI input is connected**. M4 is blocked on hardware, not software. |
| Native test exe run from MSYS bash | exit 127 / no output when dynamically linked; **works when built `-static`** | Keep `-static` on test executables (§5.1). |

**API finding that changes code, not just docs:** RtAudio 6.x device IDs are **not** `0..count-1`.
On this machine they came back as `129…137` from `getDeviceIds()`. A 4.x/5.x-style
`for (i=0; i<getDeviceCount(); ++i) getDeviceInfo(i, &info)` loop compiles, runs, and silently
returns empty `DeviceInfo` (name blank, channels 0). Use `getDeviceIds()` and pass those IDs to
`getDeviceInfo()` / `openStream()` — see §4. This is R2 made concrete.

---

## 1. Locked decisions

| # | Decision | Choice | Why (and what was rejected) |
|---|---|---|---|
| D1 | C++ audio I/O | **RtAudio 6.0.1**, vendored | Two files (`RtAudio.h/.cpp`) committed in-tree → no DLL hunting, no package manager. Builds clean under MinGW. PortAudio = extra subproject + runtime DLL for the same job. JUCE = framework-sized learning curve and GPL/commercial licence, overkill for a first synth. |
| D2 | Backend | **WASAPI** (`__WINDOWS_WASAPI__`), DirectSound as compile-time fallback | Lowest practical latency on Win10 shared mode. DirectSound fallback exists because MinGW WASAPI has a known link-lib history (see R1). |
| D3 | UI | **Swing** | Already in JDK 21 → zero downloads, runs with plain `javac`. Custom painting for keyboard/scope via `JComponent.paintComponent`. JavaFX deferred: needs SDK/build-tool fetch; migration path is the `ScopePanel`/`KeyboardPanel` paint code only. |
| D4 | Build | **CMake for C++ + `build.ps1` for Java** | CMake's `FindJNI` locates JDK headers/libs. No Gradle/Maven installed; adding one is setup, not progress. Maven/Gradle wrapper can be layered on later without touching C++. |
| D5 | MIDI input | **Java `javax.sound.midi`** → note events pushed over JNI | Pure JDK, no second native path, no winmm/`MIDIIN` callback threading headache. Rejected: native MIDI in C++ (more RT-thread risk, no benefit). |
| D6 | Engine ownership | C++ owns the audio thread and the clock; Java is **control-only** | Java never supplies samples. JNI is one-way (Java → C++). Keeps the RT callback free of JNI/JVM hazards. |
| D7 | Offline render | Same DSP code, driven by a non-realtime loop in C++ | One DSP implementation → WAV export cannot drift from what you heard. |
| D8 | v1 feature set | Poly synth + ADSR + filter, on-screen keyboard + computer keys, hardware MIDI in, live scope, WAV export | As selected. Piano roll, presets-as-UI-library, effects, plugin formats = post-v1. |

---

## 2. Architecture

```
Swing EDT (Java)                         C++ process (same JVM)
┌───────────────────────────┐            ┌──────────────────────────────────┐
│ App / KnobPanel           │  JNI       │ jni_bridge.cpp                   │
│ KeyboardPanel (mouse/keys)│ ─────────► │   noteOn/Off, setParam, render   │
│ MidiIn (javax.sound.midi) │  one-way   │            │                     │
│ ScopePanel (Swing Timer)  │ ◄───────── │            ▼                     │
└───────────────────────────┘  poll      │ Synth (voice pool, params)       │
                                         │   ▲ SPSC event queue + atomics   │
                                         │   │ written by JNI thread        │
                                         │   ▼                            │
                                         │ RtAudio WASAPI callback thread  │
                                         │   renderBlock() → float* out    │
                                         │   + scope ring buffer           │
                                         └──────────────────────────────────┘
```

### 2.1 The two threads, and the rule between them

- **JNI thread** (whatever Java thread calls a native method) writes: note events into a
  single-producer/single-consumer ring, and continuous params into `std::atomic<float>`.
- **RtAudio callback thread** reads: drains the queue at block start, loads atomics, renders.
- Hard rules for the callback: no `new`/`delete`, no `std::mutex`, no exceptions crossing out,
  no file I/O, no logging, **no JNI calls back into Java** (no JNIEnv is attached to it and the
  JVM would add unbounded pause). Java never receives a callback at all.
- One producer per queue. If two Java threads must produce (EDT + MIDI receiver), funnel both
  through one Java-side synchronized enqueue, or make the queue MPMC — resolved as §8.4.

### 2.2 Signal chain (v1)

```
per voice: osc(saw, +sine mix) → VCA(ADSR) ┐
                                             ├─ sum (16 voices) → RBJ biquad lowpass (Q = 0.5 + 6·resonance)
note-on: pitch from MIDI note, velocity → VCA peak ┘                     → master gain → soft clip → out
```

- **Filter is an RBJ biquad, not the planned Chamberlin SVF.** The SVF went unstable (NaN) past
  ~8 kHz at Q > 1 — found by `engine_tests`, not by ear.
- Release is scaled to ~5 time constants, so `release = 0.25 s` means *silent* after 0.25 s rather
  than "1.8 % still ringing".

- Naive phase-accumulator oscillators with `std::sin` for v1 (16 voices × 2 osc × 48 kHz is
  trivially affordable on desktop). Saw/square from naive `sign()`/`phase<T` alias badly —
  accepted for v1, **polyBLEP is milestone M7**, not a v1 blocker.
- Voice stealing: oldest-release-first, 16-voice fixed pool, no allocation after `openStream`.

---

## 3. Repository layout

```
audio-synthesizer/
  PLAN.md
  build.ps1                      # javac -h → cmake → java (single entry point)
  .gitignore                     # build/, *.class, *.dll
  native/
    CMakeLists.txt               # FindJNI + rtaudio subdirectory + shared lib target
    third_party/rtaudio/         # vendored RtAudio 6.0.1 (RtAudio.h/.cpp + CMakeLists.txt)
    include/synth/
      audio_engine.hpp  synth.hpp  voice.hpp  params.hpp  spsc_queue.hpp  wav_writer.hpp
      dsp.hpp                    # header-only (osc, ADSR, RBJ biquad, soft clip) - no dsp.cpp
    src/
      jni_bridge.cpp             # the ONLY file that knows about JNIEnv
      audio_engine.cpp  synth.cpp  voice.cpp  wav_writer.cpp
    tests/
      engine_tests.cpp           # assert-based, no framework, no audio device needed
  java/src/main/java/synth/
    App.java            Engine.java        # Engine = all `native` declarations + loadLibrary
    KeyboardPanel.java                     # done: full 88-key piano, mouse + computer keys
    UiSelfTest.java                        # done: headless UI check + keyboard PNG render
    KnobPanel.java  ScopePanel.java  MidiIn.java          # M3 / M4 / M8 (scope modes)
```

---

## 4. JNI surface (deliberately small)

Java side — `synth.Engine`:

```java
public final class Engine {
    static { System.loadLibrary("synthjni"); }   // build.ps1 sets -Djava.library.path
    public static final int CUTOFF=0, RESONANCE=1, ATTACK=2, DECAY=3, SUSTAIN=4, RELEASE=5, OSC_MIX=6, MASTER=7;
    public static native String probeDevices();  // device list, for M0 + device picker
    public static int defaultOutputDeviceId();   // pure Java, parses probeDevices()

    // Methods returning String: null = success, non-null = error text. No exceptions across JNI.
    // start/stop/noteOn/noteOff are Java wrappers (synchronized, §8.9) over private natives;
    // start reports failure as IOException, not a String.
    public static boolean start(int deviceId, int sampleRate, int bufferFrames) throws IOException;
    public static native void stop();
    public static native void noteOn(int note, int velocity);   // MIDI channel is filtered in Java
    public static native void noteOff(int note);
    public static native void setParam(int paramId, float value);
    public static native float getParam(int paramId);
    public static native int getScope(float[] out);              // last out.length frames
    public static native int getVoiceLevels(int[] notes, float[] levels); // M8: active voices' note + env level
    public static native String renderWav(String path, double seconds, int[] notes, int[] vels);
    public static native long underrunCount();
    public static native int activeVoices();
}
```

C side: `javac -h native/include` generates the headers; `jni_bridge.cpp` implements them and
owns the single `AudioEngine` instance. No `RegisterNatives` in v1 (generated names are fine;
revisit only if startup cost ever matters).

Notes:
- `renderWav`/`probeDevices` take `String` — convert once per call, never per sample block.
- `renderWav` takes the notes to bounce from Java. Reading held notes out of the live `Synth`
  would race the callback thread; Java is the source of truth, so the offline path is race-free
  by construction.
- Errors return a `String` (null = ok) instead of letting C++ exceptions cross the boundary.
- `getScope` uses `GetFloatArrayElements` from the Swing thread: safe, and it is the only
  Java→C++ data flow that reads engine state.

---

## 5. Build

### 5.1 `native/CMakeLists.txt` (essentials)

```cmake
cmake_minimum_required(VERSION 3.16)
project(synthjni CXX)
set(CMAKE_CXX_STANDARD 17)

find_package(JNI REQUIRED)

# Vendored RtAudio 6.0.1: static (no extra runtime DLL), WASAPI only, no upstream test programs.
set(RTAUDIO_BUILD_STATIC_LIBS ON CACHE BOOL "" FORCE)
set(RTAUDIO_API_WASAPI ON        CACHE BOOL "" FORCE)
set(RTAUDIO_API_DS OFF           CACHE BOOL "" FORCE)
set(RTAUDIO_API_ASIO OFF         CACHE BOOL "" FORCE)
add_subdirectory(third_party/rtaudio)

# Engine + DSP in a static lib shared by the JNI DLL and the offline tests, so the tests do not
# inherit a jvm.dll dependency.
add_library(synth_core STATIC
  src/audio_engine.cpp src/synth.cpp src/voice.cpp src/wav_writer.cpp)
target_include_directories(synth_core PUBLIC include)
# rtaudio defines its backend PRIVATEly, so every consumer must define it too.
target_compile_definitions(synth_core PUBLIC __WINDOWS_WASAPI__ WIN32_LEAN_AND_MEAN NOMINMAX)
target_link_libraries(synth_core PUBLIC rtaudio)

add_library(synthjni SHARED src/jni_bridge.cpp)
set_target_properties(synthjni PROPERTIES PREFIX "")   # synthjni.dll, not libsynthjni.dll
target_include_directories(synthjni PRIVATE ${JNI_INCLUDE_DIRS})
target_link_libraries(synthjni PRIVATE synth_core ${JNI_LIBRARIES})

if (MINGW)
  # WASAPI under MinGW-w64 needs the Media Foundation + KS libs (thestk/rtaudio#184)
  target_link_libraries(synth_core PUBLIC ksuser mfplat mfuuid wmcodecdspuuid ole32 uuid winmm user32)
  # ship without libgcc/libstdc++/winpthread DLLs on PATH
  target_link_options(synthjni PRIVATE -static-libgcc -static-libstdc++)
endif()

enable_testing()
add_executable(engine_tests tests/engine_tests.cpp)
target_link_libraries(engine_tests PRIVATE synth_core)
if (MINGW)
  target_link_options(engine_tests PRIVATE -static)   # run without MinGW DLLs on PATH
endif()
add_test(NAME engine_tests COMMAND engine_tests)
```

Verified on this machine (2026-10-15): the WASAPI block above compiles and links with MinGW as
written, and enumerates 9 devices. Fallback, if a future toolchain breaks it: swap
`__WINDOWS_WASAPI__` → `__WINDOWS_DS__` and link `dsound` **plus `winmm`** (the DS backend calls
`timeBeginPeriod`/`timeEndPeriod`; without `winmm` the link fails on `__imp_timeEndPeriod`).
Same API above the `RtAudio` abstraction, slightly worse latency.

### 5.2 `build.ps1` (single command, ordered)

```powershell
# pinned toolchain: two MinGW installs sit on PATH, so the script names the validated pair
$gccDir = 'C:/Strawberry/c/bin'
javac -h native/include -d build/classes $sources      # must run before cmake: synth_Engine.h
cmake -S native -B build/native -G "MinGW Makefiles" -DCMAKE_BUILD_TYPE=Release `
      -DCMAKE_CXX_COMPILER="$gccDir/g++.exe" -DCMAKE_MAKE_PROGRAM="$gccDir/mingw32-make.exe"
cmake --build build/native
java -Djava.library.path=build/native -cp build/classes synth.App <mode>
```

Targets: `-Target probe` (device report) | `smoke` (headless engine check + WAV bounce) |
`run` (keyboard window) | `test` (`ctest` for `engine_tests` + `synth.UiSelfTest`).

---

## 6. Milestones (each has a pass check; do not stack)

| M | Deliverable | Acceptance check |
|---|---|---|
| **M0** ✅ | `git init`, vendored RtAudio, CMake builds `synthjni.dll`, Java loads it | **Passed 2026-10-15:** `probeDevices()` printed `rtaudio 6.0.1 api=7`, 9 WASAPI devices, default out = `Speakers (Realtek)` id=132. |
| **M1** ✅ | Engine: WASAPI stream, note-on/off, 16-voice poly, sine/saw voice | **Passed 2026-10-15:** `ctest` 100% (no NaN, audible 1 s render, release silent, 20 notes → ≤16 voices, cutoff reaches the filter, WAV header parses); live run on device 132 @48 k/512 → `underruns=0`, `activeVoices=0` after release; `build/out.wav` = 2.000 s stereo 16-bit, peak 12262/32767. |
| **M2** ✅ | Swing keyboard (mouse click + drag) + computer-key map, full 88-key range, resizable | **Passed 2026-10-15** (PRs #1-#3): click and keys both sound; note-off on release; focus loss + Esc clear every held note; `UiSelfTest` 47 checks incl. all 88 notes clickable at 936x150, 420x80 and 320x60, and exact notes asserted from dispatched mouse events. Scope grew: the plan said one octave of computer keys on a two-octave keyboard; the user asked for the whole piano, then for it to scale with the window. |
| **M3** ✅ | Params live: ADSR, cutoff, resonance, osc mix, master gain | **Passed 2026-10-16** (PR #5): eight sliders under the keyboard, each change event calls `setParam`, so a knob moved during a sustained note is heard immediately. `UiSelfTest`: every slider spans its spec range, tick↔engine round-trips, sliders initialise from `getParam`, every value reaches the engine, two notes survive a full cutoff+level sweep with `underruns == 0`. Slider ticks are 0..1000 mapped by a pure pair; cutoff and the time params use a log scale. |
| **M4a** ✅ | MIDI-in adapter: one `Receiver` → `Engine.noteOn/Off`, plus a **built-in MIDI-file source** (`Sequencer.getTransmitter()`) | **Passed 2026-10-16**: `MidiIn.translate()` is pure, so synthetic `ShortMessage`s and the file source run the same parsing. `UiSelfTest`: note-on/off parsing, velocity-0 note-on treated as note-off, channel filter both ways, control change ignored; a 5-note `.mid` written by the test (`build/test.mid`) emits exactly 10 events with the right notes and velocities (100/60/127/1/90); played through the live engine it drives voices and leaves 0 stuck voices, `underruns == 0`. Two threads firing 200 events each through the wrappers drain to 0 voices with no underruns — the C++ ring stays single-producer because `Engine.noteOn/noteOff` are now `synchronized` wrappers over private natives (§9.4). MIDI notes light the on-screen keyboard via a separate `remote` set, so focus loss and Esc never silence a file that is still playing; a pixel check counts 1897 lit px with a remote note lit vs 0 without. |
| **M4b** *(optional)* | Real MIDI device in (hardware controller, or VMPK + loopMIDI virtual cable) | Device appears in `MidiSystem.getMidiDeviceInfo()` re-scan; live playing works; MIDI + on-screen keyboard coexist. |
| **M5** ✅ | Scope panel (WAV export already shipped with M1) | **Passed 2026-10-16**: `ScopePanel` polls the engine's 1024-sample ring on a 16 ms Swing `Timer` (~60 fps) and paints it auto-scaled. `UiSelfTest`: silence reads flat, a sustained note peaks 0.22, 60 polls cause `underruns == 0` while the note keeps sounding, the timer stops on window close, and the trace paints at 900x120 (1550 wave px) and 320x40 (623 wave px). |
| **M8** ✅ | Scope mode toggle: `wave` (today) vs per-key **reverse waterfall** above the keyboard | **Passed 2026-10-16** (PR #10): `engine_tests` — the voice snapshot is empty before any note, reports note 60 at level 0.3578 mid-attack, keeps rising through the attack, shows both notes when two are held, and is empty after the release. `UiSelfTest` (158 checks) — column level 0.83 for the held note, 0 for an unheld one, 0.0 after release; the column sits over key 60 at 900x120 / 420x80 / 320x40 (x=407/190/145) and not over key 62; the column is 40 rows tall (680/320/240 px); wave mode still paints (4220 px); `underruns == 0` across every poll. Deviation from §6.2: `Adsr::level` was already a public field and the constant is `Synth::kVoices`, so no `level()` accessor and no `kMaxVoices`. |
| **M6** ✅ | Polish: device picker, buffer-size control, quit-cleanup, README | **Passed 2026-10-16** (PRs #12-#15): `OutputPanel` lists only output endpoints (`Engine.parseDevices` drops input-only ones) and names the device plus the buffer size that **actually** opened (`Engine.bufferFrames()`, the value RtAudio leaves in its out-param). Self-test: 8 endpoints on this machine, the picker starts on the default, all 7 others opened and each rendered a note, `underruns` unchanged across 8 stream restarts; 128/256/512/1024 each opened exactly as asked, rendered a note, and caused **0 underruns**. Quit cleanup: `synth.App close` opens the real UI with a `.mid` playing, dispatches `WINDOW_CLOSING`, and must exit unaided — asserted by `build.ps1`, and the assertion bites (commenting `scope.stop()` out gives `HUNG: process did not exit`). README documents build, run, play, modes, layout. UI self-test **186 checks, 0 fail**. Not covered: the 128-vs-1024 latency A/B is a human ear, and no endpoint refused here so the revert branch of `restart()` is untested. |
| **M7** *(optional)* | polyBLEP oscillators, stereo voices, simple delay | Aliasable saw sounds audibly cleaner A/B against M6 build. |

---

### 6.1 M4 without MIDI hardware

`MidiIn` will depend on a `Transmitter`, not on a specific device. That makes the same parsing code
runnable from three sources, cheapest first:

| Tier | Source | Install | What it actually tests |
|---|---|---|---|
| **A — in v1** | `Sequencer.getTransmitter()` playing a `.mid` file | none (pure JDK) | The whole M4 code path: `ShortMessage` parsing, channels, velocity, note-off pairing, engine queue. Sequencer transmitters need no MIDI device at all. |
| **B — synthetic** | Unit test constructing `ShortMessage(0x90, note, vel)` and calling the `Receiver` directly | none | Same parsing, deterministic, runs in CI. |
| **C — optional live** | **VMPK** (virtual piano keyboard, computer keys/mouse) → **loopMIDI** virtual port → opened as a `MidiDevice` by our app | `winget install VMPK.VMPK TobiasErichsen.loopMIDI` (loopMIDI installs the teVirtualMIDI driver → UAC prompt, possibly a re-plug/restart) | Real device enumeration, hot-plug re-scan, live jamming while developing. |

Notes on Tier C, verified against current sources: VMPK exposes MIDI IN/OUT ports but on Windows it
needs a virtual cable (loopMIDI) to reach another process — so it is two installs, not one. There is
a live Windows bug where loopMIDI/teVirtualMIDI ports are not re-discovered without a service
restart or device re-enable (microsoft/MIDI#835), so the M4b re-scan must be a manual "refresh"
button, not only an event listener. Windows' own MIDI stack (Win11 24H2) added native MIDI
improvements but still ships no virtual cable.

Do **not** bother with: phone-as-controller over network MIDI (Windows network-MIDI support is weak),
or `MidiSystem.getTransmitter()` as the default path — it throws `MidiUnavailableException` when no
input device exists, which is exactly this machine's current state.

### 6.2 M8 — scope mode toggle: wave vs per-key reverse waterfall

Pulled ahead of M6 by the user. The strip above the keyboard gets a toggle. `wave` is today's
oscilloscope. `waterfall` is one column per key, aligned with the key under it: the newest row sits at
the keyboard edge and history scrolls up and away. Column brightness = how loud that note is now.

**Native — the only C++ change.** `Voice` knows its `note()`, but its envelope level is only reachable
through `render()`, and the audio thread owns those fields. So the callback snapshots per-voice state
into atomics next to the scope ring:

```cpp
std::atomic<int>   voiceNote_[kMaxVoices];
std::atomic<float> voiceLevel_[kMaxVoices];
```

written **once per block** (16 slots, not per sample) and read by
`getVoiceLevels(int[] notes, float[] levels)` → number of active voices. `Adsr` gains a
`level() const` accessor. Race-free by construction, same trick as the scope ring (§8.14).

**Java.** `ScopePanel` keeps its single 16 ms timer and gains `setMode(WAVE | WATERFALL)`:
- poll `getVoiceLevels` and fold it into `float[88] col` (note 21..108 → index), `max()` when two
  voices share a column
- push `col` into a history ring of `rows × 88` floats, `rows` = panel height, so one row per pixel
  row: ~120 rows ≈ 1.9 s at 16 ms
- paint rows bottom-up (y = height-1 is newest), brightness from level, blue ramp; column x comes from
  `KeyboardPanel`'s geometry helpers (`whiteWidth`, `leftOfNote`, `blackLeft`, `blackWidth`) so a
  column sits exactly over its key at every window size
- notes outside A0–C8 get no column — the keyboard's rule already

**Toggle.** one `JToggleButton("waterfall")` in a one-line header above the strip. `ScopePanel` is the
only thing that changes mode; keyboard, knobs and MIDI path stay untouched.

**Acceptance.**
- `engine_tests`: `noteOn(60)` + `renderBlock` → one active voice, note 60, level > 0 and rising
  through the attack; after `noteOff` + `renderBlock` → zero active. Deterministic offline synth, no
  wall clock.
- `UiSelfTest`: `col(57) > 0` after key `a`; the column decays to 0 after release; waterfall mode
  paints lit pixels for a held note and none when silent (pixel proof, as with the key lights); the
  toggle switches mode and both modes paint; the column x for notes 21/60/108 equals the key x at
  three window sizes (reuse the existing geometry assertions).
- `underruns == 0` with the extra per-frame read (32 atomic loads + one JNI copy — R9).

**Risks.** R9 (EDT poll — bounded and tiny). `max()` hides two-voice detail inside one column
(documented, not a bug). History length tracks window height, not wall time.

---

## 7. Risks and known traps

| ID | Risk | Mitigation baked into plan |
|---|---|---|
| **R1** | MinGW + RtAudio WASAPI link errors (`KSDATAFORMAT_SUBTYPE_*`, `MFStartup`, `mfuuid`, `wmcodecdspuuid`) — long-standing (thestk/rtaudio#10, #53, #184; VCV fork patched it) | Link `ksuser mfplat mfuuid wmcodecdspuuid` explicitly (§5.1); DirectSound fallback flag if it still fails. |
| **R2** | RtAudio **6.x removed C++ exceptions** and changed device selection/IDs (6.0.0 release notes). Tutorials on the internet use 4.x/5.x `RtAudioError` exceptions and cached device indices. **Confirmed live:** `getDeviceInfo(index)` with a 0-based index returns a blank `DeviceInfo` instead of failing, and real IDs on this box were 129–137 | Pin 6.0.1; check `RtAudioErrorType` returns everywhere; drive everything from `getDeviceIds()`; never cache an ID across `RtAudio` instances (IDs are per-instance). |
| **R10** | Default-device ambiguity: this machine exposes 9 WASAPI endpoints (HDMI, VB-Cable, AudioRelay, Steam Streaming) | Never silently pick device 0. M6 device picker; default to `getDefaultOutputDevice()` but show which device is live in the UI. |
| **R3** | RT-callback stalls → dropouts | No alloc/lock/IO/JNI in callback; atomics + SPSC; underrun counter exposed for measurement. |
| **R4** | JNI local-reference or string-conversion churn | Strings converted per call, never per block; no JNI from audio thread at all. |
| **R5** | Missing MinGW runtime DLLs at `System.loadLibrary` | `-static-libgcc -static-libstdc++`; tests built `-static`. |
| **R6** | 32/64-bit mismatch | Temurin is 64-bit; MinGW-w64 `x86_64-ucrt` — verified matching. |
| **R7** | Naive oscillators alias (harsh saw/square) | Explicitly deferred to M7; not treated as a v1 bug. |
| **R8** | WASAPI shared mode ≠ ASIO latency | Accept ~10–30 ms in v1; ASIO backend is a later `RtAudio` define, not a rewrite. |
| **R9** | Swing EDT blocked by native call | All native setters are O(1) queue writes; `renderWav` runs off-EDT (`SwingWorker`). |
| **R11** | Resonant-filter instability: the planned Chamberlin SVF produced NaN near 18 kHz / Q 1.2 | Swapped to an RBJ biquad (coefficients set per block). `engine_tests` asserts finiteness at max cutoff + max resonance. |
| **R12** | Stuck voices on focus loss / panic | **Handled in the keyboard layer:** held notes live in one Java-side set; focus loss and Esc send `noteOff` for every one; `activeVoices()` proves the drain (`engine drains to 0 voices`). Key auto-repeat cannot retrigger. |

---

## 8. Decisions taken (were open questions — user delegated, 2026-10-15)

1. **Effects in v1: none.** Clean chain (voices → filter → gain). A stereo delay is ~40 lines and
   makes demos sound better, but it widens the DSP surface before M1 is even proven. Held to M7.
2. **Stereo out, mono voices summed to both buses.** No per-voice panning in v1.
3. **MIDI source without hardware** — see §6.1. M4a (MIDI-file source) is in v1; M4b (real or
   virtual MIDI device) is optional. **Tier C installs (VMPK + loopMIDI) skipped for now** — they
   add a kernel driver and a Windows re-discovery bug to test a code path Tier A covers for free.
4. **Note-event producers: one Java-side `synchronized` enqueue wrapper.** EDT and the MIDI
   receiver both call it; C++ keeps a single-producer ring. Chosen over an MPMC queue because it
   is less code and the queue stays RT-trivial.
5. **Licence: MIT** (RtAudio is MIT). `LICENSE` landed in the first commit.
6. **Repo is PUBLIC** (`git ls-remote` succeeds anonymously). `main` and `develop` are on origin;
   feature branches go through PRs.
7. **Computer-key map** is the tracker layout — `a w s e d f t g y h u j k o l p ;` — position in
   that string *is* the semitone above C4. One string, no table to keep in sync. Left/right arrows
   shift that 17-semitone window (clamped so it never leaves the piano), Esc kills all notes.
   The whole keyboard is always visible: 52 white keys, 936 px preferred width, no scrolling, and a
   blue bar under the keys shows where the computer-key window currently sits.
8. **Focus loss sends note-off for every held note** (risk R12). Held notes live in one Java-side
   `LinkedHashSet`, which also turns key auto-repeat into a no-op instead of a retrigger.
9. **UI is tested headlessly** (`synth.UiSelfTest`): key map, pixel→note hit-testing, mouse drag,
   focus-loss clear, then the engine's `activeVoices()`. It also walks every white and black key and
   asserts all 88 notes A0-C8 are reachable by click. AWT swallows synthetic `FocusEvent`s, so
   the test calls the registered `FocusListener` directly. Its `png` mode renders the keyboard to
   `build/keyboard.png`; geometry was checked by reading pixel runs, not by eyeballing.
10. **Keyboard geometry is a function of the component size**, not constants: key width, black-key
    width/height, label size and the marker bar all come from `getWidth()`/`getHeight()`, and
    painting and hit-testing call the same helpers — so resizing the window cannot desync what you
    see from what you click. Minimum size 320x60. Consequence worth knowing: a black key is centred
    exactly on the boundary between two white keys, so the pixel under its left half is the lower
    white key and under its right half is the upper one.
11. **WAV export arrived early.** `renderWav` shipped with the engine and was verified by parsing
    the bytes (2.000 s stereo 16-bit, peak 12262/32767), so M5 is scope-only.
12. **C++ is split `synth_core` (static) + `synthjni` (shared).** Not in the original plan: the
    offline tests link `synth_core`, so they never pull in `jvm.lib` and need no JVM to run.
13. **`build.ps1` pins the toolchain** to `C:/Strawberry/c/bin` (g++ 13.2 + CMake 3.29). Two MinGW
    installs are on PATH; unpinned, the build silently picks g++ 15.2 + CMake 4.3.
14. **Waterfall columns read a per-block atomic snapshot, not the live voices.** The audio callback
    writes `voiceNote_[16]` / `voiceLevel_[16]` once per block and JNI reads those. Reading `Voice`
    fields directly would be a data race on plain `int`/`float`. Cost: 32 relaxed atomics per
    512-frame block, no lock, nothing added to the per-sample path.
15. **A waterfall column is the max level of the voices holding that note.** Voice stealing can put
    two notes in one column; `max()` keeps it honest about loudness instead of flickering between
    them.
16. **The waterfall scrolls up from the keyboard edge** — newest row at the keys, history leaving
    through the top — because the strip sits above the keys. History length is measured in pixel rows,
    not seconds, so a taller window shows more history. Deliberate.

## 9. Explicitly out of scope for v1

Piano roll / timeline editing, plugin formats (VST/AU), preset library UI, sample playback,
FM/wavetable synthesis, Linux/macOS CI, JUCE migration, Gradle/Maven packaging, ASIO,
reverb, automation lanes, controller mapping beyond MIDI notes.

---

## 10. Git workflow (gitflow + Conventional Commits)

Agreed 2026-10-15. Remote: `https://github.com/AgentDudu/audio-synthesizer.git` (public).

| Branch | Role |
|---|---|
| `main` | Releases only. Every merge in gets a tag (`v0.1.0`). |
| `develop` | Integration branch. All day-to-day work lands here. |
| `feature/<slug>` | One unit of work = one branch, cut from `develop` (`feature/full-keyboard`, `feature/resize-keyboard`). No milestone/phase labels in branch names. |
| `release/x.y.z` | Cut from `develop` when a milestone set is shippable; fix-only changes, then merge to `main` + tag, then back-merge `main` → `develop`. |
| `hotfix/x.y.z` | Emergency fix from `main`; merge to both `main` and `develop`. |

Rules:

- Feature → `develop` with `git merge --no-ff feature/...` so the branch stays visible in history.
- Rebase only on your own unpushed feature branch. Never rebase `main` or `develop`.
- Commits follow [Conventional Commits](https://www.conventionalcommits.org):
  `type(scope): subject` — imperative mood, ≤72 chars, body explains *why*.
  Types in use: `feat`, `fix`, `docs`, `test`, `refactor`, `perf`, `chore`, `build`.
  Scope is the area: `engine`, `dsp`, `jni`, `ui`, `midi`, `build`, `docs`.
  **Subjects never name a milestone or phase** (`feat(ui): scale the keyboard with the window`,
  not `feat: M2 keyboard`). Milestone labels live in PLAN.md only.
  Breaking changes: `feat(jni)!: ...` plus a `BREAKING CHANGE:` footer.
- Infra/docs-only commits may go straight to `develop`; anything that changes behaviour goes
  through a feature branch **and a PR** — merging is an explicit user decision.

The flow in practice (PRs #1-#3):

```bash
git switch -c feature/resize-keyboard develop
# ... commit as you go: feat(ui): scale the keyboard with the window
git push -u origin feature/resize-keyboard
gh pr create --base develop --head feature/resize-keyboard   # evidence goes in the body
gh pr merge --merge --delete-branch                          # only after the user approves
```

Current state (2026-10-16): `main` `c71e122` (tag `v0.5.0`), cut as `release/0.5.0` from `develop`
`b846a63`; `develop` is the back-merge of `main`, so both hold the same content. Tags `v0.1.0` (`b356b3c`), `v0.2.0` (`d87f3d6`),
`v0.3.0` (`f676390`) and `v0.4.0` (`b3cf2a7`) exist, so the "every merge to `main` gets a tag" rule
holds; the earlier gap was closed by cutting `v0.1.0` retroactively at the commit that was already on
`main`. PRs #1-#15 merged. No `release/*` branch survives on origin — deleting a remote ref is
blocked by the agent's safety net, so the user removed them by hand. M0/M1 commits predate the
message rules and stay as they are — no history rewrite on pushed branches.
