#
# Builds x265 plus the KMP C shim as one static archive, and (optionally) the
# JNI shared library or the WebAssembly module.
#
#   cmake -S jni -B build -DBUILD_JNI=ON               desktop JVM / Android JNI
#   cmake -S jni -B build -DBUILD_JNI=OFF              static archive only
#   emcmake cmake -S jni -B build-wasm -DBUILD_WASM=ON WebAssembly
#
# The archive is embedded into every Kotlin/Native cinterop klib. The JNI and
# WebAssembly targets link the same x265_kmp.cc implementation, so all platforms
# execute identical argument handling.
#
# X265_DIR points at the x265 checkout to compile. The default is the git
# submodule next to this directory; the web build passes a patched copy because
# x265 drives its frame encoders through pthreads, which Emscripten targets do
# not have (see patches/x265-single-threaded-wasm.patch).
#
cmake_minimum_required(VERSION 3.20)

project(x265_kmp C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(BUILD_JNI "Build the JNI shared library" OFF)
option(BUILD_WASM "Build the standalone WebAssembly module" OFF)

set(X265_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../x265" CACHE PATH "x265 checkout to build")
if(NOT EXISTS "${X265_DIR}/source/CMakeLists.txt")
  message(FATAL_ERROR "x265 sources are missing at ${X265_DIR}")
endif()

# x265 defaults. The CLI, the shared library, its tests and the optional HDR10+
# dynamic library are not part of the binding; only the static archive is.
set(ENABLE_CLI OFF CACHE BOOL "" FORCE)
set(ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTS OFF CACHE BOOL "" FORCE)
set(ENABLE_HDR10_PLUS OFF CACHE BOOL "" FORCE)
set(EXPORT_C_API ON CACHE BOOL "" FORCE)
set(ENABLE_LIBNUMA OFF CACHE BOOL "" FORCE)
set(ENABLE_LIBVMAF OFF CACHE BOOL "" FORCE)
set(ENABLE_VTUNE OFF CACHE BOOL "" FORCE)
set(ENABLE_PPA OFF CACHE BOOL "" FORCE)

# Emscripten's platform module reports the host architecture (x86_64 on an Intel
# machine), which would send x265 down its -march=i686 x86 path. wasm32 is the
# truth cmake needs here: the target has no x86/ARM SIMD variant.
if(EMSCRIPTEN OR CMAKE_SYSTEM_NAME STREQUAL "Emscripten")
  set(CMAKE_SYSTEM_PROCESSOR "wasm32")
endif()

# Architecture as x265 sees it (CMAKE_OSX_ARCHITECTURES wins on Apple hosts,
# otherwise a cross build would select the host's SIMD paths).
set(XKY_TARGET_ARCH "${CMAKE_SYSTEM_PROCESSOR}")
if(APPLE AND CMAKE_OSX_ARCHITECTURES)
  set(XKY_TARGET_ARCH "${CMAKE_OSX_ARCHITECTURES}")
endif()
string(TOLOWER "${XKY_TARGET_ARCH}" XKY_TARGET_ARCH)

if(XKY_TARGET_ARCH MATCHES "^x86|amd64|i[3-6]86" AND CMAKE_SIZEOF_VOID_P EQUAL 4)
  # x265's 32-bit x86 assembly calls through an absolute PLT entry
  # (`call [rel sym wrt ..plt]`), which NASM cannot encode for ELF. The 32-bit
  # ABIs are emulator-only, so portable C++ is the right trade-off.
  set(ENABLE_ASSEMBLY OFF CACHE BOOL "" FORCE)
  message(STATUS "x265 assembly disabled for 32-bit x86")
elseif(XKY_TARGET_ARCH MATCHES "^x86|amd64|i[3-6]86")
  # x265 compiles its x86 assembly through a raw `nasm` custom command whose
  # object format flag lives in NASM_FLAGS. Set it before the subdirectory is
  # configured; without it nasm would emit a flat binary.
  find_program(XKY_NASM_EXECUTABLE nasm)
  if(XKY_NASM_EXECUTABLE)
    if(CMAKE_SYSTEM_NAME STREQUAL "Windows")
      set(XKY_NASM_FORMAT win64)
    elseif(APPLE)
      set(XKY_NASM_FORMAT macho64)
    elseif(CMAKE_SIZEOF_VOID_P EQUAL 4)
      set(XKY_NASM_FORMAT elf32)
    else()
      set(XKY_NASM_FORMAT elf64)
    endif()
    set(NASM_FLAGS -f ${XKY_NASM_FORMAT})
    set(ENABLE_ASSEMBLY ON CACHE BOOL "" FORCE)
    message(STATUS "x265 x86 assembly enabled with nasm (${XKY_NASM_FORMAT})")
  else()
    set(ENABLE_ASSEMBLY OFF CACHE BOOL "" FORCE)
    message(STATUS "nasm not found; x265 x86 assembly disabled")
  endif()
elseif(XKY_TARGET_ARCH MATCHES "^(aarch64|arm64|arm64e|armv6l|armv7l|riscv64)$")
  # ARM/ARM64/RISC-V assembly is compiled by x265 with the C/C++ compiler.
  # The names are exactly the aliases x265 itself recognises: anything else
  # (armv7-a from the NDK, for example) makes it fall back to portable C++, and
  # enabling assembly here would leave setupAssemblyPrimitives undefined.
  set(ENABLE_ASSEMBLY ON CACHE BOOL "" FORCE)
else()
  # wasm32 and anything else: portable C++ only.
  set(ENABLE_ASSEMBLY OFF CACHE BOOL "" FORCE)
endif()

# Emscripten runs without pthreads: the patch in patches/ turns x265's frame
# encoder thread into an inline call when this is defined. The definition has to
# reach the `common` and `encoder` object libraries, so it is directory-wide
# rather than target-specific.
if(EMSCRIPTEN OR CMAKE_SYSTEM_NAME STREQUAL "Emscripten")
  add_compile_definitions(XKY_SINGLE_THREADED=1)
endif()

add_subdirectory("${X265_DIR}/source" "${CMAKE_CURRENT_BINARY_DIR}/x265" EXCLUDE_FROM_ALL)

target_sources(x265-static PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/x265_kmp.cc")
target_include_directories(x265-static
  PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/include"
  PRIVATE
    "${X265_DIR}/source"
    "${CMAKE_CURRENT_BINARY_DIR}/x265"
)
# The shim is C++17 while x265 itself pins gnu++98; pin the standard last so a
# target-wide flag cannot downgrade the translation unit.
set_source_files_properties("${CMAKE_CURRENT_SOURCE_DIR}/x265_kmp.cc"
  TARGET_DIRECTORY x265-static
  PROPERTIES COMPILE_FLAGS "-std=c++17")

# The archive name is what the cinterop `-staticLibrary` flag and the JNI link
# line expect: libx265_kmp.a on Unix/MinGW.
set_target_properties(x265-static PROPERTIES OUTPUT_NAME x265_kmp)

option(XKY_LEGACY_GLIBC "Provide symbols missing from Kotlin/Native's Linux sysroot" OFF)
if(XKY_LEGACY_GLIBC AND CMAKE_SYSTEM_NAME STREQUAL "Linux")
  # Kotlin/Native links Linux programs against an older glibc than the one the
  # archive is compiled with; see the file for the C23 entry points it needs.
  target_sources(x265-static PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/x265_linux_compat.c")
  set_source_files_properties("${CMAKE_CURRENT_SOURCE_DIR}/x265_linux_compat.c"
    TARGET_DIRECTORY x265-static
    PROPERTIES COMPILE_FLAGS "-std=c11")
endif()

if(EMSCRIPTEN OR CMAKE_SYSTEM_NAME STREQUAL "Emscripten")
  # Emscripten has no POSIX named semaphores; x265's shared-statistics ring
  # buffer references them even though this binding never enables multi-pass.
  target_sources(x265-static PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/x265_wasm_compat.c")
  # x265 pins all its translation units to gnu++98, which a C compiler rejects.
  set_source_files_properties("${CMAKE_CURRENT_SOURCE_DIR}/x265_wasm_compat.c"
    TARGET_DIRECTORY x265-static
    PROPERTIES COMPILE_FLAGS "-std=c11")
endif()

if(UNIX)
  find_package(Threads REQUIRED)
  target_link_libraries(x265-static PUBLIC Threads::Threads)
endif()

if(BUILD_JNI)
  add_library(x265_jni SHARED "${CMAKE_CURRENT_SOURCE_DIR}/x265_jni.cpp")
  target_link_libraries(x265_jni PRIVATE x265-static)
  set_target_properties(x265_jni PROPERTIES OUTPUT_NAME x265_jni)

  if(WIN32)
    # MinGW names shared libraries lib*.dll by default; the Kotlin loader uses
    # the unprefixed x265_jni.dll.
    set_target_properties(x265_jni PROPERTIES PREFIX "")
    # Keep the JNI library self-contained: no MinGW runtime DLLs (libgcc,
    # libstdc++, libwinpthread) on the load path of a consumer JVM.
    target_link_options(x265_jni PRIVATE -static -static-libgcc -static-libstdc++)
  endif()

  if(ANDROID)
    find_library(log-lib log)
    if(log-lib)
      target_link_libraries(x265_jni PRIVATE ${log-lib})
    endif()
    # 16 KB page-size devices (Android 15+).
    target_link_options(x265_jni PRIVATE
      "-Wl,-z,max-page-size=16384"
      "-Wl,-z,common-page-size=16384"
      # x265's AArch64 assembly addresses global symbols directly; bind them
      # locally so the shared library does not need preemptible relocations.
      "-Wl,-Bsymbolic"
    )
  else()
    if(JNI_INCLUDE_DIR)
      target_include_directories(x265_jni PRIVATE "${JNI_INCLUDE_DIR}")
    endif()
    if(JNI_INCLUDE_DIR_PLATFORM)
      target_include_directories(x265_jni PRIVATE "${JNI_INCLUDE_DIR_PLATFORM}")
    endif()
    if(NOT JNI_INCLUDE_DIR)
      find_package(JNI REQUIRED)
      target_include_directories(x265_jni PRIVATE ${JNI_INCLUDE_DIRS})
    endif()
  endif()
endif()

if(BUILD_WASM OR EMSCRIPTEN)
  add_executable(x265_wasm "${CMAKE_CURRENT_SOURCE_DIR}/x265_wasm_entry.c")
  target_link_libraries(x265_wasm PRIVATE x265-static)
  set_target_properties(x265_wasm PROPERTIES SUFFIX ".wasm")
  target_link_options(x265_wasm PRIVATE
    "-sSTANDALONE_WASM=1"
    "-sALLOW_MEMORY_GROWTH=1"
    "-sINITIAL_MEMORY=67108864"
    # x265's encoder stack frames are large; the Emscripten default of 64 KiB is
    # not enough for a single frame.
    "-sSTACK_SIZE=8388608"
    "-sERROR_ON_UNDEFINED_SYMBOLS=1"
    "-sEXPORTED_FUNCTIONS=_xky_api_build,_xky_api_major,_xky_version_packed,_xky_version_string,_xky_params_create,_xky_params_free,_xky_params_parse,_xky_params_apply_preset,_xky_params_apply_tune,_xky_params_apply_profile,_xky_params_validate,_xky_params_get_int,_xky_encoder_parameters,_xky_encoder_open,_xky_encoder_close,_xky_encoder_headers,_xky_encode_planes,_xky_encode_packed,_xky_flush,_xky_output_nal_count,_xky_output_nal_type,_xky_output_nal_data,_xky_output_slice_type,_xky_output_pts,_xky_output_dts,_xky_output_blob,_xky_alloc,_xky_free"
    "--no-entry"
  )
endif()
