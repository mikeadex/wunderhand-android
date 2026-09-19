package com.wunderhand.app.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.wunderhand.design.R
import com.wunderhand.app.app.MainActivity
import com.wunderhand.core.DeepLink
import com.wunderhand.core.PushPayload

/**
 * Drawing a notification from what chairtime sent.
 *
 * chairtime sends data only, never a ready-made notification, so that the app
 * can choose the channel — Bookings or Cancellations, each silenced on its own
 * in the phone's settings — and offer "Offer the gap" on a cancellation.
 *
 * The words are chairtime's, written for whoever is reading (no price for a
 * non-owner). On a lock screen set to hide sensitive content the phone shows
 * only that there is news — not a client's name.
 */
/** What a screen needs of push: where to send this phone's token, and whether to ask. Null where nothing provides it. */
class PushServices(val coordinator: PushCoordinator, val notifier: PushNotifier)

val LocalPush = staticCompositionLocalOf<PushServices?> { null }

class PushNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    /** The permission, and the switch in the phone's settings. Before Android 13 there is nothing to ask. */
    fun mayNotify(): Boolean {
        val granted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && manager.areNotificationsEnabled()
    }

    /**
     * Asked once. Somebody who said no meant it; the way back is the phone's
     * own settings, and asking again at every launch is how an app gets removed.
     */
    var hasAsked: Boolean
        get() = context.getSharedPreferences("push", Context.MODE_PRIVATE).getBoolean("asked", false)
        set(value) { context.getSharedPreferences("push", Context.MODE_PRIVATE).edit().putBoolean("asked", value).apply() }

    /** Whether there is a question to put: Android 13 and later, not yet answered, not yet asked. */
    val shouldAsk: Boolean
        get() = Build.VERSION.SDK_INT >= 33 && !hasAsked && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    /** Made once and safe to make again; a channel somebody has silenced stays silenced. */
    fun ensureChannels() {
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        for (channel in PushPayload.Channel.entries) {
            // Heads-up for both: a booking mid-haircut and a time come free are each worth a glance.
            system.createNotificationChannel(NotificationChannel(channel.id, channel.title, NotificationManager.IMPORTANCE_HIGH).apply { description = channel.about })
        }
    }

    fun show(push: PushPayload) {
        if (!mayNotify()) return
        ensureChannels()
        val id = push.notificationId
        val builder = NotificationCompat.Builder(context, push.channel.id)
            .setSmallIcon(R.drawable.ic_mark)
            .setColor(0xFFB03A24.toInt())
            .setContentTitle(push.title)
            .setContentText(push.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(push.body))
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setGroup(push.group)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            // What a locked phone set to hide sensitive content shows instead: that there is news, and no more.
            .setPublicVersion(NotificationCompat.Builder(context, push.channel.id).setSmallIcon(R.drawable.ic_mark).setContentTitle(push.title).setContentText("Open Wunderhand to see it.").build())
            .setContentIntent(opening(push.open, id))
        // The gap screen is where the offering happens, and who it goes to is the shop's to choose: the button opens it, it sends nothing.
        push.gap?.let { builder.addAction(0, "Offer the gap", opening(it, id + 1, dismissing = id)) }
        runCatching { manager.notify(id, builder.build()) }
    }

    /** Into the one activity, by name — nothing else on the phone can be handed this. With no link it simply opens the app. */
    private fun opening(link: DeepLink?, requestCode: Int, dismissing: Int? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).setAction(ACTION_OPEN).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (link != null) intent.putStringArrayListExtra(EXTRA_LINK, link.packed())
        // A tap on the notification clears it; a press of its button does not, unless told to.
        if (dismissing != null) intent.putExtra(EXTRA_DISMISS, dismissing)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** The button on a notification was pressed: the notification has done its job. */
    fun dismissFor(intent: Intent?) {
        if (intent?.action == ACTION_OPEN && intent.hasExtra(EXTRA_DISMISS)) manager.cancel(intent.getIntExtra(EXTRA_DISMISS, 0))
    }

    /** Signing out: a shop's news does not stay on the screen of a phone whoever it was for has left. */
    fun clear() = manager.cancelAll()

    companion object {
        const val ACTION_OPEN = "com.wunderhand.app.OPEN"
        const val EXTRA_LINK = "com.wunderhand.app.link"
        const val EXTRA_DISMISS = "com.wunderhand.app.dismiss"

        /**
         * Where an intent points, if anywhere: a tapped notification's extras,
         * or a link's address. Either is somebody else's text — the activity
         * is exported, as any launcher activity is — so both go through the
         * same checks: an id is a UUID or it is not a link.
         */
        fun linkIn(intent: Intent?): DeepLink? {
            intent ?: return null
            return DeepLink.unpack(runCatching { intent.getStringArrayListExtra(EXTRA_LINK) }.getOrNull())
                ?: DeepLink.from(intent.takeIf { it.action == Intent.ACTION_VIEW }?.dataString)
        }
    }
}
