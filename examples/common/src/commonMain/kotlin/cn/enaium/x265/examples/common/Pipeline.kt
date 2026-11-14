/*
 * Copyright (c) 2026 Enaium
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package cn.enaium.x265.examples.common

import cn.enaium.camera.CameraConfig
import cn.enaium.camera.CameraFormat
import cn.enaium.camera.CameraLensFacing
import cn.enaium.camera.CameraSession
import cn.enaium.camera.CameraSystem
import cn.enaium.camera.I420Frame
import cn.enaium.camera.PixelFormat
import cn.enaium.camera.cameraSystem
import cn.enaium.x265.X265ChromaFormat
import cn.enaium.x265.X265Encoder
import cn.enaium.x265.X265LogLevel
import cn.enaium.x265.X265Param
import cn.enaium.x265.X265Picture
import cn.enaium.x265.X265Preset
import cn.enaium.x265.X265Tune
import kotlin.concurrent.atomics.AtomicReference

/** What the UI shows above the picture. */
internal data class ExampleStatus(
    val source: String,
    val encodedFrames: Long,
    val encodedBytes: Long,
    val lastFrameSize: Int,
    val error: String?,
)

/**
 * Camera (camera-kmp) -> I420 -> x265.
 *
 * The camera is opened through the multiplatform camera-kmp backend, so the
 * same source runs on the JVM, Android (JVM and native), the desktop natives
 * and the browser. When no camera is available (CI, denied permission, no
 * device) a synthetic pattern takes over so the UI and the encoder stay
 * testable.
 */
internal class ExamplePipeline {
    private val camera = CameraSource()
    private val encoder = X265Pipeline()
    private val pattern = TestPattern(WIDTH, HEIGHT)
    private var pts = 0L

    var preview: I420Frame? = null
        private set

    val status: ExampleStatus
        get() = ExampleStatus(
            source = camera.source,
            encodedFrames = encoder.frames,
            encodedBytes = encoder.bytes,
            lastFrameSize = encoder.lastFrameSize,
            error = camera.error ?: encoder.error,
        )

    fun pump() {
        val frame = camera.poll() ?: pattern.next()
        preview = frame
        encoder.encode(frame, pts++)
    }

    fun restartCamera() {
        camera.restart()
    }

    fun close() {
        camera.close()
        encoder.close()
    }

    private companion object {
        const val WIDTH = 640
        const val HEIGHT = 480
    }
}

/** Owns the camera session and hands the newest I420 frame to the UI thread. */
private class CameraSource {
    private val latest = AtomicReference<I420Frame?>(null)
    private var system: CameraSystem? = null
    private var session: CameraSession? = null

    var source: String = "starting camera..."
        private set

    var error: String? = null
        private set

    init {
        restart()
    }

    fun restart() {
        close()
        error = null
        latest.store(null)
        try {
            val system = cameraSystem()
            this.system = system
            val camera = system.defaultCamera(CameraLensFacing.FRONT)
                ?: system.cameras().firstOrNull()
            if (camera == null) {
                source = "no camera - synthetic test pattern"
                return
            }
            val session = system.open(
                camera,
                CameraConfig(
                    format = CameraFormat(640, 480, PixelFormat.I420, 30),
                    lensFacing = camera.lensFacing,
                    mirrorHorizontally = camera.lensFacing == CameraLensFacing.FRONT,
                ),
            )
            this.session = session
            session.start(
                { frame -> latest.store(frame.toI420()) },
                { failure ->
                    error = failure.message ?: failure.toString()
                    source = "camera error - synthetic test pattern"
                },
            )
            source = "${camera.name} ${session.format.width}x${session.format.height} " +
                "@${session.format.frameRate}fps (${system.name})"
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
            source = "camera unavailable - synthetic test pattern"
        }
    }

    fun poll(): I420Frame? {
        val frame = latest.load()
        if (frame != null) latest.store(null)
        return frame
    }

    fun close() {
        runCatching { session?.close() }
        session = null
        runCatching { system?.close() }
        system = null
    }
}

/** Opens the encoder lazily for the first camera format and counts its output. */
private class X265Pipeline {
    private var encoder: X265Encoder? = null

    var frames = 0L
        private set

    var bytes = 0L
        private set

    var lastFrameSize = 0
        private set

    var error: String? = null
        private set

    fun encode(frame: I420Frame, pts: Long) {
        if (error != null) return
        try {
            val active = encoder ?: open(frame.width, frame.height)
            val encoded = active.encode(
                X265Picture.i420(
                    frame.y, frame.u, frame.v,
                    frame.width, frame.height,
                    if (frame.timestampNanos > 0) frame.timestampNanos / 1_000 else pts * 33_333,
                ),
            ) ?: return
            frames++
            bytes += encoded.byteSize
            lastFrameSize = encoded.byteSize
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
        }
    }

    private fun open(width: Int, height: Int): X265Encoder {
        val param = X265Param.create(width, height, X265ChromaFormat.I420, X265Preset.ULTRAFAST)
        try {
            param.tune(X265Tune.ZERO_LATENCY)
            param.set("crf", 28)
            param.set("keyint", 30)
            param.set("repeat-headers", true)
            param.logLevel = X265LogLevel.NONE
            return X265Encoder.open(param).also { encoder = it }
        } finally {
            param.close()
        }
    }

    fun close() {
        runCatching { encoder?.close() }
        encoder = null
    }
}

/** Deterministic I420 colour bars with a moving box, used when no camera exists. */
private class TestPattern(private val width: Int, private val height: Int) {
    private var frame = 0L

    fun next(): I420Frame {
        val y = ByteArray(width * height)
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val u = ByteArray(chromaWidth * chromaHeight)
        val v = ByteArray(chromaWidth * chromaHeight)
        val bars = intArrayOf(16, 60, 100, 140, 180, 220, 250, 16)
        for (row in 0 until height) {
            for (column in 0 until width) {
                val bar = bars[(column * bars.size / width).coerceIn(0, bars.size - 1)]
                y[row * width + column] = bar.toByte()
            }
        }
        val boxWidth = width / 6
        val boxHeight = height / 6
        val boxX = ((frame * width / 90) % (width - boxWidth)).toInt()
        val boxY = height / 3
        for (row in boxY until (boxY + boxHeight).coerceAtMost(height)) {
            for (column in boxX until (boxX + boxWidth).coerceAtMost(width)) {
                y[row * width + column] = 235.toByte()
            }
        }
        for (index in u.indices) {
            u[index] = 128.toByte()
            v[index] = 128.toByte()
        }
        frame++
        return I420Frame(width, height, y, u, v, frame * 33_333_333L)
    }
}
