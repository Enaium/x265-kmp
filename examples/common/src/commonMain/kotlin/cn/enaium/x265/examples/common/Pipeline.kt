/*
 * Copyright (c) 2026 Enaium
 * SPDX-License-Identifier: GPL-2.0-or-later
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
import cn.enaium.libde265.De265Chroma
import cn.enaium.libde265.Libde265Decoder
import cn.enaium.x265.X265ChromaFormat
import cn.enaium.x265.X265Encoder
import cn.enaium.x265.X265LogLevel
import cn.enaium.x265.X265Param
import cn.enaium.x265.X265Picture
import cn.enaium.x265.X265Preset
import cn.enaium.x265.X265Tune
import kotlin.concurrent.atomics.AtomicReference

/** What the UI shows above the pictures. */
internal data class ExampleStatus(
    val source: String,
    val encodedFrames: Long,
    val encodedBytes: Long,
    val decodedFrames: Long,
    val lastFrameSize: Int,
    val error: String?,
)

/**
 * Camera (camera-kmp) -> x265 encode -> libde265 decode.
 *
 * The captured I420 picture is encoded with x265 and the Annex-B access units
 * are fed straight into libde265, so the decoded picture is the camera picture
 * after a full round trip. A deterministic test pattern takes over only until
 * the camera delivers its first frame (or when no camera exists), so the
 * preview never alternates between the two sources.
 */
internal class ExamplePipeline {
    private val camera = CameraSource()
    private var loop = EncodeDecodeLoop()
    private var pattern: TestPattern? = null

    var settings: CameraSettings = CameraSettings()
        private set

    private var lastCamera: I420Frame? = null

    var preview: I420Frame? = null
        private set

    var decoded: I420Frame? = null
        private set

    val status: ExampleStatus
        get() = ExampleStatus(
            source = camera.source,
            encodedFrames = loop.encodedFrames,
            encodedBytes = loop.encodedBytes,
            decodedFrames = loop.decodedFrames,
            lastFrameSize = loop.lastFrameSize,
            error = camera.error ?: loop.error,
        )

    fun pump() {
        val current = when (settings.source) {
            CaptureSource.TEST_PATTERN -> pattern().next().transformed()
            CaptureSource.CAMERA -> {
                val fresh = camera.poll()
                if (fresh != null) lastCamera = fresh.transformed()
                lastCamera ?: pattern().next().transformed()
            }
        }
        preview = current
        loop.step(current)?.let { decoded = it }
    }

    private fun I420Frame.transformed(): I420Frame {
        if (settings.rotationDegrees == 0 && !settings.mirrorHorizontally) return this
        return toCameraFrame(
            pixelFormat = PixelFormat.I420,
            rotationDegrees = settings.rotationDegrees,
            mirrorHorizontally = settings.mirrorHorizontally,
        ).toI420()
    }

    fun applySettings(updated: CameraSettings) {
        if (updated == settings) return
        settings = updated
        if (updated.source == CaptureSource.CAMERA) {
            camera.restart(updated)
        } else {
            camera.useTestPattern()
        }
        resetLoop()
        pattern = null
        lastCamera = null
        preview = null
        decoded = null
    }

    fun restartCamera() {
        if (settings.source == CaptureSource.CAMERA) camera.restart(settings) else camera.useTestPattern()
        resetLoop()
        lastCamera = null
        preview = null
        decoded = null
    }

    private fun resetLoop() {
        runCatching { loop.close() }
        loop = EncodeDecodeLoop()
    }

    private fun pattern(): TestPattern =
        pattern ?: TestPattern(settings.width, settings.height).also { pattern = it }

    fun close() {
        camera.close()
        loop.close()
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
        restart(CameraSettings())
    }

    fun restart(settings: CameraSettings) {
        close()
        error = null
        latest.store(null)
        if (settings.source == CaptureSource.TEST_PATTERN) {
            source = "synthetic test pattern"
            return
        }
        try {
            val system = cameraSystem()
            this.system = system
            val camera = system.defaultCamera(settings.lensFacing)
                ?: system.cameras().firstOrNull()
            if (camera == null) {
                source = "no camera - synthetic test pattern"
                return
            }
            val session = system.open(
                camera,
                CameraConfig(
                    format = settings.format(),
                    lensFacing = settings.lensFacing,
                    // The example applies rotation and mirroring itself so the
                    // camera and the test pattern share one transform path.
                    rotationDegrees = 0,
                    mirrorHorizontally = false,
                ),
            )
            this.session = session
            session.start(
                { frame -> latest.store(frame.toI420()) },
                { failure ->
                    error = failure.message ?: failure.toString()
                    source = "camera error - last picture"
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

    /** Releases the camera when the generated pattern becomes the source. */
    fun useTestPattern() {
        close()
        error = null
        source = "synthetic test pattern"
    }

    fun close() {
        runCatching { session?.close() }
        session = null
        runCatching { system?.close() }
        system = null
    }
}

/**
 * x265 encoder feeding the best available HEVC decoder: MediaCodec on Android,
 * WebCodecs in the browser and libde265 everywhere else.
 */
private class EncodeDecodeLoop {
    private var encoder: X265Encoder? = null
    private val libraryDecoder = Libde265Decoder.create()
    private var platformDecoder: PlatformDecoder? = null
    private var headersSent = false

    var encodedFrames = 0L
        private set

    var encodedBytes = 0L
        private set

    var decodedFrames = 0L
        private set

    var lastFrameSize = 0
        private set

    var error: String? = null
        private set

    private var pumps = 0L
    private var platformFramesAtStart = 0L

    fun step(frame: I420Frame): I420Frame? {
        if (error != null) return null
        return try {
            val active = encoder ?: open(frame.width, frame.height)
            pumps++
            if (!headersSent) {
                headersSent = true
                feed(active.headers().parameterSets(), 0L, true)
            }
            val encoded = active.encode(
                X265Picture.i420(
                    frame.y, frame.u, frame.v,
                    frame.width, frame.height,
                    if (frame.timestampNanos > 0) frame.timestampNanos / 1_000 else 0L,
                ),
            )
            if (encoded != null) {
                encodedFrames++
                encodedBytes += encoded.byteSize
                lastFrameSize = encoded.byteSize
                feed(encoded.toAnnexB(), encoded.pts, encoded.isKeyFrame)
            }
            if (platformDecoder != null && decodedFrames == platformFramesAtStart && pumps > 60) {
                // MediaCodec/WebCodecs refused every packet (unsupported codec or
                // surface); fall back to the binding decoder.
                runCatching { platformDecoder?.close() }
                platformDecoder = null
            }
            lastDecoded
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
            null
        }
    }

    private var lastDecoded: I420Frame? = null

    private fun feed(data: ByteArray, pts: Long, keyFrame: Boolean) {
        val platform = platformDecoder
        if (platform != null) {
            for (frame in platform.decode(EncodedChunk(data, pts, keyFrame))) {
                decodedFrames++
                lastDecoded = frame
            }
            return
        }
        for (frame in libraryDecoder.decode(data, pts)) {
            if (frame.chroma != De265Chroma.YUV420) continue
            decodedFrames++
            lastDecoded = I420Frame(frame.width, frame.height, frame.y, frame.u, frame.v, frame.pts)
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
            val opened = X265Encoder.open(param)
            platformFramesAtStart = decodedFrames
            platformDecoder = createPlatformDecoder(
                PlatformCodecKind.HEVC,
                width,
                height,
                opened.headers().parameterSets(),
            )
            return opened.also { encoder = it }
        } finally {
            param.close()
        }
    }

    fun close() {
        runCatching { encoder?.close() }
        runCatching { libraryDecoder.close() }
        runCatching { platformDecoder?.close() }
        encoder = null
        platformDecoder = null
    }
}

/** Deterministic I420 colour bars with a moving box, used when no camera exists. */
private class TestPattern(private val width: Int, private val height: Int) {
    private var frame = 0L

    fun next(): I420Frame {
        val y = ByteArray(width * height)
        val u = ByteArray(((width + 1) / 2) * ((height + 1) / 2))
        val v = ByteArray(u.size)
        val bars = intArrayOf(16, 60, 100, 140, 180, 220, 250, 16)
        for (row in 0 until height) {
            for (column in 0 until width) {
                y[row * width + column] = bars[(column * bars.size / width).coerceIn(0, bars.size - 1)].toByte()
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
        u.fill(128.toByte())
        v.fill(128.toByte())
        frame++
        return I420Frame(width, height, y, u, v, frame * 33_333_333L)
    }
}
