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
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */

package cn.enaium.x265.examples.androidjvm

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import cn.enaium.libyuv.I420Frame
import cn.enaium.x265.X265ChromaFormat
import cn.enaium.x265.X265
import cn.enaium.x265.X265Encoder
import cn.enaium.x265.X265Frame
import cn.enaium.x265.X265LogLevel
import cn.enaium.x265.X265Param
import cn.enaium.x265.X265Picture
import cn.enaium.x265.X265Preset
import cn.enaium.x265.X265Tune
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max

internal data class PipelineStats(
    val encodedFrames: Long,
    val decodedFrames: Long,
    val droppedFrames: Long,
    val bytes: Long,
    val width: Int,
    val height: Int,
    val fps: Double,
)

/**
 * Camera frames in, decoded video on [surface] out.
 *
 * The pipeline is deliberately split the same way a real app would split it:
 * x265 (through the Kotlin Multiplatform binding) produces Annex-B access
 * units on a dedicated worker thread, and MediaCodec decodes them straight to
 * the preview surface. The encoder is created from the first frame, so the
 * camera's chosen resolution wins without a second lookup.
 *
 * Only one thread touches the encoder and the decoder; [submit] is the only
 * method safe to call from the camera executor.
 */
/** Copies a direct plane into a tightly packed array. */
private fun ByteBuffer.toPlaneArray(size: Int): ByteArray {
    val duplicate = duplicate()
    duplicate.clear()
    val out = ByteArray(size)
    duplicate.get(out, 0, minOf(size, duplicate.remaining()))
    return out
}

internal class HevcPipeline(
    private val surface: Surface,
    private val onStats: (PipelineStats) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val queue = ArrayBlockingQueue<I420Frame>(3)

    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ runPipeline() }, "x265-hevc").apply { start() }
    }

    fun submit(frame: I420Frame) {
        if (!running) return
        // Keep the newest frame: encoding slower than the camera must not grow
        // an unbounded backlog.
        while (!queue.offer(frame)) {
            queue.poll()
        }
    }

    fun stop() {
        if (!running) return
        running = false
        queue.clear()
        // Do not block the UI for long: the worker flushes the encoder and
        // releases the codec on its own thread.
        thread?.join(1_000)
        thread = null
    }

    private fun runPipeline() {
        val session = Session(surface, onStats)
        try {
            while (running || queue.isNotEmpty()) {
                val frame = queue.poll(20, TimeUnit.MILLISECONDS) ?: continue
                session.encode(frame)
            }
            session.finish()
        } catch (t: Throwable) {
            onError(t)
        } finally {
            session.close()
        }
    }

    private class Session(
        private val surface: Surface,
        private val onStats: (PipelineStats) -> Unit,
    ) {
        private var encoder: X265Encoder? = null
        private var decoder: MediaCodec? = null
        private var parameterSets: ByteArray? = null
        private var width = 0
        private var height = 0
        private var encodedFrames = 0L
        private var decodedFrames = 0L
        private var droppedFrames = 0L
        private var bytes = 0L
        private var lastPtsUs = 0L
        private var firstAccessUnitLogged = false
        private var lastReportMs = SystemClock.elapsedRealtime()
        private var framesSinceReport = 0

        fun encode(frame: I420Frame) {
            val live = encoder ?: createEncoder(frame.width, frame.height)
            // libyuv-kmp 1.2 hands out direct ByteBuffer planes; the x265
            // binding takes ByteArrays, so each plane is copied once here.
            val y = frame.y.toPlaneArray(frame.width * frame.height)
            val u = frame.u.toPlaneArray(frame.chromaWidth * frame.chromaHeight)
            val v = frame.v.toPlaneArray(frame.chromaWidth * frame.chromaHeight)
            val output = live.encode(
                X265Picture.i420(
                    y = y,
                    u = u,
                    v = v,
                    width = frame.width,
                    height = frame.height,
                    pts = frame.presentationTimeUs,
                ),
            ) ?: return
            publish(output)
        }

        fun finish() {
            val live = encoder ?: return
            live.flush().forEach(::publish)
            signalEndOfStream()
            report(force = true)
        }

        fun close() {
            val codec = decoder
            decoder = null
            if (codec != null) {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
            val live = encoder
            encoder = null
            if (live != null) {
                runCatching { live.close() }
            }
        }

        private fun createEncoder(sourceWidth: Int, sourceHeight: Int): X265Encoder {
            width = sourceWidth
            height = sourceHeight
            val param = X265Param.create(
                width = sourceWidth,
                height = sourceHeight,
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
                val headers = opened.headers()
                parameterSets = headers.parameterSets()
                Log.i(
                    TAG,
                    "x265 ${X265.libraryVersion} $sourceWidth" + "x" + "$sourceHeight, " +
                        "csd=${parameterSets?.size ?: 0} bytes, " +
                        "${opened.parameters.frameThreads} frame thread(s)",
                )
                encoder = opened
                return opened
            } finally {
                param.close()
            }
        }

        private fun publish(frame: X265Frame) {
            encodedFrames++
            bytes += frame.byteSize
            framesSinceReport++
            lastPtsUs = frame.pts

            val codec = ensureDecoder()
            if (codec == null) {
                droppedFrames++
            } else {
                feed(codec, frame.toAnnexB(), frame.pts, frame.isKeyFrame)
                drain(codec, endOfStream = false)
            }
            report(force = false)
        }

        private fun ensureDecoder(): MediaCodec? {
            decoder?.let { return it }
            if (!surface.isValid) return null
            val csd = parameterSets
                ?: throw IllegalStateException("x265 produced no VPS/SPS/PPS to configure the decoder")
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(csd))
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, max(width * height, 256 * 1024))
            }
            val codec = MediaCodec.createDecoderByType(MIME_TYPE)
            codec.configure(format, surface, null, 0)
            codec.start()
            decoder = codec
            Log.i(TAG, "decoder=${codec.name} ${width}x$height csd=${csd.size} bytes")
            return codec
        }

        private fun feed(codec: MediaCodec, accessUnit: ByteArray, ptsUs: Long, keyFrame: Boolean) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index < 0) {
                droppedFrames++
                return
            }
            val buffer = codec.getInputBuffer(index)
            if (buffer == null) {
                droppedFrames++
                return
            }
            if (!firstAccessUnitLogged) {
                firstAccessUnitLogged = true
                Log.i(TAG, "first access unit: ${accessUnit.size} bytes, key=$keyFrame")
            }
            buffer.clear()
            buffer.put(accessUnit)
            codec.queueInputBuffer(
                index,
                0,
                accessUnit.size,
                if (ptsUs > 0) ptsUs else lastPtsUs,
                if (keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
            )
        }

        private fun drain(codec: MediaCodec, endOfStream: Boolean) {
            val info = MediaCodec.BufferInfo()
            while (true) {
                val index = codec.dequeueOutputBuffer(info, 0)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.i(TAG, "decoder output format ${codec.outputFormat}")
                    }
                    index >= 0 -> {
                        codec.releaseOutputBuffer(index, true)
                        decodedFrames++
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                    else -> return
                }
                if (endOfStream) return
            }
        }

        private fun signalEndOfStream() {
            val codec = decoder ?: return
            val index = codec.dequeueInputBuffer(100_000)
            if (index >= 0) {
                codec.queueInputBuffer(
                    index,
                    0,
                    0,
                    max(lastPtsUs, 1),
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
            }
            drain(codec, endOfStream = true)
        }

        private fun report(force: Boolean) {
            val now = SystemClock.elapsedRealtime()
            val elapsed = now - lastReportMs
            if (!force && elapsed < 500) return
            val fps = if (elapsed > 0) framesSinceReport * 1000.0 / elapsed else 0.0
            onStats(
                PipelineStats(
                    encodedFrames = encodedFrames,
                    decodedFrames = decodedFrames,
                    droppedFrames = droppedFrames,
                    bytes = bytes,
                    width = width,
                    height = height,
                    fps = fps,
                ),
            )
            lastReportMs = now
            framesSinceReport = 0
        }

        private companion object {
            const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_HEVC
            const val TAG = "x265-hevc"
        }
    }
}
