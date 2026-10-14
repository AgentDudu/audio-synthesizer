cmake_minimum_required(VERSION 3.16)
project(synthjni CXX)
set(CMAKE_CXX_STANDARD 17)

find_package(JNI REQUIRED)

# Vendored RtAudio 6.0.1: static (no extra runtime DLL), WASAPI only, no upstream test programs.
set(RTAUDIO_BUILD_STATIC_LIBS ON   CACHE BOOL "" FORCE)
set(RTAUDIO_API_WASAPI ON          CACHE BOOL "" FORCE)
set(RTAUDIO_API_DS OFF             CACHE BOOL "" FORCE)
set(RTAUDIO_API_ASIO OFF           CACHE BOOL "" FORCE)
set(RTAUDIO_BUILD_TESTING OFF      CACHE STRING "" FORCE)
add_subdirectory(third_party/rtaudio)

add_library(synthjni SHARED
  src/jni_bridge.cpp
  src/audio_engine.cpp)

# System.loadLibrary("synthjni") looks for synthjni.dll; MinGW would prefix "lib".
set_target_properties(synthjni PROPERTIES PREFIX "")

target_include_directories(synthjni PRIVATE include ${JNI_INCLUDE_DIRS})
# rtaudio compiles its backend define PRIVATEly, so the consumer must define it too.
target_compile_definitions(synthjni PRIVATE __WINDOWS_WASAPI__ WIN32_LEAN_AND_MEAN NOMINMAX)
target_link_libraries(synthjni PRIVATE rtaudio ${JNI_LIBRARIES})

if (MINGW)
  # WASAPI under MinGW-w64 (thestk/rtaudio#184); winmm/user32 come from rtaudio's own list.
  target_link_libraries(synthjni PRIVATE ksuser mfplat mfuuid wmcodecdspuuid ole32 uuid winmm user32)
  target_link_options(synthjni PRIVATE -static-libgcc -static-libstdc++)
endif()
