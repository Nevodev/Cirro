package com.nevoit.cirro.playback

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRouter2
import android.os.Build

internal fun showAudioOutputPicker(context: Context): Boolean {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return MediaRouter2.getInstance(context).showSystemOutputSwitcher()
        }

        // Android 11–13 的系统选择器尚无公开 API；这些入口与 AndroidX MediaRouter 一致。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG")
                .setPackage("com.android.systemui")
                .putExtra("package_name", context.packageName)
            val receivers = context.packageManager.queryBroadcastReceivers(
                intent, PackageManager.MATCH_SYSTEM_ONLY,
            )
            if (receivers.any { it.activityInfo?.exported == true }) {
                context.sendBroadcast(intent)
                return true
            }
        }

        val intent = Intent("com.android.settings.panel.action.MEDIA_OUTPUT")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", context.packageName)
        val activity = context.packageManager.queryIntentActivities(
            intent, PackageManager.MATCH_SYSTEM_ONLY,
        ).firstOrNull { it.activityInfo?.exported == true } ?: return false
        intent.setPackage(activity.activityInfo.packageName)
        context.startActivity(intent)
        return true
    } catch (_: ActivityNotFoundException) {
        return false
    } catch (_: SecurityException) {
        return false
    }
}
