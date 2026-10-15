# Single entry point (PLAN.md §5.2). Usage: .\build.ps1 [-Target probe|test]
param([string]$Target = 'probe')
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

# Two MinGW toolchains sit on PATH (C:\mingw64 = g++ 15.2 / CMake 4.3, C:\Strawberry = g++ 13.2 / CMake 3.29).
# Pin the pair the plan was validated against, so the build is reproducible.
$gccDir  = 'C:/Strawberry/c/bin'
$cmake   = if (Test-Path "$gccDir/cmake.exe") { "$gccDir/cmake.exe" } else { 'cmake' }

New-Item -ItemType Directory -Force -Path build/classes, native/include | Out-Null

# javac -h regenerates native/include/synth_Engine.h; must run before CMake compiles the bridge.
$sources = Get-ChildItem -Recurse java/src/main/java -Filter *.java | ForEach-Object { $_.FullName }
javac -h native/include -d build/classes $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

& $cmake -S native -B build/native -G "MinGW Makefiles" -DCMAKE_BUILD_TYPE=Release `
    -DCMAKE_CXX_COMPILER="$gccDir/g++.exe" -DCMAKE_MAKE_PROGRAM="$gccDir/mingw32-make.exe"
if ($LASTEXITCODE -ne 0) { throw "cmake configure failed" }
# clangd only looks for compile_commands.json in the source tree (or a build/ subdir of it).
Copy-Item build/native/compile_commands.json compile_commands.json -Force
& $cmake --build build/native
if ($LASTEXITCODE -ne 0) { throw "cmake build failed" }

if ($Target -eq 'probe' -or $Target -eq 'run' -or $Target -eq 'smoke') {
    java "-Djava.library.path=$PSScriptRoot/build/native" -cp build/classes synth.App $Target
    if ($LASTEXITCODE -ne 0) { throw "java failed" }
}
if ($Target -eq 'test') {
    & "$gccDir/ctest.exe" --test-dir build/native --output-on-failure
    if ($LASTEXITCODE -ne 0) { throw "ctest failed" }
    java "-Djava.library.path=$PSScriptRoot/build/native" -cp build/classes synth.UiSelfTest
    if ($LASTEXITCODE -ne 0) { throw "UI self-test failed" }
}
