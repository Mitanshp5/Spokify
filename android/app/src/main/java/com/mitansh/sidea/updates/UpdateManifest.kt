package com.mitansh.sidea.updates

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import java.io.File
import java.security.MessageDigest

internal const val MAX_UPDATE_BYTES = 128L * 1024 * 1024

internal data class UpdateManifest(
    val applicationId: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val sizeBytes: Long,
    val sha256: String,
) {
    companion object {
        fun parse(text: String): UpdateManifest {
            val json = JsonParser.`object`().from(text)
            require(json.integer("schemaVersion") == 1L) { "Unsupported update format." }
            val version = json.integer("versionCode")
            val sdk = json.integer("minSdk")
            val size = json.integer("sizeBytes")
            val name = json.getString("versionName").orEmpty()
            val id = json.getString("applicationId").orEmpty()
            val hash = json.getString("sha256").orEmpty().lowercase()
            require(version in 1..2_100_000_000L && sdk in 26..10_000L) { "Invalid update version." }
            require(size in 1..MAX_UPDATE_BYTES) { "Invalid update size." }
            require(name.isNotBlank() && name.length <= 80 && id.isNotBlank()) { "Invalid update details." }
            require(hash.matches(Regex("[a-f0-9]{64}"))) { "Invalid update checksum." }
            return UpdateManifest(id, version, name, sdk.toInt(), size, hash)
        }
    }
}

private fun JsonObject.integer(key: String): Long {
    val number = get(key)
    require(number is Int || number is Long) { "Invalid update field: $key." }
    return (number as Number).toLong()
}

internal data class ApkIdentity(val applicationId: String, val versionCode: Long, val minSdk: Int, val signers: Set<String>)

internal fun validateUpdateIdentity(manifest: UpdateManifest, archive: ApkIdentity, installed: ApkIdentity, sdk: Int) {
    require(manifest.applicationId == installed.applicationId && archive.applicationId == installed.applicationId) { "This update belongs to a different app." }
    require(manifest.versionCode > installed.versionCode && archive.versionCode == manifest.versionCode) { "This update is not newer than the installed app." }
    require(manifest.minSdk == archive.minSdk && archive.minSdk <= sdk) { "This update requires a newer Android version." }
    require(archive.signers.isNotEmpty() && archive.signers == installed.signers) { "This update has a different signing key." }
}

internal fun verifyUpdateFile(file: File, manifest: UpdateManifest) {
    require(file.isFile && file.length() == manifest.sizeBytes) { "The update download is incomplete. Try again." }
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    require(actual == manifest.sha256) { "The update checksum did not match. Download it again." }
}
