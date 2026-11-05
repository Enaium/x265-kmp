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

#include "x265_kmp.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <new>
#include <vector>

#include "x265.h"


/* x265's public header exposes neither a parameter deep-copy helper nor a
 * "describe this parameter block" call. The encoder keeps its own copy, so the
 * only place the helper is needed is xky_encoder_open(); it is declared here in
 * the namespace the library is built with (CMakeLists.txt forces
 * EXPORT_C_API=ON, hence X265_NS == x265). */
namespace x265 {
void x265_copy_params(x265_param* dst, x265_param* src);
}  // namespace x265

namespace {

/* Header layout shared with the Kotlin parser; see x265_kmp.h. */
const size_t kOutputHeaderBase = 40;
const size_t kOutputPerNal = 12;

size_t outputPayloadBase(int nalCount) {
  return (kOutputHeaderBase + kOutputPerNal * static_cast<size_t>(nalCount) + 7u) & ~static_cast<size_t>(7u);
}

void writeU32(uint8_t* target, size_t offset, uint32_t value) {
  memcpy(target + offset, &value, sizeof(value));
}

void writeI32(uint8_t* target, size_t offset, int32_t value) {
  memcpy(target + offset, &value, sizeof(value));
}

void writeI64(uint8_t* target, size_t offset, int64_t value) {
  memcpy(target + offset, &value, sizeof(value));
}

uint32_t readU32(const uint8_t* source, size_t offset) {
  uint32_t value;
  memcpy(&value, source + offset, sizeof(value));
  return value;
}

int32_t readI32(const uint8_t* source, size_t offset) {
  int32_t value;
  memcpy(&value, source + offset, sizeof(value));
  return value;
}

int64_t readI64(const uint8_t* source, size_t offset) {
  int64_t value;
  memcpy(&value, source + offset, sizeof(value));
  return value;
}

bool isKnownCsp(int csp) {
  return csp >= XKY_CSP_I400 && csp <= XKY_CSP_I444;
}

int chromaWidth(int width, int csp) {
  return (csp == XKY_CSP_I444) ? width : ((width + 1) / 2);
}

}  // namespace

/* ------------------------------------------------------------------------- */
/* Version                                                                   */
/* ------------------------------------------------------------------------- */

extern "C" int xky_api_build(void) { return X265_BUILD; }

extern "C" int xky_api_major(void) { return X265_MAJOR_VERSION; }

extern "C" int xky_version_packed(void) {
  int major = 0;
  int minor = 0;
  int patch = 0;
  const char* version = x265_version_str;
  if (version) {
    /* The library reports "4.2"; future releases may add a third component. */
    sscanf(version, "%d.%d.%d", &major, &minor, &patch);
  }
  return major * 10000 + minor * 100 + patch;
}

extern "C" const char* xky_version_string(void) { return x265_version_str; }

/* ------------------------------------------------------------------------- */
/* Parameters                                                                */
/* ------------------------------------------------------------------------- */

struct xky_params {
  x265_param* param;
  /* The strings have to survive later preset/tune calls: x265's
   * x265_param_default_preset() resets the block, so both names must be
   * re-applied together to keep them composable. */
  char preset[32];
  char tune[32];
};

namespace {

x265_param* allocParam() { return x265_param_alloc(); }

void copyString(char* target, size_t size, const char* source) {
  if (!source) {
    target[0] = '\0';
    return;
  }
  snprintf(target, size, "%s", source);
}

/* Applies the tracked preset and tune, then restores the source geometry that
 * belongs to the caller rather than to a preset. */
int applyPresetAndTune(xky_params* params, const char* preset, const char* tune) {
  const int width = params->param->sourceWidth;
  const int height = params->param->sourceHeight;
  const int csp = params->param->internalCsp;
  const int fpsNum = params->param->fpsNum;
  const int fpsDenom = params->param->fpsDenom;
  const int sourceBitDepth = params->param->sourceBitDepth;

  const char* effectivePreset = preset ? preset : params->preset;
  const char* effectiveTune = tune ? tune : params->tune;
  if (effectivePreset[0] == '\0') {
    effectivePreset = "medium";
  }

  const int result = x265_param_default_preset(
      params->param,
      effectivePreset,
      effectiveTune[0] == '\0' ? NULL : effectiveTune);
  if (result < 0) {
    return result;
  }

  copyString(params->preset, sizeof(params->preset), effectivePreset);
  copyString(params->tune, sizeof(params->tune), effectiveTune);

  params->param->sourceWidth = width;
  params->param->sourceHeight = height;
  params->param->internalCsp = csp;
  params->param->fpsNum = fpsNum;
  params->param->fpsDenom = fpsDenom;
  params->param->sourceBitDepth = sourceBitDepth;
  return 0;
}

}  // namespace

extern "C" xky_params* xky_params_create(int width, int height, int csp) {
  if (width <= 0 || height <= 0 || !isKnownCsp(csp)) {
    return NULL;
  }
  xky_params* params = new (std::nothrow) xky_params();
  if (!params) {
    return NULL;
  }
  params->param = allocParam();
  if (!params->param) {
    delete params;
    return NULL;
  }
  params->preset[0] = '\0';
  params->tune[0] = '\0';

  x265_param_default(params->param);
  params->param->sourceWidth = width;
  params->param->sourceHeight = height;
  params->param->internalCsp = csp;
  /* x265 refuses to open without an explicit frame rate; 30 fps is the least
   * surprising default for a camera/streaming binding. */
  params->param->fpsNum = 30;
  params->param->fpsDenom = 1;
  if (applyPresetAndTune(params, "medium", NULL) < 0) {
    x265_param_free(params->param);
    delete params;
    return NULL;
  }
  return params;
}

extern "C" void xky_params_free(xky_params* params) {
  if (!params) {
    return;
  }
  if (params->param) {
    x265_param_free(params->param);
  }
  delete params;
}

extern "C" int xky_params_parse(xky_params* params, const char* name, const char* value) {
  if (!params || !name) {
    return XKY_BAD_ARGUMENT;
  }
  return x265_param_parse(params->param, name, value);
}

extern "C" int xky_params_apply_preset(xky_params* params, const char* preset) {
  if (!params || !preset) {
    return XKY_BAD_ARGUMENT;
  }
  return applyPresetAndTune(params, preset, NULL);
}

extern "C" int xky_params_apply_tune(xky_params* params, const char* tune) {
  if (!params || !tune) {
    return XKY_BAD_ARGUMENT;
  }
  return applyPresetAndTune(params, NULL, tune);
}

extern "C" int xky_params_apply_profile(xky_params* params, const char* profile) {
  if (!params) {
    return XKY_BAD_ARGUMENT;
  }
  return x265_param_apply_profile(params->param, profile);
}

extern "C" int xky_params_validate(xky_params* params) {
  if (!params) {
    return XKY_BAD_ARGUMENT;
  }
  const x265_param* p = params->param;
  if (p->sourceWidth <= 0 || p->sourceHeight <= 0 || !isKnownCsp(p->internalCsp)) {
    return -1;
  }
  if (p->fpsNum <= 0 || p->fpsDenom <= 0) {
    return -1;
  }
  if (p->internalCsp == XKY_CSP_I420 && (p->sourceWidth % 2 != 0 || p->sourceHeight % 2 != 0)) {
    return -1;
  }
  if (p->internalCsp == XKY_CSP_I422 && p->sourceWidth % 2 != 0) {
    return -1;
  }
  return 0;
}

extern "C" int xky_params_get_int(const xky_params* params, int field) {
  if (!params) {
    return INT32_MIN;
  }
  const x265_param* p = params->param;
  switch (field) {
    case XKY_FIELD_WIDTH:
      return p->sourceWidth;
    case XKY_FIELD_HEIGHT:
      return p->sourceHeight;
    case XKY_FIELD_CSP:
      return p->internalCsp;
    case XKY_FIELD_BIT_DEPTH:
      return p->internalBitDepth;
    case XKY_FIELD_FPS_NUM:
      return p->fpsNum;
    case XKY_FIELD_FPS_DEN:
      return p->fpsDenom;
    case XKY_FIELD_FRAME_THREADS:
      return p->frameNumThreads;
    case XKY_FIELD_LOG_LEVEL:
      return p->logLevel;
    case XKY_FIELD_ANNEX_B:
      return p->bAnnexB;
    case XKY_FIELD_REPEAT_HEADERS:
      return p->bRepeatHeaders;
    case XKY_FIELD_KEYFRAME_MAX:
      return p->keyframeMax;
    case XKY_FIELD_MAX_CU_SIZE:
      return p->maxCUSize;
    default:
      return INT32_MIN;
  }
}

/* ------------------------------------------------------------------------- */
/* Encoder                                                                   */
/* ------------------------------------------------------------------------- */

struct xky_encoder {
  x265_encoder* encoder;
  xky_params* params; /* owned copy of the effective parameters */
  x265_picture picture;
  int width;
  int height;
  int csp;

  /* Result of the most recent successful call. */
  std::vector<uint8_t> blob;
  int nalCount;
  int sliceType;
  int64_t pts;
  int64_t dts;
};

namespace {

void clearOutput(xky_encoder* handle) {
  handle->blob.clear();
  handle->nalCount = 0;
  handle->sliceType = XKY_TYPE_AUTO;
  handle->pts = 0;
  handle->dts = 0;
}

/* x265 prefixes every NAL with a 4-byte big-endian length when bAnnexB is
 * disabled. Returns how many leading bytes belong to that framing. */
size_t nalFramingSize(const x265_nal& nal) {
  if (nal.sizeBytes < 4) {
    return 0;
  }
  const uint8_t* payload = nal.payload;
  const uint32_t bodySize =
      (static_cast<uint32_t>(payload[0]) << 24) | (static_cast<uint32_t>(payload[1]) << 16) |
      (static_cast<uint32_t>(payload[2]) << 8) | static_cast<uint32_t>(payload[3]);
  return bodySize == nal.sizeBytes - 4 ? 4u : 0u;
}

/* Copies the NALs x265 produced into the packed result buffer. x265 reuses the
 * NAL payloads on the next call, so this copy is mandatory. The 4-byte length
 * prefix (or a start code, if a caller forced bAnnexB back on) is stripped so
 * xky_output_nal_data() always exposes a raw NAL unit. */
void captureResult(xky_encoder* handle, const x265_nal* nals, uint32_t nalCount, int64_t pts, int64_t dts, int sliceType) {
  clearOutput(handle);
  if (nalCount == 0) {
    return;
  }

  size_t payloadSize = 0;
  for (uint32_t index = 0; index < nalCount; index++) {
    payloadSize += nals[index].sizeBytes - nalFramingSize(nals[index]);
  }
  const size_t base = outputPayloadBase(static_cast<int>(nalCount));
  handle->blob.resize(base + payloadSize);

  uint8_t* data = handle->blob.data();
  writeU32(data, 0, XKY_OUTPUT_MAGIC);
  writeU32(data, 4, XKY_OUTPUT_VERSION);
  writeI32(data, 8, static_cast<int32_t>(nalCount));
  writeI32(data, 12, sliceType);
  writeI64(data, 16, pts);
  writeI64(data, 24, dts);
  writeI32(data, 32, static_cast<int32_t>(payloadSize));
  writeI32(data, 36, 0);

  size_t offset = 0;
  for (uint32_t index = 0; index < nalCount; index++) {
    const size_t typesOffset = kOutputHeaderBase + index * 4;
    const size_t offsetsOffset = kOutputHeaderBase + nalCount * 4 + index * 4;
    const size_t sizesOffset = kOutputHeaderBase + nalCount * 8 + index * 4;
    const size_t framing = nalFramingSize(nals[index]);
    const size_t size = nals[index].sizeBytes - framing;
    writeI32(data, typesOffset, static_cast<int32_t>(nals[index].type));
    writeI32(data, offsetsOffset, static_cast<int32_t>(offset));
    writeI32(data, sizesOffset, static_cast<int32_t>(size));
    memcpy(data + base + offset, nals[index].payload + framing, size);
    offset += size;
  }

  handle->nalCount = static_cast<int>(nalCount);
  handle->sliceType = sliceType;
  handle->pts = pts;
  handle->dts = dts;
}

int encodePicture(xky_encoder* handle, x265_picture* input) {
  x265_nal* nals = NULL;
  uint32_t nalCount = 0;
  x265_picture output;
  memset(&output, 0, sizeof(output));

  const int encoded = x265_encoder_encode(handle->encoder, &nals, &nalCount, input, &output);
  if (encoded < 0) {
    clearOutput(handle);
    return XKY_ERROR;
  }
  if (encoded == 0 || nalCount == 0) {
    clearOutput(handle);
    return XKY_BUFFERED;
  }
  captureResult(handle, nals, nalCount, output.pts, output.dts, output.sliceType);
  return XKY_OK;
}

}  // namespace

extern "C" xky_encoder* xky_encoder_open(const xky_params* params) {
  if (!params || !params->param) {
    return NULL;
  }
  xky_encoder* handle = new (std::nothrow) xky_encoder();
  if (!handle) {
    return NULL;
  }
  handle->encoder = NULL;
  handle->params = NULL;
  handle->nalCount = 0;
  handle->sliceType = XKY_TYPE_AUTO;
  handle->pts = 0;
  handle->dts = 0;

  /* The encoder keeps its own parameter copy; ours backs xky_encoder_parameters
   * and the input-picture initialisation. */
  handle->params = new (std::nothrow) xky_params();
  if (!handle->params) {
    delete handle;
    return NULL;
  }
  handle->params->param = allocParam();
  handle->params->preset[0] = '\0';
  handle->params->tune[0] = '\0';
  if (!handle->params->param) {
    delete handle->params;
    delete handle;
    return NULL;
  }
  x265::x265_copy_params(handle->params->param, params->param);
  copyString(handle->params->preset, sizeof(handle->params->preset), params->preset);
  copyString(handle->params->tune, sizeof(handle->params->tune), params->tune);

  /* x265's x265_nal payloads carry their own framing: a start code when
   * bAnnexB is set, or a 4-byte length prefix otherwise. The binding exposes
   * raw NAL units and lets the caller add Annex-B start codes, so the
   * length-prefixed form is requested here and stripped on output. */
  x265_param_parse(handle->params->param, "annexb", "0");

#if defined(XKY_SINGLE_THREADED)
  /* Emscripten builds run without pthreads: no worker pool and no WPP. */
  x265_param_parse(handle->params->param, "pools", "none");
  x265_param_parse(handle->params->param, "frame-threads", "1");
  x265_param_parse(handle->params->param, "wpp", "0");
  x265_param_parse(handle->params->param, "pmode", "0");
  x265_param_parse(handle->params->param, "pme", "0");
  x265_param_parse(handle->params->param, "lookahead-slices", "0");
  x265_param_parse(handle->params->param, "threaded-me", "0");
#endif

  handle->width = handle->params->param->sourceWidth;
  handle->height = handle->params->param->sourceHeight;
  handle->csp = handle->params->param->internalCsp;

  handle->encoder = x265_encoder_open(handle->params->param);
  if (!handle->encoder) {
    x265_param_free(handle->params->param);
    delete handle->params;
    delete handle;
    return NULL;
  }

  x265_picture_init(handle->params->param, &handle->picture);
  return handle;
}

extern "C" void xky_encoder_close(xky_encoder* handle) {
  if (!handle) {
    return;
  }
  if (handle->encoder) {
    x265_encoder_close(handle->encoder);
  }
  if (handle->params) {
    x265_param_free(handle->params->param);
    delete handle->params;
  }
  delete handle;
}

extern "C" int xky_encoder_parameters(const xky_encoder* handle, xky_params* params) {
  if (!handle || !params) {
    return XKY_BAD_ARGUMENT;
  }
  x265_encoder_parameters(handle->encoder, params->param);
  copyString(params->preset, sizeof(params->preset), handle->params->preset);
  copyString(params->tune, sizeof(params->tune), handle->params->tune);
  return 0;
}

extern "C" int xky_encoder_headers(xky_encoder* handle) {
  if (!handle) {
    return XKY_BAD_ARGUMENT;
  }
  x265_nal* nals = NULL;
  uint32_t nalCount = 0;
  const int result = x265_encoder_headers(handle->encoder, &nals, &nalCount);
  if (result < 0) {
    clearOutput(handle);
    return XKY_ERROR;
  }
  captureResult(handle, nals, nalCount, 0, 0, XKY_TYPE_AUTO);
  return nalCount > 0 ? XKY_OK : XKY_BUFFERED;
}

extern "C" int xky_encode_planes(xky_encoder* handle,
                                 const uint8_t* y,
                                 const uint8_t* u,
                                 const uint8_t* v,
                                 int stride_y,
                                 int stride_u,
                                 int stride_v,
                                 int width,
                                 int height,
                                 int csp,
                                 int64_t pts) {
  if (!handle) {
    return XKY_BAD_ARGUMENT;
  }
  if (!y) {
    return encodePicture(handle, NULL);
  }
  if (width != handle->width || height != handle->height || csp != handle->csp) {
    return XKY_BAD_ARGUMENT;
  }
  if (!isKnownCsp(csp) || stride_y < width) {
    return XKY_BAD_ARGUMENT;
  }
  if (csp != XKY_CSP_I400) {
    if (!u || !v) {
      return XKY_BAD_ARGUMENT;
    }
    if (stride_u < chromaWidth(width, csp) || stride_v < chromaWidth(width, csp)) {
      return XKY_BAD_ARGUMENT;
    }
  }

  x265_picture* picture = &handle->picture;
  picture->planes[0] = const_cast<uint8_t*>(y);
  picture->planes[1] = const_cast<uint8_t*>(u);
  picture->planes[2] = const_cast<uint8_t*>(v);
  picture->planes[3] = NULL;
  picture->stride[0] = stride_y;
  picture->stride[1] = stride_u;
  picture->stride[2] = stride_v;
  picture->stride[3] = 0;
  picture->pts = pts;
  picture->sliceType = X265_TYPE_AUTO;
  return encodePicture(handle, picture);
}

extern "C" int xky_encode_packed(xky_encoder* handle, uint8_t* data, int size) {
  if (!handle || !data || size < XKY_INPUT_HEADER_SIZE) {
    return XKY_BAD_ARGUMENT;
  }
  if (readU32(data, 0) != XKY_INPUT_MAGIC) {
    return XKY_BAD_ARGUMENT;
  }
  const int width = readI32(data, 4);
  const int height = readI32(data, 8);
  const int csp = readI32(data, 12);
  const int strideY = readI32(data, 16);
  const int strideU = readI32(data, 20);
  const int strideV = readI32(data, 24);
  const int64_t pts = readI64(data, 32);
  const int offsetY = readI32(data, 40);
  const int offsetU = readI32(data, 44);
  const int offsetV = readI32(data, 48);

  auto plane = [data, size](int offset) -> const uint8_t* {
    if (offset <= 0 || offset >= size) {
      return NULL;
    }
    return data + offset;
  };

  return xky_encode_planes(handle,
                           plane(offsetY),
                           plane(offsetU),
                           plane(offsetV),
                           strideY,
                           strideU,
                           strideV,
                           width,
                           height,
                           csp,
                           pts);
}

extern "C" int xky_flush(xky_encoder* handle) {
  if (!handle) {
    return XKY_BAD_ARGUMENT;
  }
  return encodePicture(handle, NULL);
}

/* ------------------------------------------------------------------------- */
/* Result accessors                                                          */
/* ------------------------------------------------------------------------- */

extern "C" int xky_output_nal_count(const xky_encoder* handle) {
  return handle ? handle->nalCount : 0;
}

extern "C" int xky_output_nal_type(const xky_encoder* handle, int index) {
  if (!handle || index < 0 || index >= handle->nalCount) {
    return -1;
  }
  return readI32(handle->blob.data(), kOutputHeaderBase + static_cast<size_t>(index) * 4);
}

extern "C" const uint8_t* xky_output_nal_data(const xky_encoder* handle, int index, size_t* size) {
  if (size) {
    *size = 0;
  }
  if (!handle || index < 0 || index >= handle->nalCount) {
    return NULL;
  }
  const size_t base = outputPayloadBase(handle->nalCount);
  const size_t offsetIndex = kOutputHeaderBase + static_cast<size_t>(handle->nalCount) * 4 + static_cast<size_t>(index) * 4;
  const size_t sizeIndex = kOutputHeaderBase + static_cast<size_t>(handle->nalCount) * 8 + static_cast<size_t>(index) * 4;
  const int offset = readI32(handle->blob.data(), offsetIndex);
  const int length = readI32(handle->blob.data(), sizeIndex);
  if (offset < 0 || length < 0 || base + static_cast<size_t>(offset) + static_cast<size_t>(length) > handle->blob.size()) {
    return NULL;
  }
  if (size) {
    *size = static_cast<size_t>(length);
  }
  return handle->blob.data() + base + static_cast<size_t>(offset);
}

extern "C" int xky_output_slice_type(const xky_encoder* handle) {
  return handle ? handle->sliceType : XKY_TYPE_AUTO;
}

extern "C" int64_t xky_output_pts(const xky_encoder* handle) {
  return handle ? handle->pts : 0;
}

extern "C" int64_t xky_output_dts(const xky_encoder* handle) {
  return handle ? handle->dts : 0;
}

extern "C" const uint8_t* xky_output_blob(const xky_encoder* handle, int* size) {
  if (size) {
    *size = 0;
  }
  if (!handle || handle->nalCount == 0) {
    return NULL;
  }
  if (size) {
    *size = static_cast<int>(handle->blob.size());
  }
  return handle->blob.data();
}

/* ------------------------------------------------------------------------- */
/* Memory                                                                    */
/* ------------------------------------------------------------------------- */

extern "C" void* xky_alloc(size_t size) { return malloc(size); }

extern "C" void xky_free(void* ptr) { free(ptr); }
