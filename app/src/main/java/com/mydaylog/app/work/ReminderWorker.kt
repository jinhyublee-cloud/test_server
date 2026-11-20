package com.mydaylog.app.work

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mydaylog.app.App
import com.mydaylog.app.Prefs
import com.mydaylog.app.R
import com.mydaylog.app.insight.Insights
import com.mydaylog.app.ui.MainActivity
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/** 매일 아침 마감·정기 결제가 다가오면 알려줌. 알림에는 개수만 (내용은 앱 안에서만) */
class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        runCatching { Insights.refresh(ctx) }
        val (dl, rc) = runCatching { Insights.dueCounts(ctx) }.getOrDefault(0 to 0)
        if (dl + rc == 0) return Result.success()
        if (!canNotify(ctx)) return Result.success()
        val parts = listOfNotNull(if (dl > 0) "마감 ${dl}건" else null, if (rc > 0) "정기 결제 ${rc}건" else null)
        val pi = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java).putExtra("tab", 2)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(ctx, App.CH_REMIND)
            .setSmallIcon(R.drawable.ic_stat_journal)
            .setContentTitle("하루기록 · 챙길 것")
            .setContentText("${parts.joinToString(", ")}이 다가와요")
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(pi).setAutoCancel(true).build()
        ctx.getSystemService(NotificationManager::class.java).notify(1002, n)
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val now = LocalDateTime.now()
            var next = now.toLocalDate().atTime(Prefs.remindHour(ctx), 0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val req = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("reminder", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
