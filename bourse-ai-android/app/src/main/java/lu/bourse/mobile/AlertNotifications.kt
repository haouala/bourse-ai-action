package lu.bourse.mobile

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import lu.bourse.mobile.data.*
import java.util.concurrent.TimeUnit

object AlertNotifications {
    private const val WORK = "bourse-alert-monitor"
    private const val CHANNEL = "bourse-review-alerts"
    private fun prefs(context: Context) = context.getSharedPreferences("alert-notifications", Context.MODE_PRIVATE)
    fun allowed(context: Context) = (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) && NotificationManagerCompat.from(context).areNotificationsEnabled()
    fun enabled(context: Context, config: ConnectionConfig): Boolean = !config.demo && config.portfolioId != null && prefs(context).getBoolean("enabled", false) && prefs(context).getString("scope", null) == monitoringScope(config)
    fun setEnabled(context: Context, config: ConnectionConfig, enabled: Boolean) {
        require(!enabled || (!config.demo && config.portfolioId != null && allowed(context))) { "Configurer un portefeuille serveur et autoriser les notifications" }
        prefs(context).edit().putBoolean("enabled", enabled).putString("scope", monitoringScope(config)).apply()
        if (enabled) WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<AlertWorker>(15, TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        else WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }
    fun reconcile(context: Context, config: ConnectionConfig) {
        if (prefs(context).getBoolean("enabled", false) && (!enabled(context, config) || !allowed(context))) setEnabled(context, config, false)
    }
    fun seen(context: Context): Set<String> = prefs(context).getStringSet("seen", emptySet())!!.toSet()
    private fun markSeen(context: Context, key: String) {
        val next = LinkedHashSet(seen(context)); next.remove(key); next.add(key)
        prefs(context).edit().putStringSet("seen", next.toList().takeLast(500).toSet()).apply()
    }
    fun notify(context: Context, config: ConnectionConfig, alert: Alert) {
        if (!allowed(context) || !enabled(context, config)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Alertes à réexaminer", NotificationManager.IMPORTANCE_DEFAULT))
        val key = notificationKey(config, alert)
        val intent = Intent(context, MainActivity::class.java).putExtra("open_alerts", true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, key.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("Bourse AI · signal à examiner").setContentText(alert.message.take(160)).setStyle(NotificationCompat.BigTextStyle().bigText(alert.message.take(1000))).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setContentIntent(pending).setAutoCancel(true).build()
        try { manager.notify(key.hashCode(), notification); markSeen(context, key) } catch (_: SecurityException) { /* Permission may have been revoked between the checks. */ }
    }
}

class AlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val config = PhoneConfig(applicationContext).read()
        if (!AlertNotifications.enabled(applicationContext, config) || !AlertNotifications.allowed(applicationContext)) return Result.success()
        return try {
            val rows = alerts(HttpTransport(config, BuildConfig.DEBUG).request("GET", api("portfolios", config.portfolioId!!, "alerts")))
            if (PhoneConfig(applicationContext).read() != config || !AlertNotifications.enabled(applicationContext, config)) return Result.success()
            pendingNotifications(config, rows, AlertNotifications.seen(applicationContext)).forEach { AlertNotifications.notify(applicationContext, config, it) }
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { if (runAttemptCount < 2) Result.retry() else Result.failure() }
    }
}
