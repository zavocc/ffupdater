package de.marmaro.krt.ffupdater.app

import java.net.URL
import java.time.ZonedDateTime

data class UpdateCheckSubResult(
        val downloadUrl: URL,
        val version: String,
        val displayVersion: String,
        val publishDate: ZonedDateTime,
        val fileHashSha256: String?,
        val fileSizeBytes: Long?,
) {
    fun convertToUpdateCheckResult(updateAvailable: Boolean): UpdateCheckResult {
        return UpdateCheckResult(
                isUpdateAvailable = updateAvailable,
                downloadUrl = downloadUrl,
                version = version,
                displayVersion = displayVersion,
                publishDate = publishDate,
                fileHashSha256 = fileHashSha256,
                fileSizeBytes = fileSizeBytes
        )
    }
}