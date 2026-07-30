package com.kcplayer.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

class ClipCaptureService : Service() {

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        private const val NOTIF_CHANNEL = "kc_player_capture"
        private const val NOTIF_ID = 4201

        // Handed off from the plugin after the user grants the MediaProjection
        // consent dialog — a service can't request that consent itself.
        var pendingResultCode: Int = 0
        var pendingResultData: Intent? = null

        // Simple in-process callback back to the plugin once the file is
        // finalized — avoids needing a broadcast/binder round trip for
        // something this simple, since service and plugin share a process.
        var onStopped: ((String?) -> Unit)? = null
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var muxer: MediaMuxer? = null

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var videoTrackAdded = false
    private var audioTrackAdded = false
    private var muxerStarted = false

    private val isRecording = AtomicBoolean(false)
    private var outputPath: String? = null
    private var recordingStartNanos = 0L

    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture()
            ACTION_STOP -> stopCapture()
        }
        return START_NOT_STICKY
    }

    private fun startCapture() {
        startForegroundWithNotification()

        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val resultData = pendingResultData ?: run { stopSelf(); return }
        mediaProjection = projectionManager.getMediaProjection(pendingResultCode, resultData)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopCapture() }
        }, null)

        val metrics = DisplayMetrics()
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        // Cap the recorded resolution — the device's raw screen size can be
        // large, and encoding at full resolution costs battery and file
        // size for no real benefit on a phone-sized clip.
        val scale = minOf(1f, 1280f / maxOf(metrics.widthPixels, metrics.heightPixels))
        val width = (metrics.widthPixels * scale).toInt() / 2 * 2
        val height = (metrics.heightPixels * scale).toInt() / 2 * 2

        outputPath = "${getExternalFilesDir(null)?.absolutePath}/kc_clip_${System.currentTimeMillis()}.mp4"
        muxer = MediaMuxer(outputPath!!, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        setUpVideoEncoder(width, height, metrics.densityDpi)
        setUpAudioEncoder()

        recordingStartNanos = System.nanoTime()
        isRecording.set(true)

        videoThread = Thread(::drainVideoEncoder).apply { start() }
        audioThread = Thread(::captureAndEncodeAudio).apply { start() }
    }

    private fun setUpVideoEncoder(width: Int, height: Int, densityDpi: Int) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val inputSurface = videoEncoder!!.createInputSurface()
        videoEncoder!!.start()

        // The system compositor stamps each frame it hands to this Surface
        // with its own timestamp automatically — unlike a manual GL bridge,
        // there's no PTS bookkeeping needed on our side for video.
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "KCPlayerCapture", width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null
        )
    }

    private fun setUpAudioEncoder() {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
        }
        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }

        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(44100)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(minBuf * 2)
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .build()
        audioRecord?.startRecording()
    }

    private fun drainVideoEncoder() {
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val encoder = videoEncoder ?: break
            val index = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    videoTrackIndex = muxer!!.addTrack(encoder.outputFormat)
                    videoTrackAdded = true
                    maybeStartMuxer()
                }
                index >= 0 -> {
                    val buf = encoder.getOutputBuffer(index)
                    if (buf != null && muxerStarted && bufferInfo.size > 0) {
                        muxer?.writeSampleData(videoTrackIndex, buf, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(index, false)
                    // Loop on the encoder's own end-of-stream flag, not the
                    // isRecording flag — those two can race, and exiting on
                    // isRecording alone risks dropping the last buffered
                    // frames right at the moment recording stops.
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER && !isRecording.get() -> {
                    // Stopped, but the encoder never emitted an EOS buffer
                    // (can happen if surface input was already idle) — bail
                    // out rather than spin forever.
                    break
                }
            }
        }
    }

    private fun captureAndEncodeAudio() {
        val pcmBuffer = ByteArray(4096)
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRecording.get()) {
            val read = audioRecord?.read(pcmBuffer, 0, pcmBuffer.size) ?: 0
            if (read > 0) {
                val ptsUs = (System.nanoTime() - recordingStartNanos) / 1000
                val inIndex = audioEncoder?.dequeueInputBuffer(10_000) ?: -1
                if (inIndex >= 0) {
                    val inBuf = audioEncoder!!.getInputBuffer(inIndex)
                    inBuf?.clear()
                    inBuf?.put(pcmBuffer, 0, read)
                    audioEncoder!!.queueInputBuffer(inIndex, 0, read, ptsUs, 0)
                }
            }
            drainAudioEncoder(bufferInfo)
        }

        // Flush whatever's left once recording stops.
        val inIndex = audioEncoder?.dequeueInputBuffer(10_000) ?: -1
        if (inIndex >= 0) {
            audioEncoder?.queueInputBuffer(inIndex, 0, 0, (System.nanoTime() - recordingStartNanos) / 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        drainAudioEncoder(bufferInfo, drainAll = true)
    }

    private fun drainAudioEncoder(bufferInfo: MediaCodec.BufferInfo, drainAll: Boolean = false) {
        val encoder = audioEncoder ?: return
        while (true) {
            val index = encoder.dequeueOutputBuffer(bufferInfo, if (drainAll) 10_000 else 0)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                audioTrackIndex = muxer!!.addTrack(encoder.outputFormat)
                audioTrackAdded = true
                maybeStartMuxer()
                continue
            }
            if (index < 0) break
            val buf = encoder.getOutputBuffer(index)
            if (buf != null && muxerStarted && bufferInfo.size > 0) {
                muxer?.writeSampleData(audioTrackIndex, buf, bufferInfo)
            }
            encoder.releaseOutputBuffer(index, false)
            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
    }

    @Synchronized
    private fun maybeStartMuxer() {
        if (!muxerStarted && videoTrackAdded && audioTrackAdded) {
            muxer?.start()
            muxerStarted = true
        }
    }

    private fun stopCapture() {
        if (!isRecording.get()) { stopSelf(); return }
        isRecording.set(false)
        videoEncoder?.signalEndOfInputStream()

        videoThread?.join(2000)
        audioThread?.join(2000)

        try {
            audioRecord?.stop(); audioRecord?.release()
            videoEncoder?.stop(); videoEncoder?.release()
            audioEncoder?.stop(); audioEncoder?.release()
            virtualDisplay?.release()
            mediaProjection?.stop()
            if (muxerStarted) { muxer?.stop() }
            muxer?.release()
        } catch (e: Exception) {
            // Best-effort cleanup — a failure here shouldn't crash the app,
            // it just means this one clip may be unusable.
        }

        onStopped?.invoke(outputPath)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIF_CHANNEL, "Clip recording", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("KC-Player is recording a clip")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }
}
