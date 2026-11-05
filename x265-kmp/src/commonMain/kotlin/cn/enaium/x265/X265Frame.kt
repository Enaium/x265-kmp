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

/** One NAL unit produced by the encoder. */
class X265Nal(
    /** Raw NAL type, see [X265NalType]. */
    val type: Int,
    /**
     * NAL payload: the two-byte NAL header is included, but neither a start
     * code nor x265's internal length prefix. [X265Frame.toAnnexB] adds the
     * four-byte start codes.
     */
    val data: ByteArray,
) {
    val nalType: X265NalType? get() = X265NalType.fromValue(type)

    /** True for VPS, SPS and PPS. */
    val isParameterSet: Boolean get() = nalType?.isParameterSet == true

    override fun toString(): String = "X265Nal(type=$type, size=${data.size})"
}

/**
 * One encoded picture: every NAL unit x265 emitted for it.
 *
 * The NAL payloads are copies of x265's internal buffers, so the frame stays
 * valid after the next [X265Encoder.encode] call.
 */
class X265Frame(
    val nals: List<X265Nal>,
    val pts: Long,
    val dts: Long,
    val sliceType: X265SliceType,
) {
    /** A picture that starts a new random-access point (IDR, CRA or BLA). */
    val isKeyFrame: Boolean
        get() = sliceType.isIntra || nals.any { it.nalType?.isIrap == true }

    /** Total payload bytes of every NAL in this picture. */
    val byteSize: Int get() = nals.sumOf { it.data.size }

    /**
     * The VPS/SPS/PPS units of this frame as Annex-B bytes, the value
     * MediaCodec expects in `csd-0`. Other NAL units (SEI, slices) are dropped:
     * some decoders refuse a codec-specific-data buffer that contains them.
     */
    fun parameterSets(): ByteArray {
        val selected = nals.filter { it.isParameterSet }
        if (selected.isEmpty()) return ByteArray(0)
        val size = selected.sumOf { it.data.size + ANNEX_B.size }
        val out = ByteArray(size)
        var offset = 0
        for (nal in selected) {
            ANNEX_B.copyInto(out, offset)
            offset += ANNEX_B.size
            nal.data.copyInto(out, offset)
            offset += nal.data.size
        }
        return out
    }

    /**
     * Concatenates all NAL units with four-byte Annex-B start codes, the format
     * MediaCodec, muxers and `ffprobe` expect.
     */
    fun toAnnexB(): ByteArray {
        val size = nals.sumOf { it.data.size + ANNEX_B.size }
        val out = ByteArray(size)
        var offset = 0
        for (nal in nals) {
            ANNEX_B.copyInto(out, offset)
            offset += ANNEX_B.size
            nal.data.copyInto(out, offset)
            offset += nal.data.size
        }
        return out
    }

    override fun toString(): String =
        "X265Frame(pts=$pts, dts=$dts, sliceType=$sliceType, nals=${nals.size}, bytes=$byteSize)"

    private companion object {
        val ANNEX_B = byteArrayOf(0, 0, 0, 1)
    }
}

/**
 * One planar 8-bit input picture.
 *
 * Planes are read through [strideY]/[strideU]/[strideV] and are not copied by
 * the binding; x265 copies them into its own frame buffers during
 * [X265Encoder.encode].
 */
class X265Picture(
    val y: ByteArray,
    val u: ByteArray?,
    val v: ByteArray?,
    val width: Int,
    val height: Int,
    val chromaFormat: X265ChromaFormat,
    val strideY: Int = width,
    val strideU: Int = chromaFormat.chromaWidth(width),
    val strideV: Int = chromaFormat.chromaWidth(width),
    val pts: Long = 0L,
) {
    init {
        require(width > 0 && height > 0) { "picture size must be positive, got ${width}x$height" }
        require(chromaFormat != X265ChromaFormat.I400 || (u == null && v == null)) {
            "I400 pictures have no chroma planes"
        }
        require(strideY >= width) { "luma stride $strideY is smaller than the width $width" }
        require(y.size.toLong() >= strideY.toLong() * height) {
            "luma plane is too small: ${y.size} bytes for ${width}x$height with stride $strideY"
        }
        if (chromaFormat != X265ChromaFormat.I400) {
            require(u != null && v != null) { "$chromaFormat pictures need both chroma planes" }
            val chromaWidth = chromaFormat.chromaWidth(width)
            require(strideU >= chromaWidth) { "chroma stride $strideU is smaller than $chromaWidth" }
            val chromaHeight = chromaFormat.chromaHeight(height)
            val needed = strideU.toLong() * chromaHeight
            require(u.size.toLong() >= needed) { "U plane is too small: ${u.size} bytes, need $needed" }
            require(v.size.toLong() >= needed) { "V plane is too small: ${v.size} bytes, need $needed" }
        }
    }

    companion object {
        /** Tightly packed I420 picture. */
        fun i420(
            y: ByteArray,
            u: ByteArray,
            v: ByteArray,
            width: Int,
            height: Int,
            pts: Long = 0L,
        ): X265Picture = X265Picture(
            y = y,
            u = u,
            v = v,
            width = width,
            height = height,
            chromaFormat = X265ChromaFormat.I420,
            pts = pts,
        )

        /** Tightly packed NV12 picture, split into I420 planes. */
        fun ofNv12(
            nv12: ByteArray,
            width: Int,
            height: Int,
            pts: Long = 0L,
        ): X265Picture {
            val chromaWidth = (width + 1) / 2
            val chromaHeight = (height + 1) / 2
            val lumaSize = width * height
            val chromaSize = chromaWidth * chromaHeight
            require(nv12.size >= lumaSize + chromaSize * 2) {
                "NV12 buffer is too small: ${nv12.size}, need ${lumaSize + chromaSize * 2}"
            }
            val y = nv12.copyOfRange(0, lumaSize)
            val u = ByteArray(chromaSize)
            val v = ByteArray(chromaSize)
            for (index in 0 until chromaSize) {
                u[index] = nv12[lumaSize + index * 2]
                v[index] = nv12[lumaSize + index * 2 + 1]
            }
            return X265Picture(y, u, v, width, height, X265ChromaFormat.I420, pts = pts)
        }
    }
}
