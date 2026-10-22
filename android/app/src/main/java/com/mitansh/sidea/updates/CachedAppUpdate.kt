package com.mitansh.sidea.updates

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter

internal fun AppUpdate.encode(): String = JsonWriter.string(JsonObject().apply {
    put("manifest", manifest.json())
    put("apkUrl", apkUrl)
    put("releaseUrl", releaseUrl)
    put("notes", notes)
})

internal fun decodeCachedUpdate(raw: String, repository: String, applicationId: String, installedCode: Long, sdk: Int): AppUpdate? {
    val root = JsonParser.`object`().from(raw)
    val manifest = UpdateManifest.parse(JsonWriter.string(requireNotNull(root.getObject("manifest"))))
    if (manifest.applicationId != applicationId || manifest.versionCode <= installedCode || manifest.minSdk > sdk) return null
    val apk = root.getString("apkUrl").orEmpty()
    validateAssetUrl(repository, apk)
    return AppUpdate(manifest, apk, "https://github.com/$repository/releases/latest", root.getString("notes").orEmpty().take(8_000))
}

private fun UpdateManifest.json() = JsonObject().apply {
    put("schemaVersion", 1)
    put("applicationId", applicationId)
    put("versionCode", versionCode)
    put("versionName", versionName)
    put("minSdk", minSdk)
    put("sizeBytes", sizeBytes)
    put("sha256", sha256)
}
