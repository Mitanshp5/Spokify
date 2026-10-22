package com.mitansh.sidea.updates

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class UpdatePolicyTest {
    @get:Rule val temporary = TemporaryFolder()
    private val sha = "a".repeat(64)
    private fun json(field: String = "", replacement: String = "") = """{"schemaVersion":1,"applicationId":"com.mitansh.sidea","versionCode":3,"versionName":"0.3.0","minSdk":26,"sizeBytes":1234,"sha256":"$sha"}"""
        .let { if (field.isEmpty()) it else it.replace(field, replacement) }
    private val manifest get() = UpdateManifest.parse(json())
    private val installed = ApkIdentity("com.mitansh.sidea", 2, 26, setOf("original-key"))
    private val archive = installed.copy(versionCode = 3)
    private fun rejected(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java, block) }

    @Test fun acceptsStrictManifest() {
        val result = manifest
        assertEquals(3L, result.versionCode)
        assertEquals(1234L, result.sizeBytes)
        assertEquals(sha, result.sha256)
    }
    @Test fun requiresKnownSchema() { rejected { UpdateManifest.parse(json("\"schemaVersion\":1", "\"schemaVersion\":2")) } }
    @Test fun rejectsFractionalVersions() { rejected { UpdateManifest.parse(json("\"versionCode\":3", "\"versionCode\":3.1")) } }
    @Test fun rejectsStringVersions() { rejected { UpdateManifest.parse(json("\"versionCode\":3", "\"versionCode\":\"3\"")) } }
    @Test fun rejectsVersionOverflow() { rejected { UpdateManifest.parse(json("\"versionCode\":3", "\"versionCode\":2147483648")) } }
    @Test fun rejectsMissingAndZeroSize() {
        rejected { UpdateManifest.parse(json("\"sizeBytes\":1234,", "")) }
        rejected { UpdateManifest.parse(json("\"sizeBytes\":1234", "\"sizeBytes\":0")) }
    }
    @Test fun rejectsOversizeApk() { rejected { UpdateManifest.parse(json("\"sizeBytes\":1234", "\"sizeBytes\":${MAX_UPDATE_BYTES + 1}")) } }
    @Test fun rejectsInvalidHash() { rejected { UpdateManifest.parse(json(sha, "../not-a-hash")) } }
    @Test fun acceptsMatchingNewerPackageAndSigner() { validateUpdateIdentity(manifest, archive, installed, 26) }
    @Test fun rejectsWrongPackageEvenWithCorrectSigner() {
        rejected { validateUpdateIdentity(manifest, archive.copy(applicationId = "other.app"), installed, 37) }
        rejected { validateUpdateIdentity(manifest.copy(applicationId = "other.app"), archive, installed, 37) }
    }
    @Test fun rejectsSameVersionAndDowngrade() {
        rejected { validateUpdateIdentity(manifest.copy(versionCode = 2), archive.copy(versionCode = 2), installed, 37) }
        rejected { validateUpdateIdentity(manifest.copy(versionCode = 1), archive.copy(versionCode = 1), installed, 37) }
    }
    @Test fun rejectsVersionDifferentFromManifest() { rejected { validateUpdateIdentity(manifest, archive.copy(versionCode = 4), installed, 37) } }
    @Test fun rejectsDifferentOrMissingSigningKey() {
        rejected { validateUpdateIdentity(manifest, archive.copy(signers = setOf("another-key")), installed, 37) }
        rejected { validateUpdateIdentity(manifest, archive.copy(signers = emptySet()), installed, 37) }
    }
    @Test fun rejectsAdditionalSigner() { rejected { validateUpdateIdentity(manifest, archive.copy(signers = setOf("original-key", "other")), installed, 37) } }
    @Test fun rejectsUnsupportedAndroidAndMisreportedMinimum() {
        rejected { validateUpdateIdentity(manifest.copy(minSdk = 37), archive.copy(minSdk = 37), installed, 36) }
        rejected { validateUpdateIdentity(manifest, archive.copy(minSdk = 28), installed, 37) }
    }
    @Test fun acceptsOnlyConfiguredGithubReleaseDownloads() {
        validateAssetUrl("Mitanshp5/Spokify", "https://github.com/Mitanshp5/Spokify/releases/download/v0.3.0/spokify.apk")
        listOf("http://github.com/Mitanshp5/Spokify/releases/download/v3/app.apk",
            "https://github.com/other/repository/releases/download/v3/app.apk",
            "https://github.com.evil.example/Mitanshp5/Spokify/releases/download/v3/app.apk",
            "https://user@github.com/Mitanshp5/Spokify/releases/download/v3/app.apk",
            "https://github.com/Mitanshp5/Spokify/releases/download/../../other/app.apk",
            "https://github.com/Mitanshp5/Spokify/releases/download/v3/app.apk?token=x"
        ).forEach { rejected { validateAssetUrl("Mitanshp5/Spokify", it) } }
    }
    @Test fun acceptsCompleteMatchingDownload() {
        val file = temporary.newFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
        verifyUpdateFile(file, manifest.copy(sizeBytes = 3, sha256 = hash))
    }
    @Test fun rejectsIncompleteAndTamperedDownload() {
        val file = temporary.newFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        rejected { verifyUpdateFile(file, manifest.copy(sizeBytes = 4)) }
        rejected { verifyUpdateFile(file, manifest.copy(sizeBytes = 3)) }
    }
}
