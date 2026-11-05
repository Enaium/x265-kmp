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
 * JavaScript glue that owns the WebAssembly instance.
 *
 * The x265 module is a standalone WASI reactor: it imports only a handful of
 * WASI calls (time and stdio, used by x265's logger) plus the Emscripten
 * memory-growth notification. The glue provides those, exposes one function per
 * C entry point on `globalThis.__x265Web`, and marshals strings through the
 * module's own `xky_alloc`.
 *
 * Bulk data (the input picture and the result blob) crosses the boundary as
 * base64 strings because Kotlin/JS and Kotlin/Wasm cannot share typed arrays
 * with a module compiled outside their own toolchain.
 */
internal const val X265_WEB_INIT_JS: String = """
(base64) => {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  let wasmExports = null;
  const memory = () => new Uint8Array(wasmExports.memory.buffer);
  const dataView = () => new DataView(wasmExports.memory.buffer);
  const decoder = new TextDecoder();

  const encodeBase64 = (data) => {
    let text = '';
    const chunk = 0x8000;
    for (let i = 0; i < data.length; i += chunk) {
      text += String.fromCharCode.apply(null, data.subarray(i, i + chunk));
    }
    return btoa(text);
  };

  const decodeBase64 = (text) => {
    const raw = atob(text);
    const out = new Uint8Array(raw.length);
    for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
    return out;
  };

  const stringToUtf8 = (text) => {
    const out = [];
    for (let i = 0; i < text.length; i++) {
      let code = text.codePointAt(i);
      if (code > 0xffff) i++;
      if (code < 0x80) out.push(code);
      else if (code < 0x800) out.push(0xc0 | (code >> 6), 0x80 | (code & 0x3f));
      else if (code < 0x10000) out.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f));
      else out.push(0xf0 | (code >> 18), 0x80 | ((code >> 12) & 0x3f), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f));
    }
    return new Uint8Array(out);
  };

  const writeString = (text) => {
    const data = stringToUtf8(text);
    const pointer = wasmExports.xky_alloc(data.length + 1);
    if (!pointer) return 0;
    memory().set(data, pointer);
    memory()[pointer + data.length] = 0;
    return pointer;
  };

  const readString = (pointer) => {
    if (!pointer) return '';
    const data = memory();
    let end = pointer;
    while (data[end] !== 0) end++;
    return decoder.decode(data.subarray(pointer, end));
  };

  const writeBytes = (data) => {
    const pointer = wasmExports.xky_alloc(data.length);
    if (!pointer) return 0;
    memory().set(data, pointer);
    return pointer;
  };

  const resultBlob = (encoder) => {
    const sizePointer = wasmExports.xky_alloc(4);
    if (!sizePointer) return '';
    const pointer = wasmExports.xky_output_blob(encoder, sizePointer);
    if (!pointer) {
      wasmExports.xky_free(sizePointer);
      return '';
    }
    const size = dataView().getUint32(sizePointer, true);
    const data = memory().slice(pointer, pointer + size);
    wasmExports.xky_free(sizePointer);
    return encodeBase64(data);
  };

  const resultOf = (encoder, code) => code === 1 ? code + ':' + resultBlob(encoder) : code + ':';

  const imports = {
    env: {
      emscripten_notify_memory_growth() {},
      __syscall_unlinkat() { return 0; },
      __syscall_renameat() { return 0; },
    },
    wasi_snapshot_preview1: {
      fd_write(fd, iovs, iovsLength, written) {
        let total = 0;
        for (let i = 0; i < iovsLength; i++) {
          const pointer = dataView().getUint32(iovs + i * 8, true);
          const length = dataView().getUint32(iovs + i * 8 + 4, true);
          const text = decoder.decode(memory().subarray(pointer, pointer + length));
          if (text.trim().length > 0) {
            if (fd === 2) console.error(text.trimEnd());
            else console.log(text.trimEnd());
          }
          total += length;
        }
        dataView().setUint32(written, total, true);
        return 0;
      },
      fd_read() { return 0; },
      fd_seek() { return 0; },
      fd_close() { return 0; },
      clock_time_get(clock, precision, out) {
        const now = typeof performance !== 'undefined' ? performance.now() : Date.now();
        dataView().setBigUint64(out, BigInt(Math.floor(now * 1e6)), true);
        return 0;
      },
      environ_sizes_get(count, size) {
        dataView().setUint32(count, 0, true);
        dataView().setUint32(size, 0, true);
        return 0;
      },
      environ_get() { return 0; },
    },
  };

  const instance = new WebAssembly.Instance(new WebAssembly.Module(bytes), imports);
  wasmExports = instance.exports;
  if (wasmExports._initialize) wasmExports._initialize();

  globalThis.__x265Web = {
    versionPacked() {
      return wasmExports.xky_version_packed();
    },
    versionString() {
      return readString(wasmExports.xky_version_string());
    },
    apiBuild() {
      return wasmExports.xky_api_build();
    },
    paramsCreate(width, height, csp) {
      return wasmExports.xky_params_create(width, height, csp);
    },
    paramsFree(pointer) {
      wasmExports.xky_params_free(pointer);
    },
    paramsConfigure(pointer, operation, name, value) {
      const namePointer = writeString(name);
      const valuePointer = value ? writeString(value) : 0;
      let code;
      if (operation === 0) code = wasmExports.xky_params_parse(pointer, namePointer, valuePointer);
      else if (operation === 1) code = wasmExports.xky_params_apply_preset(pointer, namePointer);
      else if (operation === 2) code = wasmExports.xky_params_apply_tune(pointer, namePointer);
      else code = wasmExports.xky_params_apply_profile(pointer, namePointer);
      wasmExports.xky_free(namePointer);
      if (valuePointer) wasmExports.xky_free(valuePointer);
      return code;
    },
    paramsValidate(pointer) {
      return wasmExports.xky_params_validate(pointer);
    },
    paramsGetInt(pointer, field) {
      return wasmExports.xky_params_get_int(pointer, field);
    },
    encoderOpen(pointer) {
      return wasmExports.xky_encoder_open(pointer);
    },
    encoderClose(pointer) {
      wasmExports.xky_encoder_close(pointer);
    },
    encoderParameters(encoder, pointer) {
      return wasmExports.xky_encoder_parameters(encoder, pointer);
    },
    encoderHeaders(encoder) {
      return resultOf(encoder, wasmExports.xky_encoder_headers(encoder));
    },
    encode(encoder, payloadBase64) {
      const data = decodeBase64(payloadBase64);
      const pointer = writeBytes(data);
      if (!pointer) return '-2:';
      const code = wasmExports.xky_encode_packed(encoder, pointer, data.length);
      wasmExports.xky_free(pointer);
      return resultOf(encoder, code);
    },
    flush(encoder) {
      return resultOf(encoder, wasmExports.xky_flush(encoder));
    },
  };
}
"""
