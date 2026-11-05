@file:Suppress("DEPRECATION")
@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import java.io.File
import java.util.Base64
import java.util.Properties
import org.gradle.api.tasks.testing.Test
import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.maven.publish)
}

group = rootProject.group
version = rootProject.version

val jniDir = rootProject.projectDir.resolve("jni")
val x265Dir = rootProject.projectDir.resolve("x265")
val hostOs = OperatingSystem.current()

// ==================== Android NDK resolution ====================
val androidApiLevel = 21
val pinnedAndroidNdkVersion = "27.0.12077973"

fun resolveAndroidSdkDir(): File? {
    listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").forEach { key ->
        System.getenv(key)?.takeIf { it.isNotBlank() }?.let {
            val f = file(it)
            if (f.isDirectory) return f
        }
    }
    val localProps = rootProject.file("local.properties")
    if (localProps.isFile) {
        val props = Properties().apply { localProps.inputStream().use { load(it) } }
        props.getProperty("sdk.dir")?.takeIf { it.isNotBlank() }?.let {
            val f = file(it)
            if (f.isDirectory) return f
        }
    }
    return null
}

fun resolveAndroidNdkDir(): File? {
    listOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "NDK_HOME").forEach { key ->
        System.getenv(key)?.takeIf { it.isNotBlank() }?.let {
            val f = file(it)
            if (f.isDirectory) return f
        }
    }
    val sdk = resolveAndroidSdkDir() ?: return null
    val ndkParent = sdk.resolve("ndk")
    if (!ndkParent.isDirectory) return null
    val pinned = ndkParent.resolve(pinnedAndroidNdkVersion)
    if (pinned.isDirectory) return pinned
    return ndkParent.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
}

val resolvedAndroidNdk = resolveAndroidNdkDir()
val androidNdkToolchain = resolvedAndroidNdk?.resolve("build/cmake/android.toolchain.cmake")

fun hasExecutable(name: String): Boolean =
    System.getenv("PATH")?.split(File.pathSeparator)
        ?.map { File(it, name) }
        ?.any { it.isFile && it.canExecute() } == true

fun canBuildNativeTarget(targetName: String): Boolean {
    return when {
        hostOs.isMacOsX && targetName.startsWith("macos") -> true
        hostOs.isMacOsX && targetName.startsWith("ios") -> true
        hostOs.isMacOsX && targetName.startsWith("tvos") -> true
        hostOs.isMacOsX && targetName.startsWith("watchos") -> true
        hostOs.isLinux && targetName == "linuxX64" -> true
        hostOs.isLinux && targetName == "linuxArm64" ->
            System.getProperty("os.arch").lowercase() in listOf("aarch64", "arm64") ||
                hasExecutable("aarch64-linux-gnu-gcc")
        hostOs.isLinux && targetName == "mingwX64" -> hasExecutable("x86_64-w64-mingw32-gcc")
        hostOs.isWindows && targetName == "mingwX64" -> true
        targetName.startsWith("androidNative") && androidNdkToolchain?.isFile == true -> true
        else -> false
    }
}

val cmakeExecutable: String = rootProject.extra["cmakeExecutable"] as String

// ==================== Kotlin Multiplatform ====================
kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    android {
        namespace = "cn.enaium.x265"
        compileSdk = 37
        minSdk = 21

        withHostTest {}

        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_21)
                }
            }
        }
    }

    compilerOptions {
        // X265Native uses expect/actual classes for the opaque native handles.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    // Desktop and server Kotlin/Native targets.
    macosArm64()
    macosX64()
    linuxX64()
    linuxArm64()
    mingwX64()

    // Kotlin/Native Android targets (apps that link libmain.so).
    androidNativeArm64()
    androidNativeArm32()
    androidNativeX64()
    androidNativeX86()

    // Apple mobile/wearable targets.
    iosArm64()
    iosX64()
    iosSimulatorArm64()
    tvosArm64()
    tvosSimulatorArm64()
    watchosArm64()
    watchosSimulatorArm64()
    watchosDeviceArm64()

    // Web targets. The x265 sources are compiled to a standalone WebAssembly
    // module by the buildWebWasm task; the Kotlin side talks to it through a
    // small synchronous JS bridge.
    js {
        nodejs()
    }
    wasmJs {
        nodejs()
    }

    // Keep the cinterop names identical across every native target so the
    // shared nativeMain implementation can call them.
    targets.withType<KotlinNativeTarget>().configureEach {
        val targetName = name
        val canBuild = canBuildNativeTarget(targetName)
        compilations.getByName("main") {
            cinterops.create("x265_kmp") {
                defFile(project.file("src/nativeInterop/cinterop/x265_kmp.def"))
                includeDirs(
                    project.file("src/nativeInterop/cinterop"),
                    jniDir.resolve("include"),
                )
                if (canBuild) {
                    val outputDir = layout.buildDirectory.dir("native/$targetName").get().asFile
                    extraOpts(
                        "-libraryPath", outputDir.absolutePath,
                        "-staticLibrary", "libx265_kmp.a",
                    )
                }
            }
        }
    }

    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(kotlin("stdlib-common"))
            }
        }

        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        getByName("jvmMain") {
            dependencies {
                // Bundle every JNI artifact so consumers get the right native
                // binary out of the box; NativeLoader picks one at runtime.
                runtimeOnly(project(":jni-jvm-linux-x86_64"))
                runtimeOnly(project(":jni-jvm-linux-aarch64"))
                runtimeOnly(project(":jni-jvm-darwin-x86_64"))
                runtimeOnly(project(":jni-jvm-darwin-aarch64"))
                runtimeOnly(project(":jni-jvm-windows-x86_64"))
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation(libs.junit.jupiter)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }

        getByName("androidMain") {
            // Share the JNI declarations and loader with the JVM target. The
            // AAR supplies libx265_jni.so through jniLibs, so on Android the
            // loader falls back to System.loadLibrary.
            kotlin.srcDir("src/jvmMain/kotlin")
        }

        // Shared intermediate source sets. The default hierarchy template is
        // disabled so every edge below is explicit and deterministic.
        val commonMain = getByName("commonMain")
        val commonTest = getByName("commonTest")
        val nativeMain = maybeCreate("nativeMain").apply { dependsOn(commonMain) }
        val nativeTest = maybeCreate("nativeTest").apply { dependsOn(commonTest) }
        val webMain = maybeCreate("webMain").apply { dependsOn(commonMain) }
        val webTest = maybeCreate("webTest").apply { dependsOn(commonTest) }

        getByName("jsMain").dependsOn(webMain)
        getByName("wasmJsMain").dependsOn(webMain)
        getByName("jsTest").dependsOn(webTest)
        getByName("wasmJsTest").dependsOn(webTest)
    }
}

// Kotlin/Native's Android test executables link its bundled, much older libc++
// while the archive is built with the SDK NDK. Point them at the NDK's
// libc++_shared instead, exactly like an application would.
androidNdkToolchain?.takeIf { it.isFile }?.let {
    kotlin.targets.withType<KotlinNativeTarget>().configureEach {
        if (!name.startsWith("androidNative")) return@configureEach
        val abi = when (name) {
            "androidNativeArm64" -> "arm64-v8a"
            "androidNativeArm32" -> "armeabi-v7a"
            "androidNativeX64" -> "x86_64"
            else -> "x86"
        }
        val triple = when (abi) {
            "arm64-v8a" -> "aarch64-linux-android"
            "armeabi-v7a" -> "arm-linux-androideabi"
            "x86_64" -> "x86_64-linux-android"
            else -> "i686-linux-android"
        }
        val sysroot = rootProject.extra["androidNdkSysroot"] as String? ?: return@configureEach
        binaries.configureEach {
            linkerOpts("$sysroot/usr/lib/$triple/libc++_shared.so")
        }
    }
}

// Every Kotlin/Native target shares nativeMain/nativeTest. Wiring the
// dependency (rather than copying the source directory into each target) keeps
// expect/actual resolution and cinterop commonization working.
kotlin.targets.withType<KotlinNativeTarget>().configureEach {
    compilations.getByName("main").defaultSourceSet.dependsOn(
        kotlin.sourceSets.getByName("nativeMain"),
    )
    compilations.getByName("test").defaultSourceSet.dependsOn(
        kotlin.sourceSets.getByName("nativeTest"),
    )
}

// ==================== WebAssembly ====================
// x265 and the C shim are compiled to one standalone .wasm module. Its bytes
// are embedded into a generated Kotlin source file, so the published js/wasmJs
// artifact is self-contained: no resource lookup, fetch or async init.
val wasmConfigureDir = layout.buildDirectory.dir("wasm-cmake")
val wasmOutput = wasmConfigureDir.map { it.file("x265_wasm.wasm") }
val generatedWebDir = layout.buildDirectory.dir("generated/x265Web/kotlin")

fun resolveEmsdk(): File? {
    System.getenv("EMSDK")?.takeIf { it.isNotBlank() }?.let {
        val dir = File(it)
        if (dir.isDirectory) return dir
    }
    val home = System.getProperty("user.home")
    return listOf("$home/emsdk", "$home/.emsdk").map(::File).firstOrNull { it.isDirectory }
}

fun resolveEmscripten(command: String): String {
    System.getenv("PATH")?.split(File.pathSeparator).orEmpty().forEach { dir ->
        val file = File(dir, command)
        if (file.isFile && file.canExecute()) return file.absolutePath
    }
    val sdk = resolveEmsdk()
    val candidate = sdk?.resolve("upstream/emscripten/$command")
    if (candidate != null && candidate.isFile) return candidate.absolutePath
    return command
}

fun Exec.configureEmscripten() {
    val sdk = resolveEmsdk()
    sdk?.let {
        environment("EMSDK", it.absolutePath)
        it.resolve(".emscripten").takeIf { f -> f.isFile }?.let { f ->
            environment("EM_CONFIG", f.absolutePath)
        }
        val pythonBin = it.resolve("python").listFiles()
            ?.filter { d -> d.isDirectory }
            ?.mapNotNull { d -> d.resolve("bin") }
            ?.firstOrNull { d -> d.isDirectory }
        val pythonExe = pythonBin?.resolve("python3")?.takeIf { d -> d.isFile }
        pythonExe?.let { exe -> environment("EMSDK_PYTHON", exe.absolutePath) }
        val nodeBin = it.resolve("node").listFiles()
            ?.filter { d -> d.isDirectory }
            ?.flatMap { d -> d.listFiles()?.toList().orEmpty() }
            ?.firstOrNull { d -> d.isDirectory && d.name == "bin" }
        val path = buildString {
            pythonBin?.let { bin -> append(bin.absolutePath).append(File.pathSeparator) }
            append(it.resolve("upstream/emscripten").absolutePath)
            nodeBin?.let { bin -> append(File.pathSeparator).append(bin.absolutePath) }
            System.getenv("PATH")?.let { env -> append(File.pathSeparator).append(env) }
        }
        environment("PATH", path)
    }
}

// x265 drives its frame encoders through pthreads, which Emscripten targets do
// not have. The web build therefore compiles a patched copy of the submodule:
// a `git clone --shared` (fast, tags included so `git describe` still reports
// 4.2) with jni/patches applied. The submodule itself stays pristine.
val wasmSourceDir = layout.buildDirectory.dir("x265-wasm-src")
val singleThreadPatch = rootProject.file("jni/patches/x265-single-threaded-wasm.patch")

fun runCommand(vararg command: String) {
    val process = ProcessBuilder(*command).inheritIO().start()
    val exit = process.waitFor()
    if (exit != 0) throw GradleException("Command failed ($exit): ${command.joinToString(" ")}")
}

fun gitHead(directory: java.io.File): String {
    val process = ProcessBuilder("git", "-C", directory.absolutePath, "rev-parse", "HEAD")
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    process.waitFor()
    return output
}

val prepareX265WasmSource = tasks.register("prepareX265WasmSource") {
    group = "build"
    description = "Clones x265 at the pinned commit and applies the single-threaded wasm patch."
    inputs.file(singleThreadPatch)
    inputs.property("x265Commit", gitHead(x265Dir))
    outputs.dir(wasmSourceDir)
    doLast {
        val target = wasmSourceDir.get().asFile
        target.deleteRecursively()
        target.parentFile.mkdirs()
        runCommand("git", "clone", "--quiet", "--shared", x265Dir.absolutePath, target.absolutePath)
        runCommand("git", "-C", target.absolutePath, "apply", "--whitespace=nowarn", singleThreadPatch.absolutePath)
    }
}

val configureWebWasm = tasks.register<Exec>("configureWebWasm") {
    group = "build"
    description = "Configures the Emscripten build of x265."
    val buildDir = wasmConfigureDir.get().asFile
    workingDir = buildDir
    configureEmscripten()
    dependsOn(prepareX265WasmSource)
    val wasmX265Dir = wasmSourceDir.get().asFile.absolutePath
    commandLine(
        resolveEmscripten("emcmake"), "cmake",
        jniDir.absolutePath,
        "-G", "Ninja",
        "-DCMAKE_BUILD_TYPE=Release",
        "-DBUILD_JNI=OFF",
        "-DBUILD_WASM=ON",
        "-DX265_DIR=$wasmX265Dir",
    )
    doFirst {
        buildDir.mkdirs()
    }
    inputs.files(
        rootProject.file("jni/CMakeLists.txt"),
        rootProject.file("jni/x265_kmp.cc"),
        rootProject.file("jni/include/x265_kmp.h"),
        rootProject.file("jni/x265_jni.cpp"),
        rootProject.file("jni/x265_wasm_entry.c"),
    )
    inputs.dir(rootProject.file("x265/source"))
    outputs.file(wasmConfigureDir.map { it.file("build.ninja") })
}

val buildWebWasm = tasks.register<Exec>("buildWebWasm") {
    group = "build"
    description = "Compiles x265 and the KMP shim to a standalone WebAssembly module."
    dependsOn(configureWebWasm)
    val buildDir = wasmConfigureDir.get().asFile
    workingDir = buildDir
    configureEmscripten()
    commandLine(cmakeExecutable, "--build", ".", "--target", "x265_wasm", "--config", "Release")
    inputs.files(
        rootProject.file("jni/CMakeLists.txt"),
        rootProject.file("jni/x265_kmp.cc"),
        rootProject.file("jni/include/x265_kmp.h"),
        rootProject.file("jni/x265_jni.cpp"),
        rootProject.file("jni/x265_wasm_entry.c"),
    )
    inputs.dir(rootProject.file("x265/source"))
    outputs.file(wasmOutput)
}

val generateWebWasmData = tasks.register<DefaultTask>("generateWebWasmData") {
    group = "build"
    description = "Embeds the compiled WebAssembly module into a Kotlin source file."
    dependsOn(buildWebWasm)
    val wasmBytes = wasmOutput
    val outputDir = generatedWebDir
    inputs.file(wasmBytes)
    outputs.dir(outputDir)
    doLast {
        val bytes = wasmBytes.get().asFile.readBytes()
        val base64 = Base64.getEncoder().encodeToString(bytes)
        val chunkSize = 4096
        val chunks = base64.chunked(chunkSize)
        val file = outputDir.get().asFile.resolve("cn/enaium/x265/X265WasmData.kt")
        file.parentFile.mkdirs()
        file.writeText(
            buildString {
                appendLine("// Generated by :x265-kmp:generateWebWasmData. Do not edit.")
                appendLine("package cn.enaium.x265")
                appendLine()
                appendLine("internal const val X265_WASM_BASE64_SIZE = ${base64.length}")
                appendLine("internal val X265_WASM_BASE64: String by lazy {")
                appendLine("    buildString(X265_WASM_BASE64_SIZE) {")
                chunks.forEachIndexed { index, _ ->
                    appendLine("        append(WASM_CHUNK_$index)")
                }
                appendLine("    }")
                appendLine("}")
                chunks.forEachIndexed { index, chunk ->
                    appendLine("private const val WASM_CHUNK_$index = \"$chunk\"")
                }
            },
        )
    }
}

kotlin.sourceSets.getByName("webMain").kotlin.srcDir(generatedWebDir)

tasks.matching {
    // Every task that reads webMain (compilation, source jars, publication
    // sources) must see the generated wasm data file.
    it.name.startsWith("compileKotlinJs") ||
        it.name.startsWith("compileKotlinWasmJs") ||
        it.name.startsWith("compileTestKotlinJs") ||
        it.name.startsWith("compileTestKotlinWasmJs") ||
        it.name == "jsSourcesJar" ||
        it.name == "wasmJsSourcesJar" ||
        it.name.contains("WebMain") ||
        it.name == "kotlinMultiplatformSourcesJar" ||
        it.name == "sourcesJar" ||
        it.name == "metadataSourcesJar"
}.configureEach {
    dependsOn(generateWebWasmData)
}

// ==================== Native static libraries ====================
fun registerNativeBuildTasks(targetName: String, cmakeFlags: List<String> = emptyList()) {
    val outputDir = layout.buildDirectory.dir("native/$targetName").get().asFile
    val cmakeBuildDir = layout.buildDirectory.dir("cmake-$targetName").get().asFile

    val configureTask = tasks.register<Exec>("configureNative_$targetName") {
        onlyIf { canBuildNativeTarget(targetName) }
        doFirst {
            cmakeBuildDir.mkdirs()
            outputDir.mkdirs()
        }
        workingDir = cmakeBuildDir
        commandLine(
            listOf(
                cmakeExecutable, jniDir.absolutePath,
                "-DCMAKE_BUILD_TYPE=Release",
                "-DBUILD_JNI=OFF",
                "-DCMAKE_ARCHIVE_OUTPUT_DIRECTORY=${outputDir.absolutePath}",
            ) + cmakeFlags,
        )
    }

    val staticLibrary = File(outputDir, "libx265_kmp.a")
    val buildTask = tasks.register<Exec>("buildNative_$targetName") {
        onlyIf { canBuildNativeTarget(targetName) }
        dependsOn(configureTask)
        workingDir = cmakeBuildDir
        commandLine(cmakeExecutable, "--build", ".", "--target", "x265-static", "--config", "Release")
        // No outputs: cmake's own dependency tracking decides what to recompile,
        // and Gradle must always let it run after a source change.
    }

    tasks.matching {
        it.name.startsWith("cinteropX265_kmp") &&
            it.name.endsWith(targetName.replaceFirstChar { c -> c.uppercase() })
    }.configureEach {
        dependsOn(buildTask)
        // cinterop copies the archive into the klib; without this input a
        // rebuilt library would keep linking the stale copy.
        inputs.file(staticLibrary)
    }
}

if (hostOs.isMacOsX) {
    registerNativeBuildTasks("macosArm64", listOf("-DCMAKE_OSX_ARCHITECTURES=arm64"))
    registerNativeBuildTasks("macosX64", listOf("-DCMAKE_OSX_ARCHITECTURES=x86_64"))
    registerNativeBuildTasks("iosArm64", listOf(
        "-DCMAKE_SYSTEM_NAME=iOS",
        "-DCMAKE_OSX_ARCHITECTURES=arm64",
        "-DCMAKE_OSX_SYSROOT=iphoneos",
        "-DCMAKE_OSX_DEPLOYMENT_TARGET=13.0",
    ))
    registerNativeBuildTasks("iosX64", listOf(
        "-DCMAKE_SYSTEM_NAME=iOS",
        "-DCMAKE_OSX_ARCHITECTURES=x86_64",
        "-DCMAKE_OSX_SYSROOT=iphonesimulator",
        "-DCMAKE_OSX_DEPLOYMENT_TARGET=13.0",
    ))
    registerNativeBuildTasks("iosSimulatorArm64", listOf(
        "-DCMAKE_SYSTEM_NAME=iOS",
        "-DCMAKE_OSX_ARCHITECTURES=arm64",
        "-DCMAKE_OSX_SYSROOT=iphonesimulator",
        "-DCMAKE_OSX_DEPLOYMENT_TARGET=13.0",
    ))
    registerNativeBuildTasks("tvosArm64", listOf(
        "-DCMAKE_SYSTEM_NAME=tvOS",
        "-DCMAKE_OSX_ARCHITECTURES=arm64",
        "-DCMAKE_OSX_SYSROOT=appletvos",
        "-DCMAKE_OSX_DEPLOYMENT_TARGET=13.0",
    ))
    registerNativeBuildTasks("tvosSimulatorArm64", listOf(
        "-DCMAKE_SYSTEM_NAME=tvOS",
        "-DCMAKE_OSX_ARCHITECTURES=arm64",
        "-DCMAKE_OSX_SYSROOT=appletvsimulator",
        "-DCMAKE_OSX_DEPLOYMENT_TARGET=13.0",
    ))
    registerNativeBuildTasks("watchosArm64", listOf(
        "-DCMAKE_SYSTEM_NAME=watchOS",
        "-DCMAKE_OSX_ARCHITECTURES=arm64",
        "-DCMAKE_OSX_SYSROOT=watchos",
        "-DCMAKE_OSX_DEPLOYMENT_TARGET=5.0",
    ))
    registerNativeBuildTasks("watchosSimulatorArm64", listOf(
        "-DCMAKE_SYSTEM_NAME=watchOS",
        "-DCMAKE_OSX_ARCHITECTURES=arm64",
        "-DCMAKE_OSX_SYSROOT=watchsimulator",
        "-DCMAKE_OSX_DEPLOYMENT_TARGET=5.0",
    ))
    registerNativeBuildTasks("watchosDeviceArm64", listOf(
        "-DCMAKE_SYSTEM_NAME=watchOS",
        "-DCMAKE_OSX_ARCHITECTURES=arm64",
        "-DCMAKE_OSX_SYSROOT=watchos",
        "-DCMAKE_OSX_DEPLOYMENT_TARGET=5.0",
    ))
} else if (hostOs.isLinux) {
    // Kotlin/Native links Linux programs against its bundled sysroot (glibc
    // 2.19 on x64, 2.25 on arm64). Two consequences for the archive compiled
    // with the host's much newer glibc and gcc:
    //  - gcc vectorises log()/atan2() into libmvec calls that sysroot does not
    //    provide, so tree vectorisation is turned off (the hand-written asm
    //    kernels are unaffected);
    //  - aarch64 emit calls to the outlined-atomics helpers, which that sysroot
    //    does not export either.
    val kotlinNativeLinuxFlags = listOf(
        "-DCMAKE_CXX_FLAGS_RELEASE=-O3 -DNDEBUG -fno-tree-vectorize",
        "-DXKY_LEGACY_GLIBC=ON",
    )
    registerNativeBuildTasks("linuxX64", kotlinNativeLinuxFlags)
    val arm64Flags = if (System.getProperty("os.arch").lowercase() in listOf("aarch64", "arm64")) {
        kotlinNativeLinuxFlags
    } else {
        kotlinNativeLinuxFlags + listOf(
            "-DCMAKE_SYSTEM_NAME=Linux",
            "-DCMAKE_SYSTEM_PROCESSOR=aarch64",
            "-DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc",
            "-DCMAKE_CXX_COMPILER=aarch64-linux-gnu-g++",
        )
    }
    registerNativeBuildTasks("linuxArm64", arm64Flags + listOf(
        "-DCMAKE_C_FLAGS=-mno-outline-atomics",
        "-DCMAKE_CXX_FLAGS=-mno-outline-atomics",
    ))
    registerNativeBuildTasks("mingwX64", listOf(
        "-DCMAKE_SYSTEM_NAME=Windows",
        "-DCMAKE_SYSTEM_PROCESSOR=x86_64",
        "-DCMAKE_C_COMPILER=x86_64-w64-mingw32-gcc",
        "-DCMAKE_CXX_COMPILER=x86_64-w64-mingw32-g++",
        "-DCMAKE_RC_COMPILER=x86_64-w64-mingw32-windres",
        // See jni/x265_mingw_compat.cpp: the archive is compiled with a newer
        // libstdc++ than the one Kotlin/Native links.
        "-DXKY_LEGACY_MINGW=ON",
    ))
} else if (hostOs.isWindows) {
    // CMake would pick the Visual Studio generator on a stock Windows runner,
    // producing an MSVC archive the MinGW-based Kotlin/Native link cannot use.
    registerNativeBuildTasks("mingwX64", listOf(
        "-G", "MinGW Makefiles",
        "-DCMAKE_C_COMPILER=x86_64-w64-mingw32-gcc",
        "-DCMAKE_CXX_COMPILER=x86_64-w64-mingw32-g++",
        "-DCMAKE_RC_COMPILER=x86_64-w64-mingw32-windres",
        "-DXKY_LEGACY_MINGW=ON",
    ))
}

// ==================== Android JNI shared libraries ====================
val androidJniAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

/**
 * The NDK's `clang++` defaults to the host target; x265 compiles its AArch64
 * `.S` files with a raw custom command that does not receive the toolchain's
 * `--target`/`--sysroot` flags, so the triple is passed through ASM_FLAGS.
 */
fun androidAssemblyFlags(abi: String): List<String> = when (abi) {
    "arm64-v8a" -> listOf("-DASM_FLAGS=--target=aarch64-none-linux-android$androidApiLevel")
    else -> emptyList()
}

androidNdkToolchain?.takeIf { it.isFile }?.let { toolchain ->
    listOf(
        "androidNativeArm64" to "arm64-v8a",
        "androidNativeArm32" to "armeabi-v7a",
        "androidNativeX64" to "x86_64",
        "androidNativeX86" to "x86",
    ).forEach { (targetName, abi) ->
        registerNativeBuildTasks(targetName, listOf(
            "-DCMAKE_TOOLCHAIN_FILE=${toolchain.absolutePath}",
            "-DANDROID_ABI=$abi",
            "-DANDROID_PLATFORM=android-$androidApiLevel",
            // Kotlin/Native's Android runtime predates the NDK's outlined
            // atomics helpers; emit inline LL/SC sequences instead.
            "-DCMAKE_C_FLAGS=-mno-outline-atomics",
            "-DCMAKE_CXX_FLAGS=-mno-outline-atomics",
        ) + androidAssemblyFlags(abi))
    }
}
val androidJniLibsDir = layout.buildDirectory.dir("jniLibs")
val buildAndroidJniLibs = tasks.register<DefaultTask>("buildAndroidJniLibs") {
    group = "build"
    description = "Builds libx265_jni.so for all Android ABIs."
}

androidJniAbis.forEach { abi ->
    val outputDir = layout.buildDirectory.dir("jniLibs/$abi")
    val cmakeBuildDir = layout.buildDirectory.dir("cmake-android-$abi").get().asFile

    val configureTask = tasks.register<Exec>("configureAndroidJni_$abi") {
        onlyIf { androidNdkToolchain?.isFile == true }
        doFirst {
            cmakeBuildDir.mkdirs()
            outputDir.get().asFile.mkdirs()
        }
        workingDir = cmakeBuildDir
        commandLine(
            listOf(
                cmakeExecutable, jniDir.absolutePath,
                "-DCMAKE_BUILD_TYPE=Release",
                "-DBUILD_JNI=ON",
                "-DCMAKE_TOOLCHAIN_FILE=${androidNdkToolchain?.absolutePath ?: ""}",
                "-DANDROID_ABI=$abi",
                "-DANDROID_PLATFORM=android-$androidApiLevel",
                "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${outputDir.get().asFile.absolutePath}",
            ) + androidAssemblyFlags(abi),
        )
    }

    val buildTask = tasks.register<Exec>("buildAndroidJni_$abi") {
        onlyIf { androidNdkToolchain?.isFile == true }
        dependsOn(configureTask)
        workingDir = cmakeBuildDir
        commandLine(cmakeExecutable, "--build", ".", "--target", "x265_jni", "--config", "Release")
    }

    buildAndroidJniLibs.configure { dependsOn(buildTask) }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(androidJniLibsDir.get().asFile.absolutePath)
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.contains("JniLibFolders") }
    .configureEach { dependsOn(buildAndroidJniLibs) }

// ==================== Android host tests ====================
// Android host-test targets (unit tests on the JVM) call System.loadLibrary
// directly, so they need a flat directory containing libx265_jni.{so,dylib}.
// Reuse the host's :jni-jvm-* subproject build output.
val hostJniProjectName = run {
    val arch = System.getProperty("os.arch").lowercase()
    val archClassifier = when (arch) {
        "amd64", "x86_64", "x64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> null
    }
    val osClassifier = when {
        hostOs.isLinux -> "linux"
        hostOs.isMacOsX -> "darwin"
        hostOs.isWindows -> "windows"
        else -> null
    }
    if (osClassifier != null && archClassifier != null) ":jni-jvm-$osClassifier-$archClassifier" else null
}

if (hostJniProjectName != null) {
    val hostJniProject = project(hostJniProjectName)
    val hostNativeDir = hostJniProject.layout.buildDirectory.dir(
        "jni-native/${hostJniProjectName.removePrefix(":jni-jvm-")}",
    )
    tasks.withType<Test>().configureEach {
        if (name.contains("AndroidHostTest", ignoreCase = true)) {
            dependsOn("$hostJniProjectName:buildJniLibrary")
            systemProperty("java.library.path", hostNativeDir.get().asFile.absolutePath)
        }
    }
}

// ==================== Publishing ====================
val hasSigningKey = rootProject.extra["hasSigningKey"] as Boolean

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    if (hasSigningKey) signAllPublications()

    coordinates(
        groupId = group.toString(),
        artifactId = "x265-kmp",
        version = null,
    )

    pom {
        name.set("x265-kmp")
        description.set(
            "Kotlin Multiplatform bindings for x265: HEVC/H.265 encoding on JVM, Android, Kotlin/Native and the web.",
        )
        url.set("https://github.com/Enaium/x265-kmp")
        inceptionYear.set("2026")

        licenses {
            license {
                name.set("GPL-2.0-or-later")
                url.set("https://www.gnu.org/licenses/old-licenses/gpl-2.0.html")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("Enaium")
            }
        }

        scm {
            url.set("https://github.com/Enaium/x265-kmp")
            connection.set("scm:git:git@github.com:Enaium/x265-kmp.git")
            developerConnection.set("scm:git:git@github.com:Enaium/x265-kmp.git")
        }

        issueManagement {
            system.set("GitHub")
            url.set("https://github.com/Enaium/x265-kmp/issues")
        }
    }
}
