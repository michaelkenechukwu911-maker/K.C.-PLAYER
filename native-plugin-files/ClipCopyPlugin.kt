package com.kcplayer.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.nio.ByteBuffer

object ClipCopier {

    private data class TrackJob(val extractor: MediaExtractor, val outTrack: Int)

    // Extracts [startMs, endMs) directly from the source video file's own
    // encoded data — no screen capture, no re-encoding, no consent dialog,
    // and video/audio stay perfectly in sync since they're the original
    // bytes, not a live real-time capture. Trade-off: the start point snaps
    // to the nearest keyframe at or before startMs, which can land up to a
    // couple of seconds early depending on how far apart keyframes are in
    // the source file — a cheap, honest limitation for a first version.
    fun copySegment(context: Context, sourceUri: Uri, startMs: Long, endMs: Long, outputPath: String) {
        val startUs = startMs * 1000
        val endUs = endMs * 1000

        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val jobs = mutableListOf<TrackJob>()

        for (mimePrefix in listOf("video/", "audio/")) {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, sourceUri, null)

            var srcTrack = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith(mimePrefix)) { srcTrack = i; break }
            }
            if (srcTrack == -1) { extractor.release(); continue }

            extractor.selectTrack(srcTrack)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val outTrack = muxer.addTrack(extractor.getTrackFormat(srcTrack))
            jobs.add(TrackJob(extractor, outTrack))
        }

        if (jobs.isEmpty()) {
            muxer.release()
            throw IllegalStateException("No video or audio tracks found in source file")
        }

        muxer.start()

        val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
        val bufferInfo = MediaCodec.BufferInfo()

        for (job in jobs) {
            while (true) {
                val sampleTime = job.extractor.sampleTime
                if (sampleTime == -1L || sampleTime > endUs) break

                buffer.clear()
                val size = job.extractor.readSampleData(buffer, 0)
                if (size < 0) break

                bufferInfo.offset = 0
                bufferInfo.size = size
                bufferInfo.presentationTimeUs = maxOf(0L, sampleTime - startUs)
                bufferInfo.flags = job.extractor.sampleFlags

                muxer.writeSampleData(job.outTrack, buffer, bufferInfo)
                job.extractor.advance()
            }
            job.extractor.release()
        }

        muxer.stop()
        muxer.release()
    }
}

@CapacitorPlugin(name = "ClipCopy")
class ClipCopyPlugin : Plugin() {

    @PluginMethod
    fun copyMoment(call: PluginCall) {
        val sourceUriString = call.getString("sourceUri")
        val startMs = call.getDouble("startMs")?.toLong()
        val endMs = call.getDouble("endMs")?.toLong()

        if (sourceUriString == null || startMs == null || endMs == null) {
            call.reject("sourceUri, startMs, and endMs are all required")
            return
        }
        if (endMs <= startMs) {
            call.reject("endMs must be after startMs")
            return
        }

        // Runs off the main thread — file extraction is fast, but there's
        // no reason to risk a UI hiccup while it happens.
        Thread {
            try {
                val outputPath = "${context.getExternalFilesDir(null)?.absolutePath}/kc_clip_${System.currentTimeMillis()}.mp4"
                ClipCopier.copySegment(context, Uri.parse(sourceUriString), startMs, endMs, outputPath)

                val ret = JSObject()
                ret.put("path", outputPath)
                ret.put("durationMs", (endMs - startMs))
                activity.runOnUiThread { call.resolve(ret) }
            } catch (e: Exception) {
                activity.runOnUiThread { call.reject("Could not copy that moment: ${e.message}") }
            }
        }.start()
    }
}
