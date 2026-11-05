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

import kotlin.js.js

// The glue is instantiated lazily: decoding and compiling the embedded module
// is the only expensive step, and it should not run for a process that never
// calls x265.
private val x265WebInitialized: Unit by lazy {
    js(X265_WEB_INIT_JS)(X265_WASM_BASE64)
}

internal actual fun webInit() {
    x265WebInitialized
}

internal actual fun webVersionPacked(): Int =
    js("() => globalThis.__x265Web.versionPacked()")() as Int

internal actual fun webVersionString(): String =
    js("() => globalThis.__x265Web.versionString()")() as String

internal actual fun webApiBuild(): Int =
    js("() => globalThis.__x265Web.apiBuild()")() as Int

internal actual fun webParamsCreate(width: Int, height: Int, csp: Int): Int =
    js("(w, h, c) => globalThis.__x265Web.paramsCreate(w, h, c)")(width, height, csp) as Int

internal actual fun webParamsFree(handle: Int) {
    js("(p) => globalThis.__x265Web.paramsFree(p)")(handle)
}

internal actual fun webParamsConfigure(handle: Int, operation: Int, name: String, value: String): Int =
    js("(p, op, n, v) => globalThis.__x265Web.paramsConfigure(p, op, n, v)")(
        handle,
        operation,
        name,
        value,
    ) as Int

internal actual fun webParamsValidate(handle: Int): Int =
    js("(p) => globalThis.__x265Web.paramsValidate(p)")(handle) as Int

internal actual fun webParamsGetInt(handle: Int, field: Int): Int =
    js("(p, f) => globalThis.__x265Web.paramsGetInt(p, f)")(handle, field) as Int

internal actual fun webEncoderOpen(params: Int): Int =
    js("(p) => globalThis.__x265Web.encoderOpen(p)")(params) as Int

internal actual fun webEncoderClose(encoder: Int) {
    js("(e) => globalThis.__x265Web.encoderClose(e)")(encoder)
}

internal actual fun webEncoderParameters(encoder: Int, params: Int): Int =
    js("(e, p) => globalThis.__x265Web.encoderParameters(e, p)")(encoder, params) as Int

internal actual fun webEncoderHeaders(encoder: Int): String =
    js("(e) => globalThis.__x265Web.encoderHeaders(e)")(encoder) as String

internal actual fun webEncode(encoder: Int, payload: String): String =
    js("(e, p) => globalThis.__x265Web.encode(e, p)")(encoder, payload) as String

internal actual fun webFlush(encoder: Int): String =
    js("(e) => globalThis.__x265Web.flush(e)")(encoder) as String
