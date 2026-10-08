/*
 * Copyright © 2024 Mark Raynsford <code@io7m.com> https://www.io7m.com
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY
 * SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR
 * IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */

package com.io7m.exfilac.main

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.io7m.exfilac.core.EFUploadStatusFailed
import com.io7m.exfilac.core.EFUploadStatusRunning
import com.io7m.exfilac.core.ExfilacType
import com.io7m.taskrecorder.core.TRStep
import com.io7m.taskrecorder.core.TRStepFailed
import com.io7m.taskrecorder.core.TRTask
import com.io7m.taskrecorder.core.TRTaskItemType
import java.time.OffsetDateTime
import kotlin.math.roundToInt

object EFNotifications {

  private const val NOTIFICATION_CHANNEL_ID =
    "com.io7m.exfilac.main.notifications"
  private const val PROBLEMS_CHANNEL_ID =
    "com.io7m.exfilac.main.problems"

  const val SUPERVISOR_NOTIFICATION_ID = 1
  private const val PROBLEM_NOTIFICATION_ID = 2

  private var supervisorNotificationShown: String? = null
  private val failuresNotified =
    mutableMapOf<String, OffsetDateTime>()

  fun notificationsDisplayDialog(
    context: Activity,
    onDismiss: Runnable
  ) {
    if (Build.VERSION.SDK_INT >= 33) {
      if (!notificationsArePermitted(context)) {
        MaterialAlertDialogBuilder(context)
          .setMessage(R.string.notification_explanation)
          .setOnDismissListener {
            try {
              context.requestPermissions(
                arrayOf("android.permission.POST_NOTIFICATIONS"),
                1000
              )
            } finally {
              onDismiss.run()
            }
          }
          .show()
      }
    }
  }

  fun notificationsArePermitted(
    context: Context
  ): Boolean {
    return ContextCompat.checkSelfPermission(
      context,
      "android.permission.POST_NOTIFICATIONS"
    ) == PackageManager.PERMISSION_GRANTED
  }

  fun createNotificationChannel(
    context: Context
  ) {
    val notificationManager =
      context.getSystemService(Service.NOTIFICATION_SERVICE) as NotificationManager

    val channel = NotificationChannel(
      NOTIFICATION_CHANNEL_ID,
      context.getString(R.string.notification_title),
      NotificationManager.IMPORTANCE_LOW
    )
    notificationManager.createNotificationChannel(channel)

    notificationManager.createNotificationChannel(
      NotificationChannel(
        PROBLEMS_CHANNEL_ID,
        context.getString(R.string.notification_problems_channel),
        NotificationManager.IMPORTANCE_DEFAULT
      )
    )
  }

  fun buildNotification(
    context: Context,
    text: String? = null,
    progressPercent: Int? = null
  ): Notification {
    val builder =
      NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
        .setContentTitle(context.getString(R.string.notification_title))
        .setContentText(text ?: context.getString(R.string.notification_description))
        .setSmallIcon(R.drawable.io7m_inverse_framed_24)
        .setOnlyAlertOnce(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(this.activityIntentOf(context))

    if (progressPercent != null) {
      builder.setProgress(100, progressPercent, false)
    }
    return builder.build()
  }

  private fun activityIntentOf(
    context: Context
  ): PendingIntent {
    return Intent(context, EFActivity::class.java).let { notificationIntent ->
      PendingIntent.getActivity(context, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
    }
  }

  /**
   * Show the running upload and how far its current file has got in the foreground
   * notification, and post a notification when an upload ends with files that did not upload.
   * Called on the UI thread every few seconds.
   */

  fun updateUploadNotifications(
    context: Context,
    exfilac: ExfilacType
  ) {
    if (!this.notificationsArePermitted(context)) {
      return
    }

    val notificationManager =
      context.getSystemService(Service.NOTIFICATION_SERVICE) as NotificationManager
    val statuses =
      exfilac.uploads.get().map { upload -> exfilac.uploadStatus(upload.name) }

    val running =
      statuses.filterIsInstance<EFUploadStatusRunning>().firstOrNull()
    val progressPercent =
      running?.progressMinor?.let { p -> (p * 100.0).roundToInt().coerceIn(0, 100) }
    val text =
      running?.let { r ->
        val step = "${r.name.value}: ${r.description}"
        if (progressPercent != null) "$step $progressPercent%" else step
      }

    if (text != this.supervisorNotificationShown) {
      this.supervisorNotificationShown = text
      notificationManager.notify(
        SUPERVISOR_NOTIFICATION_ID,
        this.buildNotification(context, text, progressPercent)
      )
    }

    for (failed in statuses.filterIsInstance<EFUploadStatusFailed>()) {
      val uploadName = failed.name.value
      if (this.failuresNotified[uploadName] == failed.failedAt) {
        continue
      }
      this.failuresNotified[uploadName] = failed.failedAt
      notificationManager.notify(
        uploadName,
        PROBLEM_NOTIFICATION_ID,
        this.buildProblemNotification(context, uploadName, failed)
      )
    }
  }

  private fun buildProblemNotification(
    context: Context,
    uploadName: String,
    failed: EFUploadStatusFailed
  ): Notification {
    val steps = this.failedSteps(failed.result)
    val files =
      steps.map { s -> s.description() }
        .filter { d -> d.startsWith("Uploading ") && d != "Uploading files…" }
        .map { d -> d.removePrefix("Uploading ").removeSuffix("…").substringAfterLast('/') }

    val text =
      when (files.size) {
        0 -> {
          val step = steps.firstOrNull()
          context.getString(
            R.string.notification_problem_upload,
            step?.description() ?: "",
            step?.resolution()?.message() ?: ""
          )
        }
        1 -> context.getString(R.string.notification_problem_file, files[0])
        else -> context.getString(R.string.notification_problem_files, files.size, files[0])
      }

    return NotificationCompat.Builder(context, PROBLEMS_CHANNEL_ID)
      .setContentTitle(context.getString(R.string.notification_problem_title, uploadName))
      .setContentText(text)
      .setStyle(NotificationCompat.BigTextStyle().bigText(text))
      .setSmallIcon(R.drawable.io7m_inverse_framed_24)
      .setContentIntent(this.activityIntentOf(context))
      .setAutoCancel(true)
      .build()
  }

  private fun failedSteps(
    item: TRTaskItemType
  ): List<TRStep> {
    return when (item) {
      is TRStep -> if (item.resolution() is TRStepFailed) listOf(item) else listOf()
      is TRTask<*> -> item.items().flatMap { i -> this.failedSteps(i) }
      else -> listOf()
    }
  }
}
