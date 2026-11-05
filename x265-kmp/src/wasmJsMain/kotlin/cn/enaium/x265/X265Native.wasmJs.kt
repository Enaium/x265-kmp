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

@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package cn.enaium.x265

import kotlin.JsFun

private var x265WebInitialized = false

internal actual fun webInit() {
    if (!x265WebInitialized) {
        initX265Web(X265_WASM_BASE64)
        x265WebInitialized = true
    }
}

@JsFun(X265_WEB_INIT_JS)
private external fun initX265Web(wasmBase64: String)

@JsFun("() => globalThis.__x265Web.versionPacked()")
private external fun versionPacked(): Int

@JsFun("() => globalThis.__x265Web.versionString()")
private external fun versionString(): String

@JsFun("() => globalThis.__x265Web.apiBuild()")
private external fun apiBuild(): Int

@JsFun("(w, h, c) => globalThis.__x265Web.paramsCreate(w, h, c)")
private external fun paramsCreate(width: Int, height: Int, csp: Int): Int

@JsFun("(p) => globalThis.__x265Web.paramsFree(p)")
private external fun paramsFree(pointer: Int)

@JsFun("(p, op, n, v) => globalThis.__x265Web.paramsConfigure(p, op, n, v)")
private external fun paramsConfigure(pointer: Int, operation: Int, name: String, value: String): Int

@JsFun("(p) => globalThis.__x265Web.paramsValidate(p)")
private external fun paramsValidate(pointer: Int): Int

@JsFun("(p, f) => globalThis.__x265Web.paramsGetInt(p, f)")
private external fun paramsGetInt(pointer: Int, field: Int): Int

@JsFun("(p) => globalThis.__x265Web.encoderOpen(p)")
private external fun encoderOpen(pointer: Int): Int

@JsFun("(e) => globalThis.__x265Web.encoderClose(e)")
private external fun encoderClose(pointer: Int)

@JsFun("(e, p) => globalThis.__x265Web.encoderParameters(e, p)")
private external fun encoderParameters(encoder: Int, pointer: Int): Int

@JsFun("(e) => globalThis.__x265Web.encoderHeaders(e)")
private external fun encoderHeaders(encoder: Int): String

@JsFun("(e, p) => globalThis.__x265Web.encode(e, p)")
private external fun encode(encoder: Int, payload: String): String

@JsFun("(e) => globalThis.__x265Web.flush(e)")
private external fun flush(encoder: Int): String

internal actual fun webVersionPacked(): Int = versionPacked()

internal actual fun webVersionString(): String = versionString()

internal actual fun webApiBuild(): Int = apiBuild()

internal actual fun webParamsCreate(width: Int, height: Int, csp: Int): Int =
    paramsCreate(width, height, csp)

internal actual fun webParamsFree(handle: Int) = paramsFree(handle)

internal actual fun webParamsConfigure(handle: Int, operation: Int, name: String, value: String): Int =
    paramsConfigure(handle, operation, name, value)

internal actual fun webParamsValidate(handle: Int): Int = paramsValidate(handle)

internal actual fun webParamsGetInt(handle: Int, field: Int): Int = paramsGetInt(handle, field)

internal actual fun webEncoderOpen(params: Int): Int = encoderOpen(params)

internal actual fun webEncoderClose(encoder: Int) = encoderClose(encoder)

internal actual fun webEncoderParameters(encoder: Int, params: Int): Int =
    encoderParameters(encoder, params)

internal actual fun webEncoderHeaders(encoder: Int): String = encoderHeaders(encoder)

internal actual fun webEncode(encoder: Int, payload: String): String = encode(encoder, payload)

internal actual fun webFlush(encoder: Int): String = flush(encoder)
