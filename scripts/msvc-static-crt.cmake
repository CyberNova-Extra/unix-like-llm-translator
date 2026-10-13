# CMake toolchain file for Windows (MSVC) builds with the static C runtime (/MT),
# so translate.exe runs without the VC++ redistributable. Used via the
# CMAKE_TOOLCHAIN_FILE environment variable together with
# RUSTFLAGS="-C target-feature=+crt-static" and LLAMA_STATIC_CRT=1.
#
# llama.cpp's CMake files enable policy CMP0091, under which the runtime library
# comes from CMAKE_MSVC_RUNTIME_LIBRARY (default: the DLL runtime, /MD) and the
# /MT that cmake-rs adds to the compiler flags is ignored; mixing the two fails
# to link with unresolved __imp_* CRT symbols.
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
