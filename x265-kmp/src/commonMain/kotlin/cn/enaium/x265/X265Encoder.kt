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

package cn.enaium.x265

/**
 * A configured HEVC/H.265 encoder.
 *
 * The encoder is stateful and not thread-safe: call [encode] from one thread at
 * a time. On the JVM and Kotlin/Native x265 runs its own worker threads, so the
 * call itself returns as soon as the frame is accepted or a picture falls out
 * of the lookahead/B-frame reorder buffer.
 *
 * ```kotlin
 * val param = X265Param.create(1280, 720).applyPreset(X265Preset.ULTRAFAST).crf(28.0)
 * X265Encoder.open(param).use { encoder ->
 *     encoder.headers()               // VPS/SPS/PPS for MediaCodec's csd-0
 *     while (frames.hasNext()) {
 *         encoder.encode(frame)?.let { send(it.toAnnexB()) }
 *     }
 *     encoder.flush().forEach { send(it.toAnnexB()) }
 * }
 * ```
 */
class X265Encoder private constructor(
    private val handle: X265EncoderHandle,
    /** The parameters the encoder actually runs with (after preset and open-time adjustments). */
    val parameters: X265Param,
) : AutoCloseable {
    private var closed = false

    /** True for builds that run x265 without a worker pool (the web targets). */
    val isSingleThreaded: Boolean get() = parameters.frameThreads == 1

    /**
     * Stream headers (VPS, SPS, PPS), required as `csd-0` by MediaCodec and by
     * any muxer that does not guess them from the first keyframe.
     *
     * x265 serialises the parameter sets on every call, so calling this again
     * simply returns the same VPS/SPS/PPS.
     */
    fun headers(): X265Frame {
        checkOpen()
        val blob = X265Native.encoderHeaders(handle)
            ?: return X265Frame(emptyList(), 0, 0, X265SliceType.AUTO)
        return X265Output.parse(blob)
    }

    /**
     * Encodes one picture.
     *
     * Returns the encoded picture, or `null` when x265 buffered the input for
     * frame reordering. With B-frames enabled several calls may return `null`
     * before a picture comes out; [flush] drains the remainder.
     */
    fun encode(picture: X265Picture): X265Frame? {
        checkOpen()
        val blob = X265Native.encode(handle, picture) ?: return null
        return X265Output.parse(blob)
    }

    /** Encodes one tightly packed picture. */
    fun encode(
        y: ByteArray,
        u: ByteArray?,
        v: ByteArray?,
        width: Int,
        height: Int,
        chromaFormat: X265ChromaFormat = X265ChromaFormat.I420,
        pts: Long = 0L,
    ): X265Frame? = encode(
        X265Picture(
            y = y,
            u = u,
            v = v,
            width = width,
            height = height,
            chromaFormat = chromaFormat,
            pts = pts,
        ),
    )

    /**
     * Drains every delayed picture. Call once after the last input frame;
     * afterwards the encoder must not be used for new input.
     */
    fun flush(): List<X265Frame> {
        checkOpen()
        val frames = ArrayList<X265Frame>()
        while (true) {
            val blob = X265Native.flush(handle) ?: break
            frames.add(X265Output.parse(blob))
        }
        return frames
    }

    override fun close() {
        if (closed) return
        closed = true
        X265Native.encoderClose(handle)
        parameters.close()
    }

    override fun toString(): String = "X265Encoder($parameters)"

    private fun checkOpen() {
        check(!closed) { "X265Encoder is closed" }
    }

    companion object {
        /**
         * Opens an encoder for [parameters].
         *
         * The parameter block is copied; it can be closed or reused immediately
         * after this call. The effective values are available from [parameters]
         * on the returned encoder.
         */
        fun open(parameters: X265Param): X265Encoder {
            if (!parameters.validate()) {
                throw X265Exception(
                    "x265 rejected ${parameters.width}x${parameters.height} " +
                        "${parameters.chromaFormat}: check the size, frame rate and chroma format",
                )
            }
            val handle = X265Native.encoderOpen(parameters.handle)
                ?: throw X265Exception(
                    "x265 failed to open an encoder for ${parameters.width}x${parameters.height} " +
                        "${parameters.chromaFormat}; enable logging with X265LogLevel.INFO for details",
                )
            val effective = try {
                X265Param.create(
                    parameters.width,
                    parameters.height,
                    parameters.chromaFormat,
                )
            } catch (e: Throwable) {
                X265Native.encoderClose(handle)
                throw e
            }
            if (X265Native.encoderParameters(handle, effective.handle) < 0) {
                effective.close()
                X265Native.encoderClose(handle)
                throw X265Exception("x265 did not expose the encoder parameters")
            }
            return X265Encoder(handle, effective)
        }
    }
}
