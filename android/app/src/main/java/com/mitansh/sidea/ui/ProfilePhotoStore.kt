package com.mitansh.sidea.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Keep a private copy so the avatar survives picker permission expiry and restarts. */
class ProfilePhotoStore(private val context: Context) {
    private val directory get() = File(context.filesDir, "profile-photos")

    fun file(name: String?): File? = name?.takeIf { Regex("[a-f0-9-]+\\.image").matches(it) }
        ?.let { File(directory, it) }?.takeIf(File::isFile)

    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        check(directory.isDirectory || directory.mkdirs()) { "Couldn't save photo. Try again." }
        val target = File(directory, "${UUID.randomUUID()}.image")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytes = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        bytes += count
                        require(bytes <= 20L * 1024 * 1024) { "Choose a photo smaller than 20 MB." }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("Couldn't open this photo. Try another image.")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Couldn't read this photo. Try another image." }
            target.name
        } catch (failure: Exception) {
            target.delete()
            throw failure
        }
    }

    suspend fun remove(name: String?) = withContext(Dispatchers.IO) { file(name)?.delete(); Unit }
}
