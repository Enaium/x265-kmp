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
 * Thrown when the native x265 library rejects a call: an invalid parameter
 * value, a failed encoder open, or an encoder error during encoding.
 */
class X265Exception(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Opaque parameter block owned by the native library. The actual classes wrap a
 * `xky_params*` (JNI/Kotlin-Native) or a WebAssembly pointer.
 */
internal expect class X265ParamsHandle() {
    // The actuals add the platform pointer; the common API only passes handles
    // between X265Native calls.
}

/** Opaque encoder owned by the native library, see [X265ParamsHandle]. */
internal expect class X265EncoderHandle() {
    // See X265ParamsHandle.
}

/**
 * Bridge to the C shim, implemented for JNI, Kotlin/Native cinterop and the
 * WebAssembly module. Every implementation follows the same contract:
 *
 * - negative return values raise [X265Exception];
 * - `null` pictures mean "no output yet" for [encode]/[flush];
 * - returned blobs are the packed `XKY_OUTPUT_MAGIC` records documented in
 *   `jni/include/x265_kmp.h`.
 */
internal expect object X265Native {
    fun versionPacked(): Int

    fun versionString(): String

    fun apiBuild(): Int

    fun paramsCreate(width: Int, height: Int, csp: Int): X265ParamsHandle?

    fun paramsFree(handle: X265ParamsHandle)

    fun paramsParse(handle: X265ParamsHandle, name: String, value: String?): Int

    fun paramsPreset(handle: X265ParamsHandle, preset: String): Int

    fun paramsTune(handle: X265ParamsHandle, tune: String): Int

    fun paramsProfile(handle: X265ParamsHandle, profile: String): Int

    fun paramsValidate(handle: X265ParamsHandle): Int

    fun paramsGetInt(handle: X265ParamsHandle, field: Int): Int

    fun encoderOpen(params: X265ParamsHandle): X265EncoderHandle?

    fun encoderClose(encoder: X265EncoderHandle)

    fun encoderParameters(encoder: X265EncoderHandle, params: X265ParamsHandle): Int

    fun encoderHeaders(encoder: X265EncoderHandle): ByteArray?

    fun encode(encoder: X265EncoderHandle, picture: X265Picture): ByteArray?

    fun flush(encoder: X265EncoderHandle): ByteArray?
}

/** `XKY_OUTPUT_MAGIC`, see `jni/include/x265_kmp.h`. */
internal const val X265_OUTPUT_MAGIC: Int = 0x58594F50

/** `XKY_OUTPUT_VERSION`. */
internal const val X265_OUTPUT_VERSION: Int = 1

/** `XKY_OK`: the call produced a picture. */
internal const val X265_OK: Int = 1

/** `XKY_BUFFERED`: the input was accepted but x265 has no picture yet. */
internal const val X265_BUFFERED: Int = 0

/**
 * Parser for the packed result blob. Written by hand instead of using a
 * platform `ByteBuffer` so the exact same code runs on JVM, Kotlin/Native and
 * the web.
 */
internal object X265Output {
    private const val FIXED_HEADER = 40

    fun parse(bytes: ByteArray): X265Frame {
        require(bytes.size >= FIXED_HEADER) { "truncated x265 output (${bytes.size} bytes)" }
        val magic = readInt(bytes, 0)
        check(magic == X265_OUTPUT_MAGIC) { "bad x265 output magic 0x${magic.toUInt().toString(16)}" }
        val version = readInt(bytes, 4)
        check(version == X265_OUTPUT_VERSION) { "unsupported x265 output version $version" }
        val nalCount = readInt(bytes, 8)
        require(nalCount >= 0) { "negative NAL count $nalCount" }
        val sliceType = X265SliceType.fromValue(readInt(bytes, 12))
        val pts = readLong(bytes, 16)
        val dts = readLong(bytes, 24)
        val payloadSize = readInt(bytes, 32)
        val payloadBase = align8(FIXED_HEADER + nalCount * 12)
        require(payloadSize >= 0 && payloadBase + payloadSize <= bytes.size) {
            "truncated x265 payload ($payloadSize bytes at $payloadBase)"
        }

        val nals = ArrayList<X265Nal>(nalCount)
        for (index in 0 until nalCount) {
            val type = readInt(bytes, FIXED_HEADER + index * 4)
            val offset = readInt(bytes, FIXED_HEADER + nalCount * 4 + index * 4)
            val size = readInt(bytes, FIXED_HEADER + nalCount * 8 + index * 4)
            require(offset >= 0 && size >= 0 && offset + size <= payloadSize) {
                "NAL $index is outside the payload"
            }
            val start = payloadBase + offset
            nals.add(X265Nal(type, bytes.copyOfRange(start, start + size)))
        }
        return X265Frame(nals, pts, dts, sliceType)
    }

    private fun align8(value: Int): Int = (value + 7) and 7.inv()

    private fun readInt(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xFF) or
            ((source[offset + 1].toInt() and 0xFF) shl 8) or
            ((source[offset + 2].toInt() and 0xFF) shl 16) or
            ((source[offset + 3].toInt() and 0xFF) shl 24)

    private fun readLong(source: ByteArray, offset: Int): Long =
        (readInt(source, offset).toLong() and 0xFFFFFFFFL) or
            (readInt(source, offset + 4).toLong() shl 32)
}
