@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package cn.enaium.x265.examples.browser

import cn.enaium.x265.X265
import cn.enaium.x265.X265ChromaFormat
import cn.enaium.x265.X265Encoder
import cn.enaium.x265.X265Frame
import cn.enaium.x265.X265LogLevel
import cn.enaium.x265.X265NalType
import cn.enaium.x265.X265Param
import cn.enaium.x265.X265Picture
import cn.enaium.x265.X265Preset
import cn.enaium.x265.X265Tune
import kotlin.io.encoding.Base64
import org.khronos.webgl.Uint8ClampedArray
import org.khronos.webgl.get

/**
 * Browser loopback:
 *
 * 1. `navigator.mediaDevices.getUserMedia()` provides the camera stream.
 * 2. Every new video frame is drawn to a 2D canvas and converted from RGBA to
 *    I420 (x265's input format).
 * 3. `X265Encoder` (the same embedded WebAssembly module the published js
 *    artifact uses) encodes the picture.
 * 4. The Annex-B access unit is handed to a WebCodecs `VideoDecoder`, whose
 *    output frames are painted on the second canvas.
 *
 * The demo intentionally uses plain `js()` interop: WebCodecs and
 * `getUserMedia` are not covered by the Kotlin/JS DOM declarations yet, and
 * keeping the decode side in JavaScript makes the WebCodecs usage easy to
 * compare with the MDN examples.
 */
private val document: dynamic = js("document")
private val window: dynamic = js("window")
private val navigator: dynamic = js("navigator")
private val console: dynamic = js("console")

private var video: dynamic = null
private var capture: dynamic = null
private var outputCanvas: dynamic = null
private var status: dynamic = null
private var stats: dynamic = null

private var encoder: X265Encoder? = null
private var decoder: dynamic = null
private var decoderReady = false

private var lastFrameTime = -1.0
private var lastReportTime = 0.0
private var encodedFrames = 0L
private var lastFrameSize = 0

fun main() {
    buildDom()
    startCamera()
}

private fun buildDom() {
    status = document.getElementById("status")
    stats = document.getElementById("stats")

    video = document.createElement("video")
    video.autoplay = true
    video.muted = true
    video.playsInline = true
    document.getElementById("camera").appendChild(video)

    outputCanvas = document.createElement("canvas")
    outputCanvas.width = 640
    outputCanvas.height = 480
    document.getElementById("decoded").appendChild(outputCanvas)

    // Off-screen canvas used only to read the camera frame back as RGBA.
    capture = document.createElement("canvas")
    capture.width = 640
    capture.height = 480

    status.textContent = "Requesting camera…"
}

private fun startCamera() {
    val constraints = js(
        """({
            audio: false,
            video: {
                width: { ideal: 640 },
                height: { ideal: 480 },
                frameRate: { ideal: 30 }
            }
        })""",
    )
    if ((js("typeof navigator.mediaDevices === 'undefined'") as Boolean)) {
        status.textContent = "navigator.mediaDevices is not available (serve the page over HTTPS or localhost)."
        return
    }
    navigator.mediaDevices.getUserMedia(constraints).then({ stream: dynamic ->
        video.srcObject = stream
        video.play()
        status.textContent = "Camera ready, opening encoder…"
        requestFrame()
    }).catch({ error: dynamic ->
        status.textContent = "Camera error: $error"
        console.error(error)
    })
}

private fun requestFrame() {
    window.requestAnimationFrame({ frameLoop() })
}

private fun frameLoop() {
    try {
        if ((video.readyState as Double) >= 2.0 && (video.videoWidth as Double) > 0.0) {
            encodeNewFrame()
        }
        val now = window.performance.now() as Double
        if (now - lastReportTime > 500.0) {
            lastReportTime = now
            report()
        }
    } catch (error: Throwable) {
        status.textContent = "Pipeline error: ${error.message}"
        console.error(error)
    }
    requestFrame()
}

private fun encodeNewFrame() {
    val width = (video.videoWidth as Double).toInt()
    val height = (video.videoHeight as Double).toInt()
    // I420 needs whole chroma pixels and the converter below reads 2x2 blocks.
    if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) return

    val time = video.currentTime as Double
    if (time == lastFrameTime) return
    lastFrameTime = time

    if ((capture.width as Double).toInt() != width || (capture.height as Double).toInt() != height) {
        capture.width = width
        capture.height = height
    }
    val context = capture.getContext("2d")
    context.drawImage(video, 0, 0, width, height)
    val rgba = context.getImageData(0, 0, width, height).data as Uint8ClampedArray

    val luma = ByteArray(width * height)
    val chromaWidth = width / 2
    val chromaHeight = height / 2
    val u = ByteArray(chromaWidth * chromaHeight)
    val v = ByteArray(chromaWidth * chromaHeight)
    rgbaToI420(rgba, width, height, luma, u, v)

    val active = encoder ?: openEncoder(width, height)
    val pts = ((window.performance.now() as Double) * 1000.0).toLong()
    val frame = active.encode(X265Picture.i420(luma, u, v, width, height, pts)) ?: return

    encodedFrames++
    lastFrameSize = frame.byteSize
    if (decoderReady) {
        decoder.feed(Base64.encode(frame.toAnnexB()), frame.isKeyFrame, frame.pts)
    }
}

private fun openEncoder(width: Int, height: Int): X265Encoder {
    val param = X265Param.create(width, height, X265ChromaFormat.I420, X265Preset.ULTRAFAST)
    try {
        param.tune(X265Tune.ZERO_LATENCY)
        param.set("crf", 28)
        param.set("keyint", 30)
        param.set("repeat-headers", true)
        param.logLevel = X265LogLevel.NONE
        val opened = X265Encoder.open(param)
        encoder = opened
        status.textContent = "x265 ${X265.libraryVersion}, ${width}x$height"
        configureDecoder(opened.parameters.width, opened.parameters.height, opened.headers())
        return opened
    } finally {
        param.close()
    }
}

private fun configureDecoder(width: Int, height: Int, headers: X265Frame) {
    // HEVC in WebCodecs uses an hvc1/hev1 string. The stream carries its
    // VPS/SPS/PPS in band, so no `description` (hvcC) is supplied and the
    // decoder reads Annex-B. Level 4.0 covers the resolutions this demo
    // captures; support is hardware dependent (HEVC decoders are optional).
    val hasSps = headers.nals.any { it.type == X265NalType.SPS.value }
    val codec = if (hasSps) "hev1.1.6.L120.B0" else "hev1.1.6.L93.B0"
    decoder = createDecoder(outputCanvas)
    decoderReady = decoder.configure(codec, width, height) as Boolean
    if (!decoderReady) {
        status.textContent = "This browser does not expose a usable WebCodecs HEVC VideoDecoder."
    }
}

private fun report() {
    val decoded = if (decoderReady) {
        (decoder.decoded() as Double).toInt()
    } else {
        0
    }
    val decoderState = if (decoderReady) {
        val error = decoder.lastError() as String
        "${decoder.state()} (${decoder.support()})" + if (error.isEmpty()) "" else " error=$error"
    } else {
        "unavailable"
    }
    stats.textContent = "encoded $encodedFrames frames · decoded $decoded frames · " +
        "last ${lastFrameSize} bytes · decoder $decoderState"
}

/** BT.601 limited-range RGBA to planar I420. */
private fun rgbaToI420(
    rgba: Uint8ClampedArray,
    width: Int,
    height: Int,
    y: ByteArray,
    u: ByteArray,
    v: ByteArray,
) {
    var uvIndex = 0
    var row = 0
    while (row < height) {
        var column = 0
        while (column < width) {
            val offset = (row * width + column) * 4
            val r = rgba.get(offset)
            val g = rgba.get(offset + 1)
            val b = rgba.get(offset + 2)
            y[row * width + column] = clamp(((66 * r + 129 * g + 25 * b + 128) shr 8) + 16)

            if (row % 2 == 0 && column % 2 == 0) {
                val right = offset + 4
                val bottom = offset + width * 4
                val bottomRight = bottom + 4
                val r2 = (r + rgba.get(right) + rgba.get(bottom) + rgba.get(bottomRight)) / 4
                val g2 = (g + rgba.get(right + 1) + rgba.get(bottom + 1) + rgba.get(bottomRight + 1)) / 4
                val b2 = (b + rgba.get(right + 2) + rgba.get(bottom + 2) + rgba.get(bottomRight + 2)) / 4
                u[uvIndex] = clamp(((-38 * r2 - 74 * g2 + 112 * b2 + 128) shr 8) + 128)
                v[uvIndex] = clamp(((112 * r2 - 94 * g2 - 18 * b2 + 128) shr 8) + 128)
                uvIndex++
            }
            column++
        }
        row++
    }
}

private fun clamp(value: Int): Byte = when {
    value < 0 -> 0
    value > 255 -> 255
    else -> value
}.toByte()

/**
 * Builds the WebCodecs decoder and its canvas sink. Kept in JavaScript because
 * `VideoDecoder` and `EncodedVideoChunk` have no Kotlin declarations yet.
 */
private fun createDecoder(canvas: dynamic): dynamic = js(
    """(function(canvas) {
        const context = canvas.getContext('2d');
        let decoder = null;
        let decoded = 0;
        let lastError = null;
        let support = 'unknown';
        const toBytes = (base64) => {
            const binary = atob(base64);
            const bytes = new Uint8Array(binary.length);
            for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
            return bytes;
        };
        if (typeof VideoDecoder === 'undefined') {
            return {
                configure() { return false; },
                feed() { return false; },
                decoded() { return 0; }
            };
        }
        return {
            configure(codec, width, height) {
                if (decoder) {
                    try { decoder.close(); } catch (e) { /* already closed */ }
                }
                const config = {
                    codec: codec,
                    codedWidth: width,
                    codedHeight: height,
                    optimizeForLatency: true
                };
                try {
                    decoder = new VideoDecoder({
                        output(frame) {
                            canvas.width = frame.displayWidth || width;
                            canvas.height = frame.displayHeight || height;
                            context.drawImage(frame, 0, 0, canvas.width, canvas.height);
                            decoded++;
                            frame.close();
                        },
                        error(error) {
                            lastError = String(error && error.message || error);
                            console.error('WebCodecs decode error', error);
                        }
                    });
                    decoder.configure(config);
                } catch (error) {
                    console.error('WebCodecs configure failed', error);
                    decoder = null;
                    return false;
                }
                VideoDecoder.isConfigSupported(config).then((result) => {
                    support = result.supported ? 'supported' : 'unsupported';
                    if (!result.supported) {
                        console.error('Unsupported decoder config', config, result);
                    }
                }).catch((error) => {
                    support = 'error:' + error;
                });
                return true;
            },
            feed(base64, key, timestamp) {
                if (!decoder) return false;
                try {
                    decoder.decode(new EncodedVideoChunk({
                        type: key ? 'key' : 'delta',
                        timestamp: Number(timestamp),
                        data: toBytes(base64)
                    }));
                    return true;
                } catch (error) {
                    console.error('WebCodecs feed failed', error);
                    return false;
                }
            },
            decoded() { return decoded; },
            state() { return decoder ? decoder.state : 'unconfigured'; },
            support() { return support; },
            lastError() { return lastError || ''; }
        };
    })""",
)(canvas)
