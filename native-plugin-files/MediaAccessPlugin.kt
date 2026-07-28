package com.kcplayer.app

import android.provider.MediaStore
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback

@CapacitorPlugin(
    name = "MediaAccess",
    permissions = [
        Permission(alias = "videos", strings = ["android.permission.READ_MEDIA_VIDEO"])
    ]
)
class MediaAccessPlugin : Plugin() {

    @PluginMethod
    fun requestAccess(call: PluginCall) {
        if (getPermissionState("videos") == PermissionState.GRANTED) {
            call.resolve(statusResult("granted"))
        } else {
            requestPermissionForAlias("videos", call, "onPermissionResult")
        }
    }

    @PermissionCallback
    private fun onPermissionResult(call: PluginCall) {
        val granted = getPermissionState("videos") == PermissionState.GRANTED
        call.resolve(statusResult(if (granted) "granted" else "denied"))
    }

    @PluginMethod
    fun checkAccess(call: PluginCall) {
        val granted = getPermissionState("videos") == PermissionState.GRANTED
        call.resolve(statusResult(if (granted) "granted" else "denied"))
    }

    // Queries every storage volume — internal AND any inserted SD card —
    // in one pass, sorted alphabetically. This is the native replacement
    // for the browser prototype's manual folder-picking.
    @PluginMethod
    fun listVideos(call: PluginCall) {
        if (getPermissionState("videos") != PermissionState.GRANTED) {
            call.reject("Video access not granted — call requestAccess() first")
            return
        }

        val results = JSArray()
        val volumeNames = MediaStore.getExternalVolumeNames(context)

        for (volumeName in volumeNames) {
            val collection = MediaStore.Video.Media.getContentUri(volumeName)
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.DATE_MODIFIED
            )

            context.contentResolver.query(
                collection, projection, null, null,
                "${MediaStore.Video.Media.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val contentUri = android.content.ContentUris.withAppendedId(collection, id)

                    val item = JSObject()
                    item.put("id", id)
                    item.put("name", cursor.getString(nameCol))
                    item.put("durationMs", cursor.getLong(durationCol))
                    item.put("uri", contentUri.toString())
                    results.put(item)
                }
            }
        }

        val response = JSObject()
        response.put("videos", results)
        call.resolve(response)
    }

    private fun statusResult(status: String) =
        JSObject().put("status", status)
}
