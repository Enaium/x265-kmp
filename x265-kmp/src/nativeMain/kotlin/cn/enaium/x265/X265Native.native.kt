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

package cn.enaium.x265

import cnames.structs.xky_encoder
import cnames.structs.xky_params
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import x265_kmp.xky_encoder_close
import x265_kmp.xky_encoder_headers
import x265_kmp.xky_encoder_open
import x265_kmp.xky_encoder_parameters
import x265_kmp.xky_encode_planes
import x265_kmp.xky_flush
import x265_kmp.xky_output_blob
import x265_kmp.xky_params_apply_preset
import x265_kmp.xky_params_apply_profile
import x265_kmp.xky_params_apply_tune
import x265_kmp.xky_params_create
import x265_kmp.xky_params_free
import x265_kmp.xky_params_get_int
import x265_kmp.xky_params_parse
import x265_kmp.xky_params_validate
import x265_kmp.xky_version_packed
import x265_kmp.xky_version_string
import x265_kmp.xky_api_build

internal actual class X265ParamsHandle actual constructor() {
    internal var pointer: CPointer<xky_params>? = null
}

internal actual class X265EncoderHandle actual constructor() {
    internal var pointer: CPointer<xky_encoder>? = null
}

internal actual object X265Native {
    actual fun versionPacked(): Int = xky_version_packed()

    actual fun versionString(): String = xky_version_string()?.toKString() ?: ""

    actual fun apiBuild(): Int = xky_api_build()

    actual fun paramsCreate(width: Int, height: Int, csp: Int): X265ParamsHandle? {
        val pointer = xky_params_create(width, height, csp) ?: return null
        return X265ParamsHandle().also { it.pointer = pointer }
    }

    actual fun paramsFree(handle: X265ParamsHandle) {
        xky_params_free(handle.pointer)
        handle.pointer = null
    }

    actual fun paramsParse(handle: X265ParamsHandle, name: String, value: String?): Int = memScoped {
        xky_params_parse(handle.pointer, name.cstr.ptr, value?.cstr?.ptr)
    }

    actual fun paramsPreset(handle: X265ParamsHandle, preset: String): Int = memScoped {
        xky_params_apply_preset(handle.pointer, preset.cstr.ptr)
    }

    actual fun paramsTune(handle: X265ParamsHandle, tune: String): Int = memScoped {
        xky_params_apply_tune(handle.pointer, tune.cstr.ptr)
    }

    actual fun paramsProfile(handle: X265ParamsHandle, profile: String): Int = memScoped {
        xky_params_apply_profile(handle.pointer, profile.cstr.ptr)
    }

    actual fun paramsValidate(handle: X265ParamsHandle): Int = xky_params_validate(handle.pointer)

    actual fun paramsGetInt(handle: X265ParamsHandle, field: Int): Int =
        xky_params_get_int(handle.pointer, field)

    actual fun encoderOpen(params: X265ParamsHandle): X265EncoderHandle? {
        val pointer = xky_encoder_open(params.pointer) ?: return null
        return X265EncoderHandle().also { it.pointer = pointer }
    }

    actual fun encoderClose(encoder: X265EncoderHandle) {
        xky_encoder_close(encoder.pointer)
        encoder.pointer = null
    }

    actual fun encoderParameters(encoder: X265EncoderHandle, params: X265ParamsHandle): Int =
        xky_encoder_parameters(encoder.pointer, params.pointer)

    actual fun encoderHeaders(encoder: X265EncoderHandle): ByteArray? =
        result(encoder.pointer, xky_encoder_headers(encoder.pointer), "header request")

    actual fun encode(encoder: X265EncoderHandle, picture: X265Picture): ByteArray? {
        val pointer = encoder.pointer ?: throw X265Exception("encoder is closed")
        val u = picture.u
        val v = picture.v
        val result = picture.y.usePinned { y ->
            val yPointer = y.addressOf(0).reinterpret<kotlinx.cinterop.UByteVar>()
            if (u != null && v != null) {
                u.usePinned { uPinned ->
                    v.usePinned { vPinned ->
                        xky_encode_planes(
                            pointer,
                            yPointer,
                            uPinned.addressOf(0).reinterpret(),
                            vPinned.addressOf(0).reinterpret(),
                            picture.strideY,
                            picture.strideU,
                            picture.strideV,
                            picture.width,
                            picture.height,
                            picture.chromaFormat.value,
                            picture.pts,
                        )
                    }
                }
            } else {
                xky_encode_planes(
                    pointer,
                    yPointer,
                    null,
                    null,
                    picture.strideY,
                    picture.strideU,
                    picture.strideV,
                    picture.width,
                    picture.height,
                    picture.chromaFormat.value,
                    picture.pts,
                )
            }
        }
        return result(pointer, result, "encode")
    }

    actual fun flush(encoder: X265EncoderHandle): ByteArray? =
        result(encoder.pointer, xky_flush(encoder.pointer), "flush")

    private fun result(encoder: CPointer<xky_encoder>?, code: Int, what: String): ByteArray? = when {
        encoder == null -> throw X265Exception("encoder is closed")
        code == X265_OK -> memScoped {
            val size = alloc<IntVar>()
            val blob = xky_output_blob(encoder, size.ptr)
                ?: throw X265Exception("x265 produced output for $what but no buffer")
            if (size.value <= 0) {
                throw X265Exception("x265 produced an empty output buffer for $what")
            }
            blob.readBytes(size.value)
        }
        code == X265_BUFFERED -> null
        else -> throw X265Exception("x265 failed during $what (code $code)")
    }
}
