package com.kcplayer.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.core.content.ContextCompat
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin

@CapacitorPlugin(name = "ClipCapture")
class ClipCapturePlugin : Plugin() {

    @PluginMethod
    fun startCapture(call: PluginCall) {
        // Recording the picture *and* the audio a video is playing requires
        // MediaProjection — Android's screen-capture consent system. Unlike
        // the one-time video-library permission, this consent is NOT
        // persistent by OS design: it must be re-granted every time a
        // recording starts. That's an Android platform rule, not something
        // this app can change.
        val projectionManager =
            activity.getSystemService(Activity.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = projectionManager.createScreenCaptureIntent()
        startActivityForResult(call, intent, "onCaptureConsentResult")
    }

    @ActivityCallback
    private fun onCaptureConsentResult(call: PluginCall?, result: androidx.activity.result.ActivityResult) {
        val c = call ?: return
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            c.reject("Screen recording permission was not granted")
            return
        }

        ClipCaptureService.pendingResultCode = result.resultCode
        ClipCaptureService.pendingResultData = result.data

        val serviceIntent = Intent(context, ClipCaptureService::class.java).apply {
            action = ClipCaptureService.ACTION_START
        }
        ContextCompat.startForegroundService(context, serviceIntent)

        val ret = JSObject()
        ret.put("status", "recording")
        c.resolve(ret)
    }

    @PluginMethod
    fun stopCapture(call: PluginCall) {
        ClipCaptureService.onStopped = { path ->
            val ret = JSObject()
            if (path != null) {
                ret.put("path", path)
                call.resolve(ret)
            } else {
                call.reject("Recording did not produce a file")
            }
            ClipCaptureService.onStopped = null
        }

        val serviceIntent = Intent(context, ClipCaptureService::class.java).apply {
            action = ClipCaptureService.ACTION_STOP
        }
        context.startService(serviceIntent)
    }

    // Hides the system status bar and navigation bar for the duration of a
    // recording — MediaProjection mirrors the whole display, so this (plus
    // hiding the app's own UI in JS) is what keeps the captured clip looking
    // like clean video instead of a recording of the phone's chrome too.
    @PluginMethod
    fun enterImmersive(call: PluginCall) {
        activity.runOnUiThread {
            val controller = androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        call.resolve()
    }

    @PluginMethod
    fun exitImmersive(call: PluginCall) {
        activity.runOnUiThread {
            val controller = androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        call.resolve()
    }
}
