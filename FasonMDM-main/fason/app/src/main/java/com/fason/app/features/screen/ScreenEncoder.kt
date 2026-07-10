package com.fason.app.features.screen

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * Owns the complete capture/encode pipeline on a dedicated thread.
 *
 * MediaCodec configuration used to run on the service main thread and the
 * encoder was fixed to 720x1280. The encoder now receives the real display
 * metrics and never blocks the service/socket command threads.
 */
class ScreenEncoder(
    private val mediaProjection: MediaProjection,
    private val videoWidth: Int,
    private val videoHeight: Int,
    private val videoDpi: Int,
    private val onReady: () -> Unit,
    private val onFrameEncoded: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
    private val onProjectionStopped: () -> Unit,
) {
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaCodec: MediaCodec? = null
    private var encoderThread: Thread? = null
    private var codecConfig: ByteArray? = null

    @Volatile
    private var isEncoding = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            isEncoding = false
            encoderThread?.interrupt()
            onProjectionStopped()
        }
    }

    @Synchronized
    fun start() {
        if (isEncoding || encoderThread?.isAlive == true) return

        isEncoding = true
        encoderThread = Thread({ encode() }, "remote-desktop-encoder").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun encode() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)

            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                videoWidth,
                videoHeight,
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, calculateBitRate(videoWidth, videoHeight))
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
            }

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec = codec
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = codec.createInputSurface()
            codec.start()

            // Required before createVirtualDisplay on Android 14+.
            mediaProjection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "FasonRemoteDesktop",
                videoWidth,
                videoHeight,
                videoDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null,
                null,
            )

            onReady()
            drainEncoder(codec)
        } catch (error: Exception) {
            if (isEncoding) {
                Log.e(TAG, "Unable to start screen encoder", error)
                onError(error.message ?: "Unable to start screen encoder")
            }
        } finally {
            releaseResources()
            isEncoding = false
        }
    }

    private fun drainEncoder(codec: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        while (isEncoding && !Thread.currentThread().isInterrupted) {
            try {
                val outputBufferId = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                when {
                    outputBufferId >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputBufferId)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val data = ByteArray(bufferInfo.size)
                            outputBuffer.get(data)
                            val isCodecConfig =
                                bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            val isKeyFrame =
                                bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            when {
                                isCodecConfig -> {
                                    codecConfig = data
                                    onFrameEncoded(data)
                                }
                                isKeyFrame && codecConfig != null -> {
                                    // Repeat SPS/PPS with every IDR so a newly opened web
                                    // panel can begin decoding without restarting capture.
                                    onFrameEncoded(codecConfig!! + data)
                                }
                                else -> onFrameEncoded(data)
                            }
                        }
                        codec.releaseOutputBuffer(outputBufferId, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            break
                        }
                    }

                    outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                        Log.d(TAG, "Encoder format: ${codec.outputFormat}")
                }
            } catch (_: InterruptedException) {
                break
            } catch (error: IllegalStateException) {
                if (isEncoding) throw error else break
            }
        }
    }

    @Synchronized
    fun stop() {
        isEncoding = false
        encoderThread?.interrupt()
    }

    private fun releaseResources() {
        try {
            virtualDisplay?.release()
        } catch (error: Exception) {
            Log.w(TAG, "Unable to release virtual display", error)
        } finally {
            virtualDisplay = null
        }

        try {
            mediaCodec?.stop()
        } catch (_: Exception) {
            // Codec may not have reached the started state.
        }
        try {
            mediaCodec?.release()
        } catch (error: Exception) {
            Log.w(TAG, "Unable to release codec", error)
        } finally {
            mediaCodec = null
            codecConfig = null
        }

        try {
            mediaProjection.unregisterCallback(projectionCallback)
        } catch (_: Exception) {
        }
    }

    private fun calculateBitRate(width: Int, height: Int): Int {
        // Scale bitrate with native resolution while keeping mobile bandwidth bounded.
        return min(MAX_BIT_RATE, max(MIN_BIT_RATE, width * height * 3))
    }

    companion object {
        private const val TAG = "ScreenEncoder"
        private const val FRAME_RATE = 30
        private const val I_FRAME_INTERVAL_SECONDS = 1
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val MIN_BIT_RATE = 2_000_000
        private const val MAX_BIT_RATE = 12_000_000
    }
}
