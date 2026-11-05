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

package cn.enaium.x265.examples.androidnative

import cn.enaium.libyuv.LibYuv
import cn.enaium.libyuv.Rotation
import cn.enaium.x265.X265ChromaFormat
import cn.enaium.x265.X265Encoder
import cn.enaium.x265.X265LogLevel
import cn.enaium.x265.X265Param
import cn.enaium.x265.X265Picture
import cn.enaium.x265.X265Preset
import cn.enaium.x265.X265Tune
import kotlin.math.abs

private const val WIDTH = 640
private const val HEIGHT = 360
private const val FRAME_INTERVAL_US = 33_333L

private val BARS = intArrayOf(
    0xFFFFFF, 0xFFFF00, 0x00FFFF, 0x00FF00,
    0xFF00FF, 0xFF0000, 0x0000FF, 0x000000,
)

/**
 * Desktop preview pipeline.
 *
 * Android's MediaCodec is not available on macOS, so this target only runs the
 * capture and x265 half of the loop with a generated source; the preview shows
 * the source frame. It is what lets the example be run and the encoder
 * inspected without an Android device.
 */
private class DesktopVideoPipeline : VideoPipeline {
    private val rgb24 = ByteArray(WIDTH * HEIGHT * 3)
    private val sourceY = ByteArray(WIDTH * HEIGHT)
    private val sourceU = ByteArray(((WIDTH + 1) / 2) * ((HEIGHT + 1) / 2))
    private val sourceV = ByteArray(sourceU.size)
    private var rotatedY = ByteArray(0)
    private var rotatedU = ByteArray(0)
    private var rotatedV = ByteArray(0)
    private var rgba = ByteArray(0)
    private var encoder: X265Encoder? = null
    private var encoderWidth = 0
    private var encoderHeight = 0
    private var latest: VideoFrame? = null
    private var animation = 0
    private var rotationIndex = 0
    private var lastRotation = -1
    private var encodedFrames = 0L
    private var encodedBytes = 0L
    private var presentationTimeUs = 0L
    private var error: String? = null

    override val status: PipelineStatus
        get() = PipelineStatus(
            source = "synthetic ${WIDTH}x$HEIGHT RGB24 pattern (desktop preview)",
            path = "RGB24 -> I420 -> (rotate) -> x265 HEVC encode; " +
                "MediaCodec decoding is Android-only, previewing the source",
            rotation = rotationName(),
            encodedFrames = encodedFrames,
            encodedBytes = encodedBytes,
            decodedFrames = 0,
            error = error,
        )

    override fun pump() {
        try {
            animation++
            fillRgb24()
            LibYuv.rgb24ToI420(rgb24, sourceY, sourceU, sourceV, WIDTH, HEIGHT)

            val rotation = Rotation.entries[rotationIndex]
            val rotated = rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.CLOCKWISE_270
            val outWidth = if (rotated) HEIGHT else WIDTH
            val outHeight = if (rotated) WIDTH else HEIGHT
            val y: ByteArray
            val u: ByteArray
            val v: ByteArray
            if (rotation == Rotation.NONE) {
                y = sourceY
                u = sourceU
                v = sourceV
            } else {
                if (lastRotation != rotationIndex) {
                    rotatedY = ByteArray(outWidth * outHeight)
                    rotatedU = ByteArray(((outWidth + 1) / 2) * ((outHeight + 1) / 2))
                    rotatedV = ByteArray(rotatedU.size)
                    lastRotation = rotationIndex
                }
                LibYuv.i420Rotate(
                    sourceY, sourceU, sourceV,
                    rotatedY, rotatedU, rotatedV,
                    WIDTH, HEIGHT, rotation,
                )
                y = rotatedY
                u = rotatedU
                v = rotatedV
            }

            encode(y, u, v, outWidth, outHeight)

            val size = outWidth * outHeight * 4
            if (rgba.size != size) rgba = ByteArray(size)
            LibYuv.i420ToRgba(y, u, v, rgba, outWidth, outHeight)
            latest = VideoFrame(outWidth, outHeight, rgba)
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
        }
    }

    override fun currentFrame(): VideoFrame? = latest

    override fun restart() {
        close()
        animation = 0
        rotationIndex = 0
        lastRotation = -1
        latest = null
        encodedFrames = 0
        encodedBytes = 0
        presentationTimeUs = 0
        error = null
    }

    override fun cycleRotation() {
        rotationIndex = (rotationIndex + 1) % Rotation.entries.size
        lastRotation = -1
    }

    override fun close() {
        encoder?.close()
        encoder = null
        encoderWidth = 0
        encoderHeight = 0
    }

    private fun encode(y: ByteArray, u: ByteArray, v: ByteArray, width: Int, height: Int) {
        if (encoder == null || encoderWidth != width || encoderHeight != height) {
            encoder?.close()
            val param = X265Param.create(
                width = width,
                height = height,
                chromaFormat = X265ChromaFormat.I420,
                preset = X265Preset.ULTRAFAST,
            )
            try {
                param.tune(X265Tune.ZERO_LATENCY)
                param.set("crf", 26)
                param.set("keyint", 30)
                param.logLevel = X265LogLevel.NONE
                encoder = X265Encoder.open(param)
                encoderWidth = width
                encoderHeight = height
            } finally {
                param.close()
            }
        }
        val live = encoder ?: return
        val pts = presentationTimeUs
        presentationTimeUs += FRAME_INTERVAL_US
        val frame = live.encode(X265Picture.i420(y, u, v, width, height, pts)) ?: return
        encodedFrames++
        encodedBytes += frame.byteSize
    }

    private fun fillRgb24() {
        val scanX = (animation * 4) % WIDTH
        var offset = 0
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val bar = BARS[(x * BARS.size) / WIDTH]
                var r = (bar shr 16) and 0xFF
                var g = (bar shr 8) and 0xFF
                var b = bar and 0xFF
                if (abs(x - scanX) < 6) {
                    r = 255
                    g = 255
                    b = 255
                }
                rgb24[offset++] = r.toByte()
                rgb24[offset++] = g.toByte()
                rgb24[offset++] = b.toByte()
            }
        }
    }

    private fun rotationName(): String = when (Rotation.entries[rotationIndex]) {
        Rotation.NONE -> "0 deg"
        Rotation.CLOCKWISE_90 -> "90 deg"
        Rotation.ROTATE_180 -> "180 deg"
        Rotation.CLOCKWISE_270 -> "270 deg"
    }
}

internal actual fun createVideoPipeline(): VideoPipeline = DesktopVideoPipeline()
