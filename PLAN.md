# Audio Synthesizer / DAW — Build Plan

Java (Swing) UI ⇄ JNI ⇄ C++ audio engine (RtAudio). Windows-first, designed so Linux/macOS
later is a CMake flag change, not a rewrite.

Status: **M0 + M1 done** (M0 `5eb7d3c`) — engine plays through WASAPI with 0 underruns, offline
WAV bounce verified. Next: M2 (Swing keyboard).

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
  through one Java-side synchronized enqueue, or make the queue MPMC — decision Q3.

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
      audio_engine.hpp  synth.hpp  voice.hpp  dsp.hpp  spsc_queue.hpp  wav_writer.hpp
    src/
      jni_bridge.cpp             # the ONLY file that knows about JNIEnv
      audio_engine.cpp  synth.cpp  voice.cpp  dsp.cpp  wav_writer.cpp
    tests/
      engine_tests.cpp           # assert-based, no framework, no audio device needed
  java/src/main/java/synth/
    App.java            Engine.java        # Engine = all `native` declarations + loadLibrary
    KeyboardPanel.java  KnobPanel.java  ScopePanel.java  MidiIn.java
  java/src/test/java/synth/
    EngineSmokeTest.java         # headless: load DLL, render, assert non-silent
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
    public static native String start(int deviceId, int sampleRate, int bufferFrames);
    public static native void stop();
    public static native void noteOn(int note, int velocity);   // MIDI channel is filtered in Java
    public static native void noteOff(int note);
    public static native void setParam(int paramId, float value);
    public static native float getParam(int paramId);
    public static native int getScope(float[] out);              // last out.length frames
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
set(CMAKE_CXX_STANDARD 20)

find_package(JNI REQUIRED)                 # uses $ENV{JAVA_HOME} / JAVA_HOME
add_subdirectory(third_party/rtaudio)      # RtAudio 6.0.1

add_library(synthjni SHARED
  src/jni_bridge.cpp src/audio_engine.cpp src/synth.cpp
  src/voice.cpp src/dsp.cpp src/wav_writer.cpp)
target_include_directories(synthjni PRIVATE ${JNI_INCLUDE_DIRS} include)
target_compile_definitions(synthjni PRIVATE __WINDOWS_WASAPI__ WIN32_LEAN_AND_MEAN NOMINMAX)
target_link_libraries(synthjni PRIVATE rtaudio)

if (MINGW)
  # WASAPI under MinGW-w64 needs the Media Foundation + KS libs (thestk/rtaudio#184)
  target_link_libraries(synthjni PRIVATE ksuser mfplat mfuuid wmcodecdspuuid ole32 uuid)
  # ship without libgcc/libstdc++/winpthread DLLs on PATH
  target_link_options(synthjni PRIVATE -static-libgcc -static-libstdc++)
endif()

enable_testing()
add_executable(engine_tests tests/engine_tests.cpp)
target_link_options(engine_tests PRIVATE -static)   # run without MinGW on PATH
add_test(NAME engine_tests COMMAND engine_tests)
```

Verified on this machine (2026-10-15): the WASAPI block above compiles and links with MinGW as
written, and enumerates 9 devices. Fallback, if a future toolchain breaks it: swap
`__WINDOWS_WASAPI__` → `__WINDOWS_DS__` and link `dsound` **plus `winmm`** (the DS backend calls
`timeBeginPeriod`/`timeEndPeriod`; without `winmm` the link fails on `__imp_timeEndPeriod`).
Same API above the `RtAudio` abstraction, slightly worse latency.

### 5.2 `build.ps1` (single command, ordered)

```powershell
javac -h native/include -d build/classes (Get-ChildItem -Recurse java/src/main/java -Filter *.java).FullName
cmake -S native -B build/native -G "MinGW Makefiles"
cmake --build build/native
java -Djava.library.path=build/native -cp build/classes synth.App
```

Test entry: `.\build.ps1 -Target test` → runs `ctest` + `EngineSmokeTest`.

---

## 6. Milestones (each has a pass check; do not stack)

| M | Deliverable | Acceptance check |
|---|---|---|
| **M0** ✅ | `git init`, vendored RtAudio, CMake builds `synthjni.dll`, Java loads it | **Passed 2026-10-15:** `probeDevices()` printed `rtaudio 6.0.1 api=7`, 9 WASAPI devices, default out = `Speakers (Realtek)` id=132. |
| **M1** ✅ | Engine: WASAPI stream, note-on/off, 16-voice poly, sine/saw voice | **Passed 2026-10-15:** `ctest` 100% (no NaN, audible 1 s render, release silent, 20 notes → ≤16 voices, cutoff reaches the filter, WAV header parses); live run on device 132 @48 k/512 → `underruns=0`, `activeVoices=0` after release; `build/out.wav` = 2.000 s stereo 16-bit, peak 12262/32767. |
| **M2** | Swing keyboard (mouse click) + computer-key map (`awsedftgyhujk` = one octave) | Click and keys both sound; note-off on key release; no stuck notes after window focus loss. |
| **M3** | Params live: ADSR, cutoff, resonance, osc mix, master gain | Dragging a knob while a note sustains changes sound with no click; params read back correctly. |
| **M4a** | MIDI-in adapter: one `Receiver` → `Engine.noteOn/Off`, plus a **built-in MIDI-file source** (`Sequencer.getTransmitter()`) | No hardware needed. A `.mid` file drives the engine; note-on/off pairs balanced, velocity respected, channel filtering correct. Unit-testable with synthetic `ShortMessage`s. |
| **M4b** *(optional)* | Real MIDI device in (hardware controller, or VMPK + loopMIDI virtual cable) | Device appears in `MidiSystem.getMidiDeviceInfo()` re-scan; live playing works; MIDI + on-screen keyboard coexist. |
| **M5** | Scope panel + WAV export | 60 fps scope from `getScope`; `renderWav` file opens in a player, header parses, duration matches ±10 ms. |
| **M6** | Polish: device picker, buffer-size control, quit-cleanup, README | Clean exit with no orphan stream; README has build+run for a stranger. |
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
| **R12** | Stuck voices on focus loss / panic — not yet handled | M2 must call `noteOff` on focus loss and expose an all-notes-off; `activeVoices()` is already exported to prove it. |

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
6. **Repo is PUBLIC** (`git ls-remote` succeeds anonymously). Not pushed yet — pushing publishes
   the code, so that stays an explicit user action.

---

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
| `feature/<milestone>-<slug>` | One milestone = one branch, cut from `develop` (e.g. `feature/m2-swing-keyboard`). |
| `release/x.y.z` | Cut from `develop` when a milestone set is shippable; fix-only changes, then merge to `main` + tag, then back-merge `main` → `develop`. |
| `hotfix/x.y.z` | Emergency fix from `main`; merge to both `main` and `develop`. |

Rules:

- Feature → `develop` with `git merge --no-ff feature/...` so the branch stays visible in history.
- Rebase only on your own unpushed feature branch. Never rebase `main` or `develop`.
- Commits follow [Conventional Commits](https://www.conventionalcommits.org):
  `type(scope): subject` — imperative mood, ≤72 chars, body explains *why*.
  Types in use: `feat`, `fix`, `docs`, `test`, `refactor`, `perf`, `chore`, `build`.
  Scope is the area: `engine`, `dsp`, `jni`, `ui`, `midi`, `build`, `docs`.
  Breaking changes: `feat(jni)!: ...` plus a `BREAKING CHANGE:` footer.
- Infra/docs-only commits may go straight to `develop`; anything that changes behaviour goes
  through a feature branch.

M2 example:

```bash
git switch -c feature/m2-swing-keyboard develop
# ... commit as you go: feat(ui): add on-screen keyboard with mouse input
git switch develop && git merge --no-ff feature/m2-swing-keyboard && git push
```

Current state: `main` and `develop` both at `b08ee54` (M0 `5eb7d3c` + M1 `b08ee54`, pushed
2026-10-15). M0/M1 predate the branch model and stay as they are — no history rewrite.
