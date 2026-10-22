package com.mitansh.sidea.playback

import android.app.AlarmManager
import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.net.Uri
import android.provider.Settings
import android.service.notification.Condition
import android.service.notification.ZenPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class QuietNotificationState(
    val enabled: Boolean, val permitted: Boolean, val active: Boolean,
    val supported: Boolean, val error: String? = null,
)

/** Owns only Spokify's automatic rule; never changes the listener's global DND policy or volumes. */
class QuietPlaybackNotifications private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("quiet-playback", Context.MODE_PRIVATE)
    private val notifications = app.getSystemService(NotificationManager::class.java)
    private val alarms = app.getSystemService(AlarmManager::class.java)
    private val supported get() = Build.VERSION.SDK_INT >= 29
    private val permitted get() = supported && notifications.isNotificationPolicyAccessGranted
    private var playing = false
    private var active = false
    private var lastHeartbeat = 0L
    private val session = QuietNotificationSession(::applyRule)
    private val mutableState = MutableStateFlow(readState())
    val state = mutableState.asStateFlow()

    private fun readState(error: String? = null) = QuietNotificationState(
        prefs.getBoolean("enabled", true), permitted, active, supported, error)

    init {
        // A new process starts with no audible player. Clear a rule left by an interrupted process.
        refresh()
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("enabled", enabled).apply()
        if (enabled && permitted) {
            // Only an explicit in-app choice may re-enable a rule disabled in Android settings.
            try {
                if (Build.VERSION.SDK_INT >= 29) prefs.getString("rule-id", null)?.let { id ->
                    notifications.getAutomaticZenRule(id)?.takeIf { !it.isEnabled }?.let { rule ->
                        rule.isEnabled = true
                        notifications.updateAutomaticZenRule(id, rule)
                    }
                }
            } catch (_: Exception) {
                mutableState.value = readState("Couldn't enable Spokify playback mode in Android settings.")
                return
            }
        }
        refresh()
    }

    fun refreshAccess() {
        if (permitted && Build.VERSION.SDK_INT >= 29) {
            try {
                prefs.getString("rule-id", null)?.let { id ->
                    notifications.getAutomaticZenRule(id)?.takeIf { !it.isEnabled }?.let {
                        prefs.edit().putBoolean("enabled", false).apply()
                    }
                }
            } catch (_: Exception) { /* refresh below reports permission/update failures */ }
        }
        refresh()
    }

    fun setPlaybackActive(value: Boolean) {
        playing = value
        refresh()
    }

    fun refresh() {
        try {
            session.update(playing, prefs.getBoolean("enabled", true), permitted)
            if (!permitted) active = false
            mutableState.value = readState()
        } catch (_: Exception) {
            mutableState.value = readState("Android couldn't update notification control. Try again in Listening preferences.")
        }
    }

    /** A best-effort lease also clears stale quiet mode after an unexpected process exit. */
    fun heartbeat() {
        refresh()
        if (!active) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastHeartbeat >= 30_000) {
            lastHeartbeat = now
            scheduleExpiry(now + 120_000)
        }
    }

    fun requestAccess() {
        try {
            app.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            mutableState.value = readState("Open Android Settings and allow Spokify access to Do Not Disturb.")
        }
    }

    private fun expiryIntent() = PendingIntent.getBroadcast(app, 0,
        Intent(app, QuietPlaybackExpiryReceiver::class.java).setAction(EXPIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun scheduleExpiry(deadline: Long) {
        prefs.edit().putLong("lease-until", deadline).apply()
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, expiryIntent())
    }

    fun expire() {
        val deadline = prefs.getLong("lease-until", 0)
        if (playing && SystemClock.elapsedRealtime() < deadline) scheduleExpiry(deadline)
        else setPlaybackActive(false)
    }

    @androidx.annotation.RequiresApi(29)
    private fun policy(): ZenPolicy {
        val builder = ZenPolicy.Builder().allowAlarms(true).allowMedia(true).allowSystem(true)
            .allowCalls(ZenPolicy.PEOPLE_TYPE_ANYONE).allowRepeatCallers(true)
            .allowMessages(ZenPolicy.PEOPLE_TYPE_NONE).allowEvents(false).allowReminders(false)
            .showAllVisualEffects()
        if (Build.VERSION.SDK_INT >= 30) builder.allowConversations(ZenPolicy.CONVERSATION_SENDERS_NONE)
        if (Build.VERSION.SDK_INT >= 35) builder.allowPriorityChannels(false)
        return builder.build()
    }

    private fun applyRule(quiet: Boolean) {
        if (Build.VERSION.SDK_INT < 29) return
        var id = prefs.getString("rule-id", null)
        var rule = id?.let { notifications.getAutomaticZenRule(it) }
        if (rule == null && quiet) {
            // Android retains ownership and combines this with existing rules; those rules stay intact.
            rule = AutomaticZenRule("Spokify playback", null,
                ComponentName(app, QuietNotificationSettingsActivity::class.java), conditionId,
                policy(), NotificationManager.INTERRUPTION_FILTER_PRIORITY, true)
            id = notifications.addAutomaticZenRule(rule)
            check(!id.isNullOrEmpty())
            prefs.edit().putString("rule-id", id).apply()
        }
        if (id != null && rule != null) {
            notifications.setAutomaticZenRuleState(id, Condition(conditionId, "Spokify is playing music",
                if (quiet) Condition.STATE_TRUE else Condition.STATE_FALSE))
        }
        active = quiet && rule?.isEnabled == true
        if (active) {
            lastHeartbeat = SystemClock.elapsedRealtime()
            scheduleExpiry(lastHeartbeat + 120_000)
        } else {
            alarms.cancel(expiryIntent())
            prefs.edit().remove("lease-until").apply()
        }
    }

    companion object {
        private const val EXPIRE = "com.mitansh.sidea.QUIET_PLAYBACK_EXPIRED"
        private val conditionId = Uri.parse("condition://com.mitansh.sidea/playing-music")
        @Volatile private var instance: QuietPlaybackNotifications? = null
        fun get(context: Context): QuietPlaybackNotifications = instance ?: synchronized(this) {
            instance ?: QuietPlaybackNotifications(context).also { instance = it }
        }
    }
}

/** Receives only explicit expiry intents and system restart/update cleanup broadcasts. */
class QuietPlaybackExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val quiet = QuietPlaybackNotifications.get(context)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            quiet.setPlaybackActive(false)
        } else quiet.expire()
    }
}
