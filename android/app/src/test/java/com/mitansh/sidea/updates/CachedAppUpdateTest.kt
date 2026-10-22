package com.mitansh.sidea.updates

import org.junit.Assert.*
import org.junit.Test

class CachedAppUpdateTest {
    private val repository = "Mitanshp5/Spokify"
    private val update = AppUpdate(UpdateManifest("com.mitansh.sidea", 3, "0.3.0", 26, 1234, "a".repeat(64)),
        "https://github.com/$repository/releases/download/v0.3.0/spokify.apk",
        "https://github.com/$repository/releases/latest", "What's new\nSmoother music.")
    private fun read(raw: String = update.encode(), code: Long = 2, sdk: Int = 26) =
        decodeCachedUpdate(raw, repository, "com.mitansh.sidea", code, sdk)

    @Test fun preservesAvailableReleaseAcrossProcessRestarts() { assertEquals(update, read()) }
    @Test fun clearsBadgeOnceAppHasBeenUpdated() {
        assertNull(read(code = 3))
        assertNull(read(code = 4))
    }
    @Test fun ignoresCachedUpdateForOtherAppOrUnsupportedAndroid() {
        assertNull(read(update.copy(manifest = update.manifest.copy(applicationId = "other.app")).encode()))
        assertNull(read(update.copy(manifest = update.manifest.copy(minSdk = 28)).encode()))
    }
    @Test fun rejectsUnexpectedCachedDownloadSource() {
        assertThrows(IllegalArgumentException::class.java) {
            read(update.copy(apkUrl = "https://github.com/other/app/releases/download/v3/app.apk").encode())
        }
    }
}
