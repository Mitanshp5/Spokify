package com.mitansh.sidea.data.offline

import java.security.MessageDigest

/** Preserve existing SHA-256 filenames without allocating a Formatter for each byte. */
internal fun offlineArtworkKey(url: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    return CharArray(digest.size * 2).also { chars ->
        digest.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            chars[index * 2] = hex[value ushr 4]
            chars[index * 2 + 1] = hex[value and 15]
        }
    }.concatToString()
}
