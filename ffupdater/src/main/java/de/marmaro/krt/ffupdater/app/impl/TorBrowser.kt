package de.marmaro.krt.ffupdater.app.impl

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.Keep
import androidx.annotation.MainThread
import de.marmaro.krt.ffupdater.R
import de.marmaro.krt.ffupdater.app.App
import de.marmaro.krt.ffupdater.app.entity.DisplayCategory.BASED_ON_FIREFOX
import de.marmaro.krt.ffupdater.app.entity.DisplayCategory.GOOD_PRIVACY_BROWSER
import de.marmaro.krt.ffupdater.app.entity.DisplayCategory.GOOD_SECURITY_BROWSER
import de.marmaro.krt.ffupdater.app.entity.LatestVersion
import de.marmaro.krt.ffupdater.device.ABI
import de.marmaro.krt.ffupdater.device.DeviceAbiExtractor
import de.marmaro.krt.ffupdater.network.exceptions.NetworkException
import de.marmaro.krt.ffupdater.network.file.CacheBehaviour
import de.marmaro.krt.ffupdater.network.file.FileDownloader
import de.marmaro.krt.ffupdater.settings.DeviceSettingsHelper

/**
 * https://www.torproject.org/download/#android
 * https://www.apkmirror.com/apk/the-tor-project/tor-browser/
 * https://dist.torproject.org/torbrowser/
 */
@Keep
object TorBrowser : AppBase() {
    override val app = App.TOR_BROWSER
    override val packageName = "org.torproject.torbrowser"
    override val title = R.string.tor_browser__title
    override val description = R.string.tor_browser__description
    override val installationWarning: Int? = null
    override val downloadSource = "https://dist.torproject.org/torbrowser"
    override val icon = R.drawable.ic_logo_tor_browser
    override val minApiLevel = Build.VERSION_CODES.LOLLIPOP
    override val supportedAbis = ARM32_ARM64_X86_X64

    @Suppress("SpellCheckingInspection")
    override val signatureHash = "20061f045e737c67375c17794cfedb436a03cec6bacb7cb9f96642205ca2cec8"
    override val projectPage = "https://www.torproject.org/download/#android"
    override val displayCategory = listOf(BASED_ON_FIREFOX, GOOD_PRIVACY_BROWSER, GOOD_SECURITY_BROWSER)

    override suspend fun getInstalledVersion(packageManager: PackageManager): String? {
        val rawVersion = super.getInstalledVersion(packageManager) ?: return null
        return rawVersion.split(" ").last()
            .removePrefix("(")
            .removeSuffix(")")
    }

    @MainThread
    @Throws(NetworkException::class)
    override suspend fun fetchLatestUpdate(context: Context, cacheBehaviour: CacheBehaviour): LatestVersion {
        val version = findLatestVersion(cacheBehaviour)
        val downloadUrl = getDownloadUrl(version)
        return LatestVersion(
            downloadUrl = downloadUrl,
            version = version,
            publishDate = findDateTime(version, cacheBehaviour),
            exactFileSizeBytesOfDownload = null,
            fileHash = null,
        )
    }

    private suspend fun findLatestVersion(cacheBehaviour: CacheBehaviour): String {
        val content = FileDownloader.downloadStringWithCache("$MAIN_BASE_URL/", cacheBehaviour)
        val pattern = Regex.escape("<a href=\"") +
                VERSION_PATTERN +
                Regex.escape("/\">") +
                VERSION_PATTERN +
                Regex.escape("/</a>")

        val match = Regex(pattern).find(content)
        checkNotNull(match) { "Can't find latest version regex pattern '$pattern'." }

        val version = match.groups[1]?.value
        checkNotNull(version) { "Can't extract latest version from regex match." }
        check(version == match.groups[2]?.value) { "Extract different versions." }

        return version
    }

    private fun getDownloadUrl(version: String): String {
        val abi = getAbiString()
        return "$MAIN_BASE_URL/$version/tor-browser-android-$abi-$version.apk"
    }

    @Throws(IllegalStateException::class)
    private suspend fun findDateTime(version: String, cacheBehaviour: CacheBehaviour): String {
        val abi = getAbiString()
        val fileName = "tor-browser-android-$abi-$version.apk"
        val url = "$MAIN_BASE_URL/$version/?P=$fileName"
        val content = FileDownloader.downloadStringWithCache(url, cacheBehaviour)
        check(content.contains(fileName)) { "$fileName is not available on $url" }

        val spaces = """\s+"""
        val pattern = Regex.escape("</a>") +
                spaces +
                """(\d{4}-\d{1,2}-\d{1,2}) """ + //for example 2022-12-16
                """(\d{1,2}:\d{1,2})""" + //for example 13:30
                spaces +
                """((\d){2,3})M""" + //for 82M
                spaces +
                """\n"""
        val match = Regex(pattern).find(content)
        checkNotNull(match) {
            "Can't extract creation date from website: $url\n" +
                    "with regex pattern: $pattern\n" +
                    "content: " + content.lines().joinToString("")
        }
        val date = match.groups[1]
        checkNotNull(date) { "Can't extract date from regex match." }
        val time = match.groups[2]
        checkNotNull(time) { "Can't extract time from regex match." }

        return "${date.value}T${time.value}:00Z"
    }

    private fun getAbiString(): String {
        return when (DeviceAbiExtractor.findBestAbi(supportedAbis, DeviceSettingsHelper.prefer32BitApks)) {
            ABI.ARM64_V8A -> "aarch64"
            ABI.ARMEABI_V7A -> "armv7"
            ABI.X86_64 -> "x86_64"
            ABI.X86 -> "x86"
            else -> throw IllegalArgumentException("ABI is not supported")
        }
    }

    private const val MAIN_BASE_URL = "https://dist.torproject.org/torbrowser"
    private const val VERSION_PATTERN = "([\\d\\.]+)"
}