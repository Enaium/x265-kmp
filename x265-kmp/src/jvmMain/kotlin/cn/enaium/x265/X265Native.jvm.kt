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

// =========================================================================
// JNI bridge: declarations are shared by the JVM and Android targets.
// =========================================================================

internal object JniX265 {
    init {
        NativeLoader.load()
    }

    external fun nativeVersionPacked(): Int

    external fun nativeVersionString(): String

    external fun nativeApiBuild(): Int

    external fun nativeParamsCreate(width: Int, height: Int, csp: Int): Long

    external fun nativeParamsFree(handle: Long)

    external fun nativeParamsParse(handle: Long, name: String, value: String?): Int

    external fun nativeParamsPreset(handle: Long, preset: String): Int

    external fun nativeParamsTune(handle: Long, tune: String): Int

    external fun nativeParamsProfile(handle: Long, profile: String?): Int

    external fun nativeParamsValidate(handle: Long): Int

    external fun nativeParamsGetInt(handle: Long, field: Int): Int

    external fun nativeEncoderOpen(handle: Long): Long

    external fun nativeEncoderClose(handle: Long)

    external fun nativeEncoderParameters(encoder: Long, params: Long): Int

    external fun nativeEncoderHeaders(encoder: Long): Int

    external fun nativeEncode(
        encoder: Long,
        y: ByteArray,
        u: ByteArray?,
        v: ByteArray?,
        width: Int,
        height: Int,
        csp: Int,
        strideY: Int,
        strideU: Int,
        strideV: Int,
        pts: Long,
    ): Int

    external fun nativeFlush(encoder: Long): Int

    external fun nativeOutputBlob(encoder: Long): ByteArray?
}

internal actual class X265ParamsHandle actual constructor() {
    internal var pointer: Long = 0
}

internal actual class X265EncoderHandle actual constructor() {
    internal var pointer: Long = 0
}

internal actual object X265Native {
    actual fun versionPacked(): Int = JniX265.nativeVersionPacked()

    actual fun versionString(): String = JniX265.nativeVersionString()

    actual fun apiBuild(): Int = JniX265.nativeApiBuild()

    actual fun paramsCreate(width: Int, height: Int, csp: Int): X265ParamsHandle? {
        val pointer = JniX265.nativeParamsCreate(width, height, csp)
        if (pointer == 0L) return null
        return X265ParamsHandle().also { it.pointer = pointer }
    }

    actual fun paramsFree(handle: X265ParamsHandle) {
        JniX265.nativeParamsFree(handle.pointer)
    }

    actual fun paramsParse(handle: X265ParamsHandle, name: String, value: String?): Int =
        JniX265.nativeParamsParse(handle.pointer, name, value)

    actual fun paramsPreset(handle: X265ParamsHandle, preset: String): Int =
        JniX265.nativeParamsPreset(handle.pointer, preset)

    actual fun paramsTune(handle: X265ParamsHandle, tune: String): Int =
        JniX265.nativeParamsTune(handle.pointer, tune)

    actual fun paramsProfile(handle: X265ParamsHandle, profile: String): Int =
        JniX265.nativeParamsProfile(handle.pointer, profile)

    actual fun paramsValidate(handle: X265ParamsHandle): Int =
        JniX265.nativeParamsValidate(handle.pointer)

    actual fun paramsGetInt(handle: X265ParamsHandle, field: Int): Int =
        JniX265.nativeParamsGetInt(handle.pointer, field)

    actual fun encoderOpen(params: X265ParamsHandle): X265EncoderHandle? {
        val pointer = JniX265.nativeEncoderOpen(params.pointer)
        if (pointer == 0L) return null
        return X265EncoderHandle().also { it.pointer = pointer }
    }

    actual fun encoderClose(encoder: X265EncoderHandle) {
        JniX265.nativeEncoderClose(encoder.pointer)
    }

    actual fun encoderParameters(encoder: X265EncoderHandle, params: X265ParamsHandle): Int =
        JniX265.nativeEncoderParameters(encoder.pointer, params.pointer)

    actual fun encoderHeaders(encoder: X265EncoderHandle): ByteArray? {
        val result = JniX265.nativeEncoderHeaders(encoder.pointer)
        if (result < 0) throw X265Exception("x265 rejected the header request (code $result)")
        if (result == X265_BUFFERED) return null
        return JniX265.nativeOutputBlob(encoder.pointer)
            ?: throw X265Exception("x265 produced headers but no output buffer")
    }

    actual fun encode(encoder: X265EncoderHandle, picture: X265Picture): ByteArray? {
        val result = JniX265.nativeEncode(
            encoder.pointer,
            picture.y,
            picture.u,
            picture.v,
            picture.width,
            picture.height,
            picture.chromaFormat.value,
            picture.strideY,
            picture.strideU,
            picture.strideV,
            picture.pts,
        )
        return when (result) {
            X265_OK -> JniX265.nativeOutputBlob(encoder.pointer)
                ?: throw X265Exception("x265 produced a picture but no output buffer")
            X265_BUFFERED -> null
            else -> throw X265Exception("x265 failed to encode a picture (code $result)")
        }
    }

    actual fun flush(encoder: X265EncoderHandle): ByteArray? {
        val result = JniX265.nativeFlush(encoder.pointer)
        return when (result) {
            X265_OK -> JniX265.nativeOutputBlob(encoder.pointer)
                ?: throw X265Exception("x265 produced a picture but no output buffer")
            X265_BUFFERED -> null
            else -> throw X265Exception("x265 failed to flush (code $result)")
        }
    }
}
