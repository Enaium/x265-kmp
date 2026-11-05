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

@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package cn.enaium.x265

import kotlin.io.encoding.Base64

/**
 * Web bridge shared by the Kotlin/JS and Kotlin/Wasm targets.
 *
 * The encoder is a stateful C library, so instead of packing every call into a
 * single dispatcher (the libyuv approach) the JavaScript glue exposes one
 * function per C entry point and keeps the exported function table. Only the
 * picture payload and the result blob cross the boundary as base64 strings,
 * because those are the two calls that carry bulk data.
 *
 * Pointers are 32-bit WebAssembly addresses and therefore `Int`s here. They are
 * widened to `Long` on the common handle types.
 */

/** `XKY_INPUT_MAGIC`. */
internal const val X265_INPUT_MAGIC: Int = 0x50594E49

/** `XKY_INPUT_HEADER_SIZE`. */
internal const val X265_INPUT_HEADER_SIZE: Int = 56

/** Parameter operations understood by `__x265Web.paramsConfigure`. */
internal const val X265_PARAMS_PARSE: Int = 0
internal const val X265_PARAMS_PRESET: Int = 1
internal const val X265_PARAMS_TUNE: Int = 2
internal const val X265_PARAMS_PROFILE: Int = 3

internal expect fun webInit()

internal expect fun webVersionPacked(): Int

internal expect fun webVersionString(): String

internal expect fun webApiBuild(): Int

internal expect fun webParamsCreate(width: Int, height: Int, csp: Int): Int

internal expect fun webParamsFree(handle: Int)

internal expect fun webParamsConfigure(handle: Int, operation: Int, name: String, value: String): Int

internal expect fun webParamsValidate(handle: Int): Int

internal expect fun webParamsGetInt(handle: Int, field: Int): Int

internal expect fun webEncoderOpen(params: Int): Int

internal expect fun webEncoderClose(encoder: Int)

internal expect fun webEncoderParameters(encoder: Int, params: Int): Int

/** Returns `"<code>:<base64 blob>"`. */
internal expect fun webEncoderHeaders(encoder: Int): String

/** Returns `"<code>:<base64 blob>"`. */
internal expect fun webEncode(encoder: Int, payload: String): String

/** Returns `"<code>:<base64 blob>"`. */
internal expect fun webFlush(encoder: Int): String

/**
 * Packs a picture into the header `xky_encode_packed()` parses.
 *
 * Layout (little-endian, 56-byte header):
 * magic, width, height, csp, strideY, strideU, strideV, reserved, pts (i64),
 * plane offsets[3], reserved, then the plane bytes.
 */
internal object X265Packing {
    fun pack(picture: X265Picture): String {
        val y = picture.y
        val u = picture.u
        val v = picture.v
        val uOffset = X265_INPUT_HEADER_SIZE + y.size
        val vOffset = uOffset + (u?.size ?: 0)
        val packet = ByteArray(vOffset + (v?.size ?: 0))

        writeInt(packet, 0, X265_INPUT_MAGIC)
        writeInt(packet, 4, picture.width)
        writeInt(packet, 8, picture.height)
        writeInt(packet, 12, picture.chromaFormat.value)
        writeInt(packet, 16, picture.strideY)
        writeInt(packet, 20, picture.strideU)
        writeInt(packet, 24, picture.strideV)
        writeInt(packet, 28, 0)
        writeLong(packet, 32, picture.pts)
        writeInt(packet, 40, if (y.isEmpty()) 0 else X265_INPUT_HEADER_SIZE)
        writeInt(packet, 44, if (u == null || u.isEmpty()) 0 else uOffset)
        writeInt(packet, 48, if (v == null || v.isEmpty()) 0 else vOffset)
        writeInt(packet, 52, 0)

        y.copyInto(packet, X265_INPUT_HEADER_SIZE)
        if (u != null) u.copyInto(packet, uOffset)
        if (v != null) v.copyInto(packet, vOffset)
        return Base64.encode(packet)
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun writeLong(target: ByteArray, offset: Int, value: Long) {
        writeInt(target, offset, value.toInt())
        writeInt(target, offset + 4, (value ushr 32).toInt())
    }
}

/** Splits the `"<code>:<base64>"` envelope the JavaScript glue returns. */
internal fun parseWebResult(result: String, what: String): ByteArray? {
    val separator = result.indexOf(':')
    check(separator > 0) { "malformed x265 web result during $what: $result" }
    val code = result.substring(0, separator).toInt()
    return when (code) {
        X265_NO_OUTPUT -> null
        X265_OK -> {
            val encoded = result.substring(separator + 1)
            if (encoded.isEmpty()) {
                throw X265Exception("x265 reported output during $what but sent no payload")
            }
            Base64.decode(encoded)
        }
        else -> throw X265Exception("x265 failed during $what (code $code)")
    }
}

/** The C `XKY_BUFFERED` constant, named locally to keep the envelope readable. */
private const val X265_NO_OUTPUT: Int = 0
