package com.shreddro.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.shreddro.app.MainActivity

/**
 * Batch digest notifications ("N slips archived — tap to sweep them from your
 * gallery"). The purge consent dialog needs a visible Activity, so background
 * scans archive silently and this nudge brings the user back to finish.
 */
object Notifications {

    private const val CHANNEL_ID = "shreddro_digest"
    private const val DIGEST_ID = 1001

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Slip processing digests",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "Summaries of processed bank slips awaiting gallery sweep" }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    fun postScanDigest(context: Context, archived: Int, needsReview: Int) {
        if (archived == 0 && needsReview == 0) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return // user declined POST_NOTIFICATIONS; stay silent
        }
        ensureChannel(context)

        val tapIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val text = buildList {
            if (archived > 0) add("$archived slip(s) ready to sweep from your gallery")
            if (needsReview > 0) add("$needsReview need manual review")
        }.joinToString(" · ")

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("Shreddro processed new slips")
            .setContentText(text)
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(DIGEST_ID, notification)
    }
}
