cmake_minimum_required(VERSION 3.16)
project(synthjni CXX)
set(CMAKE_CXX_STANDARD 17)
# clangd reads this; build.ps1 copies it to the repo root where clangd looks for it.
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)
# MinGW make writes includes to a @file.rsp, which clangd does not expand, so keep -I inline.
set(CMAKE_CXX_USE_RESPONSE_FILE_FOR_INCLUDES OFF)

find_package(JNI REQUIRED)

# Vendored RtAudio 6.0.1: static (no extra runtime DLL), WASAPI only, no upstream test programs.
set(RTAUDIO_BUILD_STATIC_LIBS ON   CACHE BOOL "" FORCE)
set(RTAUDIO_API_WASAPI ON          CACHE BOOL "" FORCE)
set(RTAUDIO_API_DS OFF             CACHE BOOL "" FORCE)
set(RTAUDIO_API_ASIO OFF           CACHE BOOL "" FORCE)
set(RTAUDIO_BUILD_TESTING OFF      CACHE STRING "" FORCE)
add_subdirectory(third_party/rtaudio)

# Engine + DSP, shared by the JNI DLL and the offline tests. Kept separate so the tests do not
# inherit a jvm.dll dependency from the JNI layer.
add_library(synth_core STATIC
  src/audio_engine.cpp
  src/synth.cpp
  src/voice.cpp
  src/wav_writer.cpp)
target_include_directories(synth_core PUBLIC include)
# rtaudio compiles its backend define PRIVATEly, so every consumer must define it too.
target_compile_definitions(synth_core PUBLIC __WINDOWS_WASAPI__ WIN32_LEAN_AND_MEAN NOMINMAX)
target_link_libraries(synth_core PUBLIC rtaudio)

add_library(synthjni SHARED src/jni_bridge.cpp)
# System.loadLibrary("synthjni") looks for synthjni.dll; MinGW would prefix "lib".
set_target_properties(synthjni PROPERTIES PREFIX "")
target_include_directories(synthjni PRIVATE ${JNI_INCLUDE_DIRS})
target_link_libraries(synthjni PRIVATE synth_core ${JNI_LIBRARIES})

if (MINGW)
  # WASAPI under MinGW-w64 (thestk/rtaudio#184); winmm/user32 also come from rtaudio's own list.
  target_link_libraries(synth_core PUBLIC ksuser mfplat mfuuid wmcodecdspuuid ole32 uuid winmm user32)
  target_link_options(synthjni PRIVATE -static-libgcc -static-libstdc++)
endif()

# Offline DSP tests: no device, no JVM. Built -static because dynamically linked MinGW exes
# fail to start from MSYS bash on this box (exit 127).
enable_testing()
add_executable(engine_tests tests/engine_tests.cpp)
target_link_libraries(engine_tests PRIVATE synth_core)
if (MINGW)
  target_link_options(engine_tests PRIVATE -static)
endif()
add_test(NAME engine_tests COMMAND engine_tests)
