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
 * Chroma subsampling of the encoder input, mirroring x265's `X265_CSP_*`.
 *
 * x265 down-converts nothing: the picture handed to [X265Encoder.encode] must
 * already match the colour space the encoder was opened with.
 */
enum class X265ChromaFormat(val value: Int) {
    /** Luma only. */
    I400(0),

    /** 4:2:0, the camera and consumer video default. */
    I420(1),

    /** 4:2:2. */
    I422(2),

    /** 4:4:4. */
    I444(3),
    ;

    internal fun chromaWidth(width: Int): Int =
        if (this == I444 || this == I400) width else (width + 1) / 2

    internal fun chromaHeight(height: Int): Int = when (this) {
        I400 -> 0
        I422 -> height
        else -> (height + 1) / 2
    }

    internal companion object {
        fun fromValue(value: Int): X265ChromaFormat =
            entries.firstOrNull { it.value == value } ?: error("Unknown x265 colour space $value")
    }
}

/**
 * Speed/efficiency preset, mirroring `x265_param_default_preset()`.
 *
 * [ULTRAFAST] is the real-time camera setting; [MEDIUM] is x265's default.
 */
enum class X265Preset(val value: String) {
    ULTRAFAST("ultrafast"),
    SUPERFAST("superfast"),
    VERYFAST("veryfast"),
    FASTER("faster"),
    FAST("fast"),
    MEDIUM("medium"),
    SLOW("slow"),
    SLOWER("slower"),
    VERYSLOW("veryslow"),
    PLACEBO("placebo"),
}

/** Tune, mirroring `x265_param_default_preset()`'s tune argument. */
enum class X265Tune(val value: String) {
    PSNR("psnr"),
    SSIM("ssim"),
    FAST_DECODE("fastdecode"),
    ZERO_LATENCY("zerolatency"),
    GRAIN("grain"),
    ANIMATION("animation"),
    VMAF("vmaf"),
}

/** 8-bit profile accepted by this build, mirroring `x265_param_apply_profile()`. */
enum class X265Profile(val value: String) {
    MAIN("main"),
    MAIN_STILL_PICTURE("mainstillpicture"),
    MAIN_INTRA("main-intra"),
    MAIN_444("main444-8"),
    MAIN_444_INTRA("main444-intra"),
    MAIN_444_STILL_PICTURE("main444-stillpicture"),
}

/** x265 log verbosity, mirroring `X265_LOG_*`. */
enum class X265LogLevel(val value: Int) {
    NONE(-1),
    ERROR(0),
    WARNING(1),
    INFO(2),
    DEBUG(3),
    FULL(4),
}

/** HEVC NAL unit type values reported by x265, mirroring `NalUnitType`. */
enum class X265NalType(val value: Int) {
    TRAIL_N(0),
    TRAIL_R(1),
    TSA_N(2),
    TSA_R(3),
    STSA_N(4),
    STSA_R(5),
    RADL_N(6),
    RADL_R(7),
    RASL_N(8),
    RASL_R(9),
    BLA_W_LP(16),
    BLA_W_RADL(17),
    BLA_N_LP(18),
    IDR_W_RADL(19),
    IDR_N_LP(20),
    CRA(21),
    VPS(32),
    SPS(33),
    PPS(34),
    AUD(35),
    EOS(36),
    EOB(37),
    FD(38),
    PREFIX_SEI(39),
    SUFFIX_SEI(40),
    UNSPECIFIED(62),
    ;

    /** Random-access picture (BLA, IDR or CRA). */
    val isIrap: Boolean get() = value in 16..21

    /** Coded slice NAL unit. */
    val isSlice: Boolean get() = value in 0..9 || isIrap

    val isParameterSet: Boolean get() = this == VPS || this == SPS || this == PPS

    internal companion object {
        fun fromValue(value: Int): X265NalType? = entries.firstOrNull { it.value == value }
    }
}

/** Encoded picture type, mirroring `X265_TYPE_*`. */
enum class X265SliceType(val value: Int) {
    AUTO(0),
    IDR(1),
    I(2),
    P(3),
    BREF(4),
    B(5),
    ;

    val isIntra: Boolean get() = this == IDR || this == I

    internal companion object {
        fun fromValue(value: Int): X265SliceType =
            entries.firstOrNull { it.value == value } ?: AUTO
    }
}

/** Readable parameter fields, mirroring `XKY_FIELD_*` from the C shim. */
internal enum class X265Field(val value: Int) {
    WIDTH(1),
    HEIGHT(2),
    CHROMA_FORMAT(3),
    BIT_DEPTH(4),
    FPS_NUM(5),
    FPS_DEN(6),
    FRAME_THREADS(7),
    LOG_LEVEL(8),
    REPEAT_HEADERS(10),
    KEYFRAME_MAX(11),
    MAX_CU_SIZE(12),
}
