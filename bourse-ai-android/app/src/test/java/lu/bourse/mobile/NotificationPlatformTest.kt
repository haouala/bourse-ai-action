package lu.bourse.mobile

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import lu.bourse.mobile.data.ConnectionConfig
import lu.bourse.mobile.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationPlatformTest {
    @Test fun actualAndroidNotificationIsPrivateAndRecordedForDeduplication() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val config = ConnectionConfig(false, "https://example.com", 1)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        context.getSharedPreferences("alert-notifications", Context.MODE_PRIVATE).edit().clear().putBoolean("enabled", true).putString("scope", monitoringScope(config)).commit()
        val alert = Alert(1, 1, "LOSS_LIMIT", "Limite à réexaminer", "NEW", "2026-11-19T17:30:00Z", "2026-11-19T17:40:00Z", "test")
        AlertNotifications.notify(context, config, alert)
        val posted = shadowOf(manager).allNotifications.single()
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE, posted.visibility)
        assertNotNull(posted.contentIntent)
        assertTrue(pendingNotifications(config, listOf(alert), AlertNotifications.seen(context)).isEmpty())
    }
    @Test fun globallyDisabledNotificationsRemainBlockedEvenWithRuntimePermission() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        assertFalse(AlertNotifications.allowed(context))
        assertThrows(IllegalArgumentException::class.java) { AlertNotifications.setEnabled(context, ConnectionConfig(false, "https://example.com", 1), true) }
    }
    @Test fun demoAndMissingPortfolioCannotEnableMonitoring() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(AlertNotifications.enabled(context, ConnectionConfig()))
        assertThrows(IllegalArgumentException::class.java) { AlertNotifications.setEnabled(context, ConnectionConfig(), true) }
        assertThrows(IllegalArgumentException::class.java) { AlertNotifications.setEnabled(context, ConnectionConfig(false, "https://example.com", null), true) }
    }
}
