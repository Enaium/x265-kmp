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

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.enaium.x265.examples.androidnative

import cn.enaium.libyuv.LibYuv
import cn.enaium.libyuv.Rotation
import cn.enaium.sdl.SDL
import cn.enaium.x265.X265ChromaFormat
import cn.enaium.x265.X265Encoder
import cn.enaium.x265.X265LogLevel
import cn.enaium.x265.X265Param
import cn.enaium.x265.X265Picture
import cn.enaium.x265.X265Preset
import cn.enaium.x265.X265Tune
import cnames.structs.AMediaCodec
import cnames.structs.AMediaFormat
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.media.AMediaCodecBufferInfo
import platform.media.AMediaCodec_configure
import platform.media.AMediaCodec_createDecoderByType
import platform.media.AMediaCodec_dequeueInputBuffer
import platform.media.AMediaCodec_dequeueOutputBuffer
import platform.media.AMediaCodec_delete
import platform.media.AMediaCodec_getInputBuffer
import platform.media.AMediaCodec_getOutputBuffer
import platform.media.AMediaCodec_getOutputFormat
import platform.media.AMediaCodec_queueInputBuffer
import platform.media.AMediaCodec_releaseOutputBuffer
import platform.media.AMediaCodec_start
import platform.media.AMediaCodec_stop
import platform.media.AMediaFormat_delete
import platform.media.AMediaFormat_getInt32
import platform.media.AMediaFormat_new
import platform.media.AMediaFormat_setBuffer
import platform.media.AMediaFormat_setInt32
import platform.media.AMediaFormat_setString
import platform.media.AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG
import platform.media.AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM
import platform.media.AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED
import platform.media.AMEDIACODEC_INFO_TRY_AGAIN_LATER
import platform.posix.memcpy
import platform.posix.size_tVar

private const val MIME_HEVC = "video/hevc"

/** MediaCodecInfo.CodecCapabilities color formats, from the Android SDK. */
private const val COLOR_YUV420_PLANAR = 19
private const val COLOR_YUV420_SEMI_PLANAR = 21

/** Microseconds between two camera frames at 30 fps. */
private const val FRAME_INTERVAL_US = 33_333L

private data class EncodedPacket(val data: ByteArray, val ptsUs: Long)

/**
 * The Android native loopback:
 *
 *   Camera2 (YUV_420_888) -> libyuv Android420ToI420
 *     -> x265 HEVC encoder -> MediaCodec HEVC decoder (NDK AMediaCodec)
 *     -> libyuv I420ToRGBA -> ImGui texture
 *
 * The encoder is the Kotlin Multiplatform x265 binding linked into `libmain.so`;
 * the decoder uses the NDK's `AMediaCodec` C API, which is the native face of
 * `android.media.MediaCodec`.
 */
private class AndroidVideoPipeline : VideoPipeline {
    private var camera: Camera2Source? = null
    private var cameraWidth = 0
    private var cameraHeight = 0
    private var encodedWidth = 0
    private var encodedHeight = 0
    private var encoder: X265Encoder? = null
    private var parameterSets: ByteArray? = null
    private var decoder: CPointer<AMediaCodec>? = null
    private var decoderColor = COLOR_YUV420_SEMI_PLANAR
    private var decoderStride = 0
    private var decoderSliceHeight = 0

    private var sourceY = ByteArray(0)
    private var sourceU = ByteArray(0)
    private var sourceV = ByteArray(0)
    private var rotatedY = ByteArray(0)
    private var rotatedU = ByteArray(0)
    private var rotatedV = ByteArray(0)
    private var decodedY = ByteArray(0)
    private var decodedU = ByteArray(0)
    private var decodedV = ByteArray(0)

    private var latest: VideoFrame? = null
    private var started = false
    private var retryAt = 0uL
    private var error: String? = null
    private var encodedFrames = 0L
    private var encodedBytes = 0L
    private var decodedFrames = 0L
    private var presentationTimeUs = 0L
    private var rotationIndex = 0
    private val packets = ArrayDeque<EncodedPacket>()

    override val status: PipelineStatus
        get() = PipelineStatus(
            source = "camera ${cameraWidth}x$cameraHeight Camera2 YUV_420_888",
            path = "camera -> I420 -> x265 HEVC encode -> MediaCodec decode -> I420 -> RGBA",
            rotation = rotationName(),
            encodedFrames = encodedFrames,
            encodedBytes = encodedBytes,
            decodedFrames = decodedFrames,
            error = error,
        )

    override fun pump() {
        if (!started) {
            val now = SDL.getTicks()
            if (now < retryAt) return
            if (!start()) {
                retryAt = SDL.getTicks() + 2_000u
                return
            }
        }
        try {
            pumpCamera()
            feedDecoder()
            drainDecoder()
        } catch (t: Throwable) {
            fail(t.message ?: t.toString())
        }
    }

    override fun currentFrame(): VideoFrame? = latest

    override fun restart() {
        close()
        latest = null
        encodedFrames = 0
        encodedBytes = 0
        decodedFrames = 0
        presentationTimeUs = 0
        retryAt = 0u
        error = null
    }

    override fun cycleRotation() {
        rotationIndex = (rotationIndex + 1) % Rotation.entries.size
        restart()
    }

    override fun close() {
        camera?.close()
        camera = null
        closeCodecs()
        started = false
    }

    private fun start(): Boolean = try {
        val source = Camera2Source(640, 480).also { it.open() }
        camera = source
        cameraWidth = source.width
        cameraHeight = source.height
        encodedWidth = if (rotated()) cameraHeight else cameraWidth
        encodedHeight = if (rotated()) cameraWidth else cameraHeight
        allocateSourceBuffers()
        startEncoder()
        startDecoder()
        started = true
        error = null
        true
    } catch (t: Throwable) {
        close()
        error = t.message ?: t.toString()
        false
    }

    private fun allocateSourceBuffers() {
        val w = cameraWidth
        val h = cameraHeight
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        sourceY = ByteArray(w * h)
        sourceU = ByteArray(cw * ch)
        sourceV = ByteArray(cw * ch)
    }

    private fun startEncoder() {
        val param = X265Param.create(
            width = encodedWidth,
            height = encodedHeight,
            chromaFormat = X265ChromaFormat.I420,
            preset = X265Preset.ULTRAFAST,
        )
        try {
            param.tune(X265Tune.ZERO_LATENCY)
            param.set("crf", 26)
            param.set("keyint", 30)
            param.set("repeat-headers", true)
            param.logLevel = X265LogLevel.NONE
            val opened = X265Encoder.open(param)
            val csd = opened.headers().parameterSets()
            check(csd.isNotEmpty()) { "x265 produced no VPS/SPS/PPS" }
            parameterSets = csd
            encoder = opened
        } finally {
            param.close()
        }
    }

    private fun startDecoder() {
        val csd = parameterSets ?: throw IllegalStateException("no HEVC parameter sets")
        val codec = AMediaCodec_createDecoderByType(MIME_HEVC)
            ?: throw IllegalStateException("no HEVC decoder")
        val format = AMediaFormat_new() ?: run {
            AMediaCodec_delete(codec)
            throw IllegalStateException("AMediaFormat_new failed")
        }
        try {
            AMediaFormat_setString(format, "mime", MIME_HEVC)
            AMediaFormat_setInt32(format, "width", encodedWidth)
            AMediaFormat_setInt32(format, "height", encodedHeight)
            csd.usePinned {
                AMediaFormat_setBuffer(format, "csd-0", it.addressOf(0), csd.size.convert())
            }
            if (AMediaCodec_configure(codec, format, null, null, 0u) != 0 ||
                AMediaCodec_start(codec) != 0
            ) {
                throw IllegalStateException("decoder configure/start failed")
            }
            decoder = codec
            decoderColor = COLOR_YUV420_SEMI_PLANAR
            decoderStride = encodedWidth
            decoderSliceHeight = encodedHeight
            allocateDecoderBuffers()
        } catch (t: Throwable) {
            AMediaCodec_delete(codec)
            throw t
        } finally {
            AMediaFormat_delete(format)
        }
    }

    private fun allocateDecoderBuffers() {
        val w = encodedWidth
        val h = encodedHeight
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        decodedY = ByteArray(w * h)
        decodedU = ByteArray(cw * ch)
        decodedV = ByteArray(cw * ch)
    }

    private fun pumpCamera() {
        val cam = camera ?: return
        if (!cam.acquire(sourceY, sourceU, sourceV)) return
        val frame = rotatedI420(sourceY, sourceU, sourceV)
        val pts = presentationTimeUs
        presentationTimeUs += FRAME_INTERVAL_US
        encodeFrame(frame.first, frame.second, frame.third, pts)
    }

    private fun rotatedI420(
        y: ByteArray,
        u: ByteArray,
        v: ByteArray,
    ): Triple<ByteArray, ByteArray, ByteArray> {
        val rotation = Rotation.entries[rotationIndex]
        if (rotation == Rotation.NONE) return Triple(y, u, v)
        val w = encodedWidth
        val h = encodedHeight
        if (rotatedY.size != w * h) {
            rotatedY = ByteArray(w * h)
            rotatedU = ByteArray(((w + 1) / 2) * ((h + 1) / 2))
            rotatedV = ByteArray(rotatedU.size)
        }
        LibYuv.i420Rotate(
            y, u, v,
            rotatedY, rotatedU, rotatedV,
            cameraWidth, cameraHeight, rotation,
        )
        return Triple(rotatedY, rotatedU, rotatedV)
    }

    private fun encodeFrame(y: ByteArray, u: ByteArray, v: ByteArray, ptsUs: Long) {
        val live = encoder ?: return
        val frame = live.encode(
            X265Picture.i420(y, u, v, encodedWidth, encodedHeight, ptsUs),
        ) ?: return
        encodedFrames++
        encodedBytes += frame.byteSize
        // Decoder input does not need BUFFER_FLAG_KEY_FRAME: the IDR NAL unit
        // already marks the random access point.
        packets.addLast(EncodedPacket(frame.toAnnexB(), frame.pts))
        while (packets.size > 60) packets.removeFirst()
    }

    private fun feedDecoder() {
        val codec = decoder ?: return
        while (packets.isNotEmpty()) {
            val index = AMediaCodec_dequeueInputBuffer(codec, 0)
            if (index < 0) return
            val packet = packets.removeFirst()
            memScoped {
                val capacity = alloc<size_tVar>()
                // size_t is UInt on 32-bit ABIs and ULong on 64-bit ones.
                val buffer = AMediaCodec_getInputBuffer(codec, index.convert(), capacity.ptr)
                    ?: throw IllegalStateException("decoder input buffer is null")
                if (packet.data.size > capacity.value.toLong()) {
                    error = "decoder input ${capacity.value} < packet ${packet.data.size}"
                    return
                }
                buffer.write(packet.data, packet.data.size)
                val result = AMediaCodec_queueInputBuffer(
                    codec,
                    index.convert(),
                    0,
                    packet.data.size.convert(),
                    packet.ptsUs.toULong(),
                    0u,
                )
                if (result != 0) throw IllegalStateException("decoder queueInputBuffer=$result")
            }
        }
    }

    private fun drainDecoder() {
        val codec = decoder ?: return
        memScoped {
            val info = alloc<AMediaCodecBufferInfo>()
            val bufferSize = alloc<size_tVar>()
            while (true) {
                val index = AMediaCodec_dequeueOutputBuffer(codec, info.ptr, 0)
                if (index.toLong() == AMEDIACODEC_INFO_TRY_AGAIN_LATER.toLong()) break
                if (index.toLong() == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED.toLong()) {
                    readDecoderFormat(codec)
                    continue
                }
                if (index < 0) break
                if (info.size > 0 &&
                    (info.flags.toInt() and AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) == 0
                ) {
                    val buffer = AMediaCodec_getOutputBuffer(codec, index.convert(), bufferSize.ptr)
                    if (buffer != null) {
                        latest = decodeOutput(buffer.read(info.offset, info.size))
                        decodedFrames++
                    }
                }
                AMediaCodec_releaseOutputBuffer(codec, index.convert(), false)
                if ((info.flags.toInt() and AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        }
    }

    private fun readDecoderFormat(codec: CPointer<AMediaCodec>) {
        val format = AMediaCodec_getOutputFormat(codec) ?: return
        memScoped {
            val value = alloc<IntVar>()
            if (AMediaFormat_getInt32(format, "color-format", value.ptr)) decoderColor = value.value
            if (AMediaFormat_getInt32(format, "stride", value.ptr)) decoderStride = value.value
            if (AMediaFormat_getInt32(format, "slice-height", value.ptr)) decoderSliceHeight = value.value
        }
        if (decoderStride <= 0) decoderStride = encodedWidth
        if (decoderSliceHeight <= 0) decoderSliceHeight = encodedHeight
    }

    private fun decodeOutput(data: ByteArray): VideoFrame {
        val w = encodedWidth
        val h = encodedHeight
        val stride = if (decoderStride > 0) decoderStride else w
        val sliceHeight = if (decoderSliceHeight > 0) decoderSliceHeight else h
        val ySize = stride * sliceHeight
        if (data.size < ySize) {
            throw IllegalStateException("decoder output ${data.size} < luma $ySize")
        }
        val rgbaOut = ByteArray(w * h * 4)
        val chromaHeight = (sliceHeight + 1) / 2

        if (decoderColor == COLOR_YUV420_PLANAR) {
            val chromaStride = (stride + 1) / 2
            val uSize = chromaStride * chromaHeight
            val uOffset = ySize
            val vOffset = uOffset + uSize
            if (data.size < vOffset + uSize) {
                throw IllegalStateException("decoder output is smaller than an I420 frame")
            }
            val y = data.copyOfRange(0, ySize)
            val u = data.copyOfRange(uOffset, uOffset + uSize)
            val v = data.copyOfRange(vOffset, vOffset + uSize)
            LibYuv.i420ToRgbaBt709(y, u, v, rgbaOut, w, h, stride, chromaStride, chromaStride)
        } else {
            val chromaStride = stride
            val uvSize = chromaStride * chromaHeight
            val uvOffset = ySize
            if (data.size < uvOffset + uvSize) {
                throw IllegalStateException("decoder output is smaller than an NV12 frame")
            }
            val y = data.copyOfRange(0, ySize)
            val uv = data.copyOfRange(uvOffset, uvOffset + uvSize)
            LibYuv.nv12ToI420(y, uv, decodedY, decodedU, decodedV, w, h, stride, chromaStride)
            LibYuv.i420ToRgbaBt709(decodedY, decodedU, decodedV, rgbaOut, w, h)
        }
        return VideoFrame(w, h, rgbaOut)
    }

    private fun closeCodecs() {
        decoder?.let {
            AMediaCodec_stop(it)
            AMediaCodec_delete(it)
        }
        decoder = null
        encoder?.let { runCatching { it.close() } }
        encoder = null
        parameterSets = null
        packets.clear()
    }

    private fun fail(message: String) {
        error = message
        close()
        retryAt = SDL.getTicks() + 2_000u
    }

    private fun rotated(): Boolean =
        Rotation.entries[rotationIndex] == Rotation.CLOCKWISE_90 ||
            Rotation.entries[rotationIndex] == Rotation.CLOCKWISE_270

    private fun rotationName(): String = when (Rotation.entries[rotationIndex]) {
        Rotation.NONE -> "0 deg"
        Rotation.CLOCKWISE_90 -> "90 deg"
        Rotation.ROTATE_180 -> "180 deg"
        Rotation.CLOCKWISE_270 -> "270 deg"
    }
}

internal actual fun createVideoPipeline(): VideoPipeline = AndroidVideoPipeline()

private fun CPointer<UByteVar>.write(bytes: ByteArray, length: Int) {
    if (length == 0) return
    val dst = interpretCPointer<ByteVar>(rawValue) ?: return
    bytes.usePinned { memcpy(dst, it.addressOf(0), length.convert()) }
}

private fun CPointer<UByteVar>.read(offset: Int, length: Int): ByteArray {
    if (length <= 0) return ByteArray(0)
    val base = interpretCPointer<ByteVar>(rawValue)
        ?: throw IllegalStateException("null MediaCodec buffer")
    return base.plus(offset)?.readFast(length) ?: ByteArray(0)
}

private fun CPointer<*>.readFast(length: Int): ByteArray {
    val out = ByteArray(length)
    if (length == 0) return out
    out.usePinned { memcpy(it.addressOf(0), this, length.convert()) }
    return out
}
