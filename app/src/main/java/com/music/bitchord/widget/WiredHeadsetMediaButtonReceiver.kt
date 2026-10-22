package com.music.bitchord.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.util.Log

/**
 * Custom media button receiver that handles wired headset multi-click gestures.
 *
 * Wired headsets (e.g. KZ Duonic) send individual MEDIA_BUTTON intents for each
 * click. Android's built-in MediaButtonReceiver has a short internal debounce
 * window (~500 ms), so a triple-click often fires the double-click action before
 * the third click arrives. This receiver extends the detection window to 800 ms,
 * correctly distinguishing single / double / triple clicks.
 *
 * The resolved click count is stored in the application's shared preferences so
 * that PlaybackService (or any other component) can read it and act accordingly.
 */
class WiredHeadsetMediaButtonReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WiredHeadsetMBR"

        /** Shared-preference key for the last detected click count. */
        const val PREF_KEY_CLICK_COUNT = "wired_headset_click_count"

        /** How long to wait after the first click before deciding how many clicks occurred.
         * 800 ms is generous enough for a deliberate triple-click (typically ~250 ms between
         * presses → ~500 ms total) while still feeling responsive for single / double taps. */
        private const val CLICK_DETECTION_WINDOW_MS = 800L

        /** Maximum number of clicks we track. */
        private const val MAX_CLICKS = 3
    }

    // Per-context state protected by a lock to avoid race conditions across rapid broadcasts.
    private val lock = Any()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return

        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            ?: return

        // Only down-events matter; up-events are noise for our counter.
        if (event.action != KeyEvent.ACTION_DOWN) return

        val keyCode = event.keyCode
        if (keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE &&
            keyCode != KeyEvent.KEYCODE_HEADSETHOOK &&
            keyCode != KeyEvent.KEYCODE_MEDIA_NEXT &&
            keyCode != KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            return
        }

        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences("headset_clicks", Context.MODE_PRIVATE)

        synchronized(lock) {
            // Read the current click state.
            val lastClickTime = prefs.getLong("last_click_time", 0)
            var clickCount = prefs.getInt("click_count", 0)

            val now = SystemClock.elapsedRealtime()

            if (now - lastClickTime > CLICK_DETECTION_WINDOW_MS || clickCount == 0) {
                // Window expired or first click — start fresh.
                clickCount = 1
            } else {
                clickCount = (clickCount + 1).coerceAtMost(MAX_CLICKS)
            }

            prefs.edit()
                .putLong("last_click_time", now)
                .putInt("click_count", clickCount)
                .apply()

            // Schedule a timeout to fire the resolved click count.
            Handler(Looper.getMainLooper()).postDelayed({
                synchronized(lock) {
                    val resolved = prefs.getInt(PREF_KEY_CLICK_COUNT, 0)
                    if (resolved > 0) {
                        Log.d(TAG, "Resolved $resolved-click gesture")
                        // Store the final count so PlaybackService can read it.
                        prefs.edit().putInt(PREF_KEY_CLICK_COUNT, resolved).apply()

                        // Forward to PlaybackService via a custom intent action.
                        val forwardIntent = Intent(context, WiredHeadsetMediaButtonReceiver::class.java)
                            .setAction("com.music.bitchord.WIRED_HEADSET_CLICK")
                            .putExtra("click_count", resolved)
                        context.sendBroadcast(forwardIntent)
                    }
                }
            }, CLICK_DETECTION_WINDOW_MS)
        }

        // Consume the event so Android's default MediaButtonReceiver doesn't also fire.
        abortBroadcast()
    }
}
