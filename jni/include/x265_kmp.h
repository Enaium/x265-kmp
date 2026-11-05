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

#ifndef X265_KMP_H_
#define X265_KMP_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * One C ABI shared by the JNI bridge, the Kotlin/Native cinterop and the
 * WebAssembly build. x265 itself exposes a C API, but its public names are
 * preprocessor-mangled with X265_BUILD, the encoder is stateful and - most
 * importantly for the web target - every result must be copied out before the
 * next x265 call. This shim hides those details behind handles and one packed
 * result blob so that all three bridges execute identical argument handling.
 */

/* ------------------------------------------------------------------------- */
/* Version                                                                   */
/* ------------------------------------------------------------------------- */

/* x265 API build number (SONAME), e.g. 216. */
int xky_api_build(void);

/* x265 API major version, e.g. 4. */
int xky_api_major(void);

/* Encoder version encoded as major * 10000 + minor * 100 + patch, e.g. 40200. */
int xky_version_packed(void);

/* "4.2" - the version string of the compiled library. */
const char* xky_version_string(void);

/* ------------------------------------------------------------------------- */
/* Enumerations mirroring x265                                               */
/* ------------------------------------------------------------------------- */

/* Chroma subsampling, mirrors X265_CSP_*. */
enum xky_csp {
  XKY_CSP_I400 = 0,
  XKY_CSP_I420 = 1,
  XKY_CSP_I422 = 2,
  XKY_CSP_I444 = 3,
};

/* Picture type, mirrors X265_TYPE_*. */
enum xky_slice_type {
  XKY_TYPE_AUTO = 0,
  XKY_TYPE_IDR = 1,
  XKY_TYPE_I = 2,
  XKY_TYPE_P = 3,
  XKY_TYPE_BREF = 4,
  XKY_TYPE_B = 5,
};

/* NAL unit types reported by the encoder, mirrors NalUnitType. */
enum xky_nal_type {
  XKY_NAL_TRAIL_N = 0,
  XKY_NAL_TRAIL_R = 1,
  XKY_NAL_BLA_W_LP = 16,
  XKY_NAL_BLA_W_RADL = 17,
  XKY_NAL_BLA_N_LP = 18,
  XKY_NAL_IDR_W_RADL = 19,
  XKY_NAL_IDR_N_LP = 20,
  XKY_NAL_CRA = 21,
  XKY_NAL_VPS = 32,
  XKY_NAL_SPS = 33,
  XKY_NAL_PPS = 34,
  XKY_NAL_AUD = 35,
  XKY_NAL_EOS = 36,
  XKY_NAL_EOB = 37,
  XKY_NAL_FD = 38,
  XKY_NAL_PREFIX_SEI = 39,
  XKY_NAL_SUFFIX_SEI = 40,
  XKY_NAL_UNSPECIFIED = 62,
};

/* Readable parameter fields, see xky_params_get_int(). */
enum xky_field {
  XKY_FIELD_WIDTH = 1,
  XKY_FIELD_HEIGHT = 2,
  XKY_FIELD_CSP = 3,
  XKY_FIELD_BIT_DEPTH = 4,
  XKY_FIELD_FPS_NUM = 5,
  XKY_FIELD_FPS_DEN = 6,
  XKY_FIELD_FRAME_THREADS = 7,
  XKY_FIELD_LOG_LEVEL = 8,
  XKY_FIELD_ANNEX_B = 9,
  XKY_FIELD_REPEAT_HEADERS = 10,
  XKY_FIELD_KEYFRAME_MAX = 11,
  XKY_FIELD_MAX_CU_SIZE = 12,
};

/* Return codes shared by the encode functions. */
#define XKY_OK 1           /* A picture was produced. */
#define XKY_BUFFERED 0     /* Accepted, but no output yet. */
#define XKY_ERROR (-1)     /* x265 rejected the call. */
#define XKY_BAD_ARGUMENT (-2)

/* Packed input passed to xky_encode_packed(): little-endian header followed by
 * the three plane regions.
 *
 *   int32 magic        XKY_INPUT_MAGIC
 *   int32 width
 *   int32 height
 *   int32 csp
 *   int32 stride_y     row stride in bytes
 *   int32 stride_u
 *   int32 stride_v
 *   int32 reserved     must be 0
 *   int64 pts
 *   int32 offsets[3]   plane offsets relative to the packet start
 *   int32 reserved2
 *
 * Unused chroma planes (I400) have a zero length, so the offsets may repeat.
 */
#define XKY_INPUT_MAGIC 0x50594E49u /* "INYP" */
#define XKY_INPUT_HEADER_SIZE 56

/* Packed result returned by xky_output_blob(): little-endian header followed by
 * the NAL payloads. NAL payloads carry the NAL header but no start code.
 *
 *   int32 magic        XKY_OUTPUT_MAGIC
 *   int32 version      XKY_OUTPUT_VERSION
 *   int32 nal_count
 *   int32 slice_type   X265_TYPE_* of the returned picture
 *   int64 pts
 *   int64 dts
 *   int32 payload_size total bytes of all NAL payloads
 *   int32 reserved
 *   int32 types[nal_count]
 *   int32 offsets[nal_count]  relative to the payload base
 *   int32 sizes[nal_count]
 *   ... 8-byte aligned, then the payloads
 *
 * The fixed header is 40 bytes; the payload base is (40 + 12 * nal_count)
 * rounded up to 8. Every field is native-endian; all supported targets are
 * little-endian.
 */
#define XKY_OUTPUT_MAGIC 0x58594F50u /* "POYX" */
#define XKY_OUTPUT_VERSION 1u

typedef struct xky_params xky_params;
typedef struct xky_encoder xky_encoder;

/* ------------------------------------------------------------------------- */
/* Parameters                                                                */
/* ------------------------------------------------------------------------- */

/* Allocates a parameter block for a source of the given size and returns it
 * initialised with x265's defaults plus the "medium" preset. Returns NULL on
 * allocation failure. */
xky_params* xky_params_create(int width, int height, int csp);

/* Releases a parameter block. Encoders keep their own copy. */
void xky_params_free(xky_params* params);

/* x265_param_parse(): name is a CLI option ("crf", "preset", "bframes", ...)
 * and value its textual value. Returns 0 on success. */
int xky_params_parse(xky_params* params, const char* name, const char* value);

/* x265_param_default_preset(): applies an ultrafast..placebo preset. */
int xky_params_apply_preset(xky_params* params, const char* preset);

/* x265_param_default_preset() with a tune ("zerolatency", "psnr", ...). */
int xky_params_apply_tune(xky_params* params, const char* tune);

/* x265_param_apply_profile() ("main", "mainstillpicture", ...). */
int xky_params_apply_profile(xky_params* params, const char* profile);

/* x265_param_parse() for every field x265 validates before an encoder open.
 * Returns 0 when the parameter block is usable. */
int xky_params_validate(xky_params* params);

/* Reads one XKY_FIELD_* value, or INT32_MIN when the field is unknown. */
int xky_params_get_int(const xky_params* params, int field);

/* Encoder open copies the parameters. This overwrites [params] with the values
 * the encoder actually runs with (frame threads, internal depth, ...). */
int xky_encoder_parameters(const xky_encoder* encoder, xky_params* params);

/* ------------------------------------------------------------------------- */
/* Encoder                                                                   */
/* ------------------------------------------------------------------------- */

/* Opens an encoder. Returns NULL when x265 rejects the parameters. The params
 * may be freed as soon as this returns. */
xky_encoder* xky_encoder_open(const xky_params* params);

/* Stops and releases an encoder. Safe with NULL. Delayed pictures are dropped,
 * so flush before closing when every frame is required. */
void xky_encoder_close(xky_encoder* encoder);

/* Requests the stream headers (VPS/SPS/PPS) explicitly. The result is read with
 * the xky_output_* accessors, like an encode result. Returns XKY_OK when a
 * header picture was produced. */
int xky_encoder_headers(xky_encoder* encoder);

/* Encodes one picture from planar 8-bit input. u/v may be NULL for I400.
 * Starts from y/xky_encode_planes() and are aliases of each other.
 *
 * Returns XKY_OK when a picture was produced, XKY_BUFFERED when x265 buffered
 * the input (frame threading / B-frames), or XKY_ERROR. */
int xky_encode_planes(xky_encoder* encoder,
                      const uint8_t* y,
                      const uint8_t* u,
                      const uint8_t* v,
                      int stride_y,
                      int stride_u,
                      int stride_v,
                      int width,
                      int height,
                      int csp,
                      int64_t pts);

/* WebAssembly entry point: the same encode with the picture packed into one
 * XKY_INPUT_HEADER_SIZE-prefixed buffer. */
int xky_encode_packed(xky_encoder* encoder, uint8_t* data, int size);

/* Drains one delayed picture. Call until it returns XKY_BUFFERED. */
int xky_flush(xky_encoder* encoder);

/* ------------------------------------------------------------------------- */
/* Result of the last call                                                   */
/* ------------------------------------------------------------------------- */

/* Number of NAL units in the last result. */
int xky_output_nal_count(const xky_encoder* encoder);

/* NAL type of [index], see XKY_NAL_*. */
int xky_output_nal_type(const xky_encoder* encoder, int index);

/* Payload of [index] (two-byte NAL header included, no start code and no
 * length prefix; x265's internal framing is stripped), or NULL. */
const uint8_t* xky_output_nal_data(const xky_encoder* encoder, int index, size_t* size);

/* Picture type of the last result, see XKY_TYPE_*. */
int xky_output_slice_type(const xky_encoder* encoder);

/* Presentation / decoding timestamps of the last result. */
int64_t xky_output_pts(const xky_encoder* encoder);
int64_t xky_output_dts(const xky_encoder* encoder);

/* The whole result in the XKY_OUTPUT_MAGIC format. The pointer stays valid
 * until the next call on the same encoder. Returns NULL when the last call did
 * not produce a picture. */
const uint8_t* xky_output_blob(const xky_encoder* encoder, int* size);

/* ------------------------------------------------------------------------- */
/* Memory                                                                    */
/* ------------------------------------------------------------------------- */

/* Allocation helpers used by the WebAssembly bridge. */
void* xky_alloc(size_t size);
void xky_free(void* ptr);

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* X265_KMP_H_ */
