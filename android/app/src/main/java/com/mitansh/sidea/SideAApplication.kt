package com.mitansh.sidea

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.mitansh.sidea.ui.OfflineArtworkInterceptor
import com.mitansh.sidea.data.youtube.YouTubeCatalog

class SideAApplication : Application(), SingletonImageLoader.Factory {
    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .components { add(OfflineArtworkInterceptor(context.applicationContext)) }
        .build()

    override fun onCreate() {
        super.onCreate()
        val migrations = getSharedPreferences("app-migrations", MODE_PRIVATE)
        if (!migrations.getBoolean("removed-spotify-login", false)) {
            val removed = runCatching {
                val old = java.io.File(noBackupFilesDir, "spotify-credential.bin")
                check(!old.exists() || old.delete())
                java.security.KeyStore.getInstance("AndroidKeyStore").apply {
                    load(null)
                    deleteEntry("sidea.spotify.credentials")
                }
            }.isSuccess
            if (removed) migrations.edit().putBoolean("removed-spotify-login", true).apply()
        }
        com.mitansh.sidea.playback.QuietPlaybackNotifications.get(this)
        YouTubeCatalog.initialize()
    }
}
