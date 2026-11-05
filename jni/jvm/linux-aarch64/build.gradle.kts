/*
 * Per-OS/arch JNI artifact: linux-aarch64.
 * Ships libx265_jni.so as a classpath resource at
 * /cn/enaium/x265/native/linux-aarch64/, which NativeLoader
 * (in :x265-kmp's jvmMain) extracts and System.load()s at runtime.
 */
import org.gradle.internal.os.OperatingSystem

plugins {
    `java-library`
    alias(libs.plugins.maven.publish)
}

group = rootProject.group
version = rootProject.version

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

val jniOs = "linux"
val jniArch = "aarch64"
val classifier = "$jniOs-$jniArch"
val libFile = "libx265_jni.so"
val resourceDir = "cn/enaium/x265/native/$classifier"

val canBuildHere = OperatingSystem.current().isLinux

val cmakeExecutable: String = rootProject.extra["cmakeExecutable"] as String

val nativeOutputDir = layout.buildDirectory.dir("jni-native/$classifier")
val cmakeBuildDir = layout.buildDirectory.dir("cmake-jni/$classifier")

val configureJniLibrary = tasks.register<Exec>("configureJniLibrary") {
    group = "build"
    description = "cmake-configures x265_jni for $classifier."
    onlyIf { canBuildHere }
    val outDir = nativeOutputDir.get().asFile
    val buildDir = cmakeBuildDir.get().asFile
    doFirst {
        outDir.mkdirs()
        buildDir.mkdirs()
    }
    workingDir = buildDir
    val javaHome = System.getProperty("java.home") ?: System.getenv("JAVA_HOME") ?: ""
    val jniInclude = if (javaHome.isNotEmpty()) "$javaHome/include" else ""
    commandLine(
        cmakeExecutable,
        rootProject.file("jni").absolutePath,
        "-DCMAKE_BUILD_TYPE=Release",
        "-DBUILD_JNI=ON",
        "-DJNI_INCLUDE_DIR=$jniInclude",
        "-DJNI_INCLUDE_DIR_PLATFORM=$jniInclude/linux",
        "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${outDir.absolutePath}",
        // DLLs are RUNTIME artifacts in CMake; without this the Windows build
        // writes x265_jni.dll into the CMake build tree.
        "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY=${outDir.absolutePath}",

    )
}

val buildJniLibrary = tasks.register<Exec>("buildJniLibrary") {
    group = "build"
    description = "Builds $libFile for $classifier."
    onlyIf { canBuildHere }
    dependsOn(configureJniLibrary)
    workingDir = cmakeBuildDir.get().asFile
    commandLine(cmakeExecutable, "--build", ".", "--target", "x265_jni", "--config", "Release")
    inputs.files(
        rootProject.file("jni/CMakeLists.txt"),
        rootProject.file("jni/x265_kmp.cc"),
        rootProject.file("jni/include/x265_kmp.h"),
        rootProject.file("jni/x265_jni.cpp"),
    )
    inputs.dir(rootProject.file("x265/source"))
    outputs.file(nativeOutputDir.map { it.file(libFile) })
}

tasks.named<Copy>("processResources") {
    dependsOn(buildJniLibrary)
    // Copy the archive through its provider instead of the task's output
    // collection: the latter is filtered by an include pattern and was empty
    // on the Windows runner, which silently produced a jar without the DLL.
    from(nativeOutputDir.map { it.file(libFile) }) {
        into(resourceDir)
    }
}

val hasSigningKey = rootProject.extra["hasSigningKey"] as Boolean

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    if (hasSigningKey) signAllPublications()
    coordinates(
        groupId = rootProject.group.toString(),
        artifactId = "x265-kmp-jni-jvm-$classifier",
        version = rootProject.version.toString(),
    )
    pom {
        name.set("x265-kmp-jni-jvm-$classifier")
        description.set(
            "Prebuilt JNI shared library for x265-kmp on $jniOs/$jniArch. " +
                "Loaded automatically by NativeLoader; not intended to be depended on directly.",
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
