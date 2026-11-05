# x265-kmp

Kotlin Multiplatform bindings for [x265](https://bitbucket.org/multicoreware/x265_git):
HEVC/H.265 encoding on JVM, Android, Kotlin/Native and the web.

`x265` is included as a Git submodule and compiled from source for every
target. One C ABI (`jni/include/x265_kmp.h`) drives the JNI bridge, the
Kotlin/Native cinterop and the WebAssembly build, so every platform shares the
same parameter handling, input validation and result format.

Binding version: **4.2.0.1** (x265 4.2, binding revision 1).

## Supported targets

| Platform | Kotlin target | Native artifact |
|---|---|---|
| JVM | `jvm` | `x265-kmp-jni-jvm-{linux,darwin}-{x86_64,aarch64}`, `x265-kmp-jni-jvm-windows-x86_64` |
| Android | `android` | `x265-kmp` AAR with `libx265_jni.so` for every ABI |
| Android Native | `androidNativeArm64`, `androidNativeArm32`, `androidNativeX64`, `androidNativeX86` | static archive embedded in the cinterop klib; link the NDK's `libc++_shared.so` |
| Linux | `linuxX64`, `linuxArm64` | static archive embedded in the cinterop klib |
| Windows | `mingwX64` | static archive embedded in the cinterop klib |
| macOS | `macosX64`, `macosArm64` | static archive embedded in the cinterop klib |
| iOS | `iosX64`, `iosArm64`, `iosSimulatorArm64` | static archive embedded in the cinterop klib |
| tvOS | `tvosArm64`, `tvosSimulatorArm64` | static archive embedded in the cinterop klib |
| watchOS | `watchosArm64`, `watchosSimulatorArm64`, `watchosDeviceArm64` | static archive embedded in the cinterop klib |
| Kotlin/JS | `js` | embedded WebAssembly module |
| Kotlin/Wasm | `wasmJs` | embedded WebAssembly module |

The web targets compile x265 to a standalone `.wasm` module with Emscripten,
embed the binary into the published klib and instantiate it synchronously
through a small JS bridge. No resource lookup or async initialization is
required at runtime.

Android has two paths: the JNI AAR (`:examples:android-jvm`) and the
`androidNative*` Kotlin/Native targets (`:examples:android-native`). The native
targets are compiled with the SDK NDK, so a consumer's `libmain.so` must link
the NDK's `libc++_shared.so` and package it next to itself; the example does
both. Kotlin/Native's own bundled libc++ is too old for the archive's C++
runtime symbols.

This build is 8-bit (Main profile) only. The web module runs x265 in its
single-threaded mode: Emscripten has no pthreads, so `jni/patches` turns x265's
frame-encoder thread into an inline call, and the encoder is forced to
`pools=none`, one frame thread and no WPP. All JVM and Kotlin/Native targets use
x265's normal thread pool.

## Using the library

```kotlin
dependencies {
    implementation("cn.enaium.x265:x265-kmp:4.2.0.1")
}
```

The Android AAR and the JVM artifacts contain the native libraries. JVM
consumers automatically receive the per-OS/arch JNI artifacts and
`NativeLoader` extracts the matching one from the classpath.

### Encoding

```kotlin
import cn.enaium.x265.*

val param = X265Param.create(width = 1280, height = 720).apply {
    preset(X265Preset.ULTRAFAST)     // real-time camera setting
    tune(X265Tune.ZERO_LATENCY)
    crf(28.0)                        // 0 = lossless, 51 = worst
    keyframeMax = 30
    logLevel = X265LogLevel.NONE
}

X265Encoder.open(param).use { encoder ->
    // VPS/SPS/PPS, required as MediaCodec's csd-0 or by a muxer.
    val parameterSets = encoder.headers().toAnnexB()

    val picture = X265Picture.i420(y, u, v, width, height, pts = presentationTimeUs)
    encoder.encode(picture)?.let { frame ->
        // frame.nals exposes every NAL unit; frame.toAnnexB() adds start codes.
        sink.write(frame.toAnnexB())
    }

    // B-frames and the lookahead delay pictures; drain them before closing.
    encoder.flush().forEach { sink.write(it.toAnnexB()) }
}
```

Every x265 option is reachable by name through `set`:

```kotlin
param.set("preset", "slow")
param.set("crf", 24)
param.set("bframes", 6)
param.set("aq-mode", 3)
param.set("repeat-headers", true)
```

`X265Param` is a thin wrapper over `x265_param_parse`, so the option names and
value syntax are exactly the ones documented by `x265 --help`.

### Frames, NAL units and timestamps

`X265Frame` carries the NAL units of one encoded picture plus the presentation
and decoding timestamps x265 echoed back. `X265Nal.data` starts with the two-byte
NAL header; x265's own start codes or length prefixes are stripped by the
binding, so `toAnnexB()` emits exactly one start code per NAL. That matters for
permissive parsers like ffmpeg (which tolerate doubled start codes) and strict
consumers like MediaCodec (which do not).

`X265Encoder.encode` returns `null` while x265 buffers input for frame
reordering. `flush` returns every remaining picture and must be called once
after the last input frame.

## Examples

`examples/android-jvm` is a Compose loopback: CameraX → x265 → MediaCodec → `TextureView`.

1. CameraX `ImageAnalysis` provides `YUV_420_888` frames.
2. [`cn.enaium.libyuv:libyuv-kmp:1.2.0`](https://github.com/Enaium/libyuv-kmp)
   turns the camera's (often semi-planar, often padded) planes into direct I420
   planes through the `ImageProxy.toI420()` extension.
3. `X265Encoder` encodes with the `ultrafast` + `zerolatency` preset.
4. A `video/hevc` `MediaCodec` decoder renders the Annex-B access units straight
   to the preview `Surface`.

```bash
./gradlew :examples:android-jvm:assembleDebug
```

`examples/android-native` is the same loop written for Kotlin/Native with SDL3
and Dear ImGui ([sdl-kmp](https://github.com/Enaium/sdl-kmp) `3.4.18.1`,
[imgui-kmp](https://github.com/Enaium/imgui-kmp) `1.93.0.1`):

1. Camera2 NDK (`AImageReader`) captures `YUV_420_888`, converted with libyuv.
2. `X265Encoder` runs in `libmain.so`, linked statically into the SDL activity.
3. The NDK `AMediaCodec` C API decodes `video/hevc` to YUV buffers.
4. libyuv converts the decoded frame to RGBA and ImGui draws it.

The module also builds a macOS executable that runs the same UI and the x265
half of the loop (MediaCodec is Android-only), which is how the example is
verified without a device:

```bash
./gradlew :examples:android-native:linkDebugExecutableMacosArm64
X265_KMP_FRAMES=40 ./examples/android-native/build/bin/macosArm64/debugExecutable/android-native.kexe

# APK; narrow the ABIs on a host that cannot link all four
./gradlew -PandroidAbis=arm64-v8a :examples:android-native:android:assembleDebug
```

## Building from source

```bash
git submodule update --init --recursive
./gradlew :x265-kmp:jvmTest                 # JNI + tests
./gradlew :examples:android:assembleDebug   # camera example
./gradlew :x265-kmp:buildWebWasm            # Emscripten module
```

Native targets are built through CMake. `NASM` is used for x86 assembly when it
is on `PATH` (x265 falls back to portable C++ otherwise); AArch64 assembly is
compiled with the C++ compiler.

The web build does not compile the submodule directly: `prepareX265WasmSource`
runs `git clone --shared` into the build directory and applies
`jni/patches/x265-single-threaded-wasm.patch`, so the checkout stays pristine
and the stream still reports x265 4.2. The Android ARM64 build needs the NDK
target triple for x265's raw `.S` rules, passed through `ASM_FLAGS`.

## Licence

x265 is licensed under the GPL v2 (or a commercial licence from MulticoreWare).
Linking it into an application makes the resulting work subject to the GPL
unless a commercial x265 licence is obtained. The bindings themselves are
released under the GPL-2.0-or-later.
