package com.mitansh.sidea.data.radio

import android.content.Context

/**
 * The user's radio preferences.
 *
 * These are the choices that change what the radio proposes, kept apart from the listening
 * statistics so they can be changed or reset without touching the history. The storage is a plain
 * key-value pair, so the rules can be checked without Android.
 */
class RadioPreferences(private val storage: Storage) {

    /** The small amount of storage the preferences need. */
    interface Storage {
        fun getBoolean(key: String, fallback: Boolean): Boolean
        fun putBoolean(key: String, value: Boolean)
        fun getString(key: String): String
        fun putString(key: String, value: String)
        fun clear()
    }

    companion object {
        private const val NAME = "radio-preferences"
        const val KEY_AUTOPLAY = "autoplay-enabled"
        const val KEY_HIDDEN = "hidden-tracks"

        /** The preferences backed by the app's shared preferences. */
        fun of(context: Context): RadioPreferences {
            val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            return RadioPreferences(object : Storage {
                override fun getBoolean(key: String, fallback: Boolean) = prefs.getBoolean(key, fallback)
                override fun putBoolean(key: String, value: Boolean) {
                    prefs.edit().putBoolean(key, value).apply()
                }
                override fun getString(key: String) = prefs.getString(key, "").orEmpty()
                override fun putString(key: String, value: String) {
                    prefs.edit().putString(key, value).apply()
                }
                override fun clear() {
                    prefs.edit().clear().apply()
                }
            })
        }
    }

    /**
     * Whether the radio keeps filling the queue on its own.
     *
     * With this off, the radio only runs when the user starts it, and the queue is left alone
     * otherwise. With it on, a short queue is topped up from the seed.
     */
    var autoplayEnabled: Boolean
        get() = storage.getBoolean(KEY_AUTOPLAY, true)
        set(value) = storage.putBoolean(KEY_AUTOPLAY, value)

    /**
     * The songs the user never wants to hear again.
     *
     * These are excluded from every radio batch, and they are kept by their durable id so the
     * exclusion survives a re-read of the same song under a different upload.
     */
    fun hiddenTracks(): Set<String> =
        storage.getString(KEY_HIDDEN).split('\n').filter { it.isNotBlank() }.toSet()

    /** Hides a song from the radio. Hiding it twice is not an error. */
    fun hide(trackId: String) {
        if (trackId.isBlank()) return
        val hidden = hiddenTracks().toMutableSet()
        hidden += trackId
        storage.putString(KEY_HIDDEN, hidden.joinToString("\n"))
    }

    /** Brings a hidden song back into the radio. */
    fun unhide(trackId: String) {
        val hidden = hiddenTracks().toMutableSet()
        hidden -= trackId
        storage.putString(KEY_HIDDEN, hidden.joinToString("\n"))
    }

    fun isHidden(trackId: String): Boolean = trackId in hiddenTracks()

    /** Forgets every radio preference, so the radio starts from the listening statistics again. */
    fun reset() {
        storage.clear()
    }
}
