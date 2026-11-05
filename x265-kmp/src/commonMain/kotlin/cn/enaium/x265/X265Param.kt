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

/** Version information of the bundled x265 build and of this binding. */
object X265 {
    /** Version of the Kotlin bindings; the upstream release plus the binding revision. */
    const val VERSION: String = "4.2.0.1"

    /** The upstream x265 release this binding ships, e.g. `"4.2"`. */
    val libraryVersion: String by lazy { X265Native.versionString() }

    /** Encoder version encoded as `major * 10000 + minor * 100 + patch`. */
    val libraryVersionPacked: Int by lazy { X265Native.versionPacked() }

    /** x265 public API build number (the shared library SONAME). */
    val apiBuild: Int by lazy { X265Native.apiBuild() }

    /** 8-bit build: [X265Param.bitDepth] is always 8. */
    const val BIT_DEPTH: Int = 8

    override fun toString(): String = "x265 $libraryVersion (api $apiBuild), bindings $VERSION"
}

/**
 * An x265 parameter block.
 *
 * Options are set by their CLI names through [set], so every x265 option is
 * reachable without the binding mirroring the (very large) `x265_param` struct.
 * [preset], [tune], [profile] and [frameRate] are typed shortcuts.
 *
 * The block is consulted by [X265Encoder.open]; afterwards the encoder owns a
 * copy and this block may be closed or changed freely.
 *
 * Instances are not thread-safe: configure them on one thread, then hand the
 * immutable encoder to the worker thread.
 */
class X265Param internal constructor(internal val handle: X265ParamsHandle) : AutoCloseable {
    private var closed = false

    val width: Int get() = getInt(X265Field.WIDTH)

    val height: Int get() = getInt(X265Field.HEIGHT)

    val chromaFormat: X265ChromaFormat
        get() = X265ChromaFormat.fromValue(getInt(X265Field.CHROMA_FORMAT))

    /** Internal bit depth of this build; always [X265.BIT_DEPTH]. */
    val bitDepth: Int get() = getInt(X265Field.BIT_DEPTH)

    var frameRateNum: Int
        get() = getInt(X265Field.FPS_NUM)
        set(value) {
            set("fps", "$value/$frameRateDen")
        }

    var frameRateDen: Int
        get() = getInt(X265Field.FPS_DEN)
        set(value) {
            set("fps", "$frameRateNum/$value")
        }

    /** Configured frame threads; 0 means "auto-detect when the encoder opens". */
    val frameThreads: Int get() = getInt(X265Field.FRAME_THREADS)

    var logLevel: X265LogLevel
        get() = X265LogLevel.entries.firstOrNull { it.value == getInt(X265Field.LOG_LEVEL) }
            ?: X265LogLevel.INFO
        set(value) {
            set("log-level", value.value)
        }

    /** Repeat VPS/SPS/PPS before every keyframe, useful for stream joins. */
    var repeatHeaders: Boolean
        get() = getInt(X265Field.REPEAT_HEADERS) != 0
        set(value) {
            set("repeat-headers", value)
        }

    /** Maximum keyframe distance in frames. */
    var keyframeMax: Int
        get() = getInt(X265Field.KEYFRAME_MAX)
        set(value) {
            set("keyint", value)
        }

    /** Sets `name` to an integer value, e.g. `set("crf", 28)`. */
    fun set(name: String, value: Int): X265Param = apply(name, value.toString())

    /** Sets `name` to a boolean value, e.g. `set("repeat-headers", true)`. */
    fun set(name: String, value: Boolean): X265Param = apply(name, if (value) "1" else "0")

    /** Sets `name` to a textual value, e.g. `set("tune", "zerolatency")`. */
    fun set(name: String, value: String): X265Param = apply(name, value)

    /** Applies a speed/efficiency preset. Resets the block to x265's defaults. */
    fun preset(preset: X265Preset): X265Param {
        checkOpen()
        val result = X265Native.paramsPreset(handle, preset.value)
        if (result < 0) {
            throw X265Exception("x265 rejected preset '${preset.value}' (code $result)")
        }
        return this
    }

    /** Applies a tune; keeps the current preset. */
    fun tune(tune: X265Tune): X265Param {
        checkOpen()
        val result = X265Native.paramsTune(handle, tune.value)
        if (result < 0) {
            throw X265Exception("x265 rejected tune '${tune.value}' (code $result)")
        }
        return this
    }

    /** Applies an 8-bit profile. */
    fun profile(profile: X265Profile): X265Param {
        checkOpen()
        val result = X265Native.paramsProfile(handle, profile.value)
        if (result < 0) {
            throw X265Exception("x265 rejected profile '${profile.value}' (code $result)")
        }
        return this
    }

    /** Applies a profile by its raw x265 name. */
    fun profile(name: String): X265Param {
        checkOpen()
        val result = X265Native.paramsProfile(handle, name)
        if (result < 0) {
            throw X265Exception("x265 rejected profile '$name' (code $result)")
        }
        return this
    }

    /** Sets the source frame rate. */
    fun frameRate(numerator: Int, denominator: Int = 1): X265Param = set("fps", "$numerator/$denominator")

    /**
     * Constant-rate-factor mode, the recommended quality mode: 0 is lossless,
     * 28 is roughly "visually good", 51 the worst quality.
     */
    fun crf(value: Double): X265Param = set("crf", value.toString())

    /** Constant-bitrate target in kbit/s (switches x265 to ABR mode). */
    fun bitrate(kbitsPerSecond: Int): X265Param = set("bitrate", kbitsPerSecond)

    /** Sanity check of the fields x265 validates before opening an encoder. */
    fun validate(): Boolean {
        checkOpen()
        return X265Native.paramsValidate(handle) == 0
    }

    internal fun getInt(field: X265Field): Int = X265Native.paramsGetInt(handle, field.value)

    override fun close() {
        if (!closed) {
            closed = true
            X265Native.paramsFree(handle)
        }
    }

    override fun toString(): String =
        "X265Param(${width}x$height $chromaFormat, bitDepth=$bitDepth, threads=$frameThreads)"

    private fun apply(name: String, value: String): X265Param {
        checkOpen()
        val result = X265Native.paramsParse(handle, name, value)
        if (result < 0) {
            throw X265Exception("x265 rejected option '$name'='$value' (code $result)")
        }
        return this
    }

    private fun checkOpen() {
        check(!closed) { "X265Param is closed" }
    }

    companion object {
        /**
         * Creates a parameter block for a `width` x `height` source, initialised
         * with x265's defaults, the [preset] and 30 fps.
         */
        fun create(
            width: Int,
            height: Int,
            chromaFormat: X265ChromaFormat = X265ChromaFormat.I420,
            preset: X265Preset = X265Preset.MEDIUM,
        ): X265Param {
            require(width > 0 && height > 0) { "size must be positive, got ${width}x$height" }
            val handle = X265Native.paramsCreate(width, height, chromaFormat.value)
                ?: throw X265Exception("x265 could not allocate ${width}x$height $chromaFormat parameters")
            val param = X265Param(handle)
            try {
                if (preset != X265Preset.MEDIUM) {
                    param.preset(preset)
                }
                if (chromaFormat == X265ChromaFormat.I420 && (width % 2 != 0 || height % 2 != 0)) {
                    throw X265Exception("4:2:0 requires even dimensions, got ${width}x$height")
                }
            } catch (e: Throwable) {
                param.close()
                throw e
            }
            return param
        }
    }
}
