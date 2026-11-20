package com.mydaylog.app.work

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mydaylog.app.App
import com.mydaylog.app.Prefs
import com.mydaylog.app.R
import com.mydaylog.app.data.Db
import com.mydaylog.app.insight.Insights
import com.mydaylog.app.summary.Summarizer
import com.mydaylog.app.ui.MainActivity
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/** 매일 정해진 시각에 그날의 요약을 폰 안에서 자동 생성 */
class DailyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val now = LocalDateTime.now()
        // 새벽 6시 전에 실행되면 전날 요약으로 처리
        val day = (if (now.hour < 6) now.toLocalDate().minusDays(1) else now.toLocalDate()).toString()

        val r = Summarizer.generate(ctx, day)

        runCatching { Insights.refresh(ctx) }
        val keep = Prefs.retentionDays(ctx)
        runCatching { Db.get(ctx).cleanup(if (keep > 0) LocalDate.now().minusDays(keep.toLong()).toString() else null) }

        return r.fold(
            onSuccess = { notifyDone(); Result.success() },
            onFailure = { if (runAttemptCount < 3) Result.retry() else Result.failure() },
        )
    }

    /** 잠금화면·알림에는 내용 없이 안내만 표시 */
    private fun notifyDone() {
        val ctx = applicationContext
        if (!canNotify(ctx)) return
        val pi = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(ctx, App.CH_JOURNAL)
            .setSmallIcon(R.drawable.ic_stat_journal)
            .setContentTitle("하루기록")
            .setContentText("오늘의 요약이 준비됐어요")
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(1001, n)
    }

    companion object {
        private const val NAME = "daily_journal"

        fun schedule(ctx: Context, replace: Boolean) {
            val now = LocalDateTime.now()
            var next = now.toLocalDate().atTime(Prefs.hour(ctx), Prefs.minute(ctx))
            if (!next.isAfter(now)) next = next.plusDays(1)
            val delayMin = Duration.between(now, next).toMinutes()

            val req = PeriodicWorkRequestBuilder<DailyWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delayMin, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                NAME,
                if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP,
                req,
            )
        }
    }
}
