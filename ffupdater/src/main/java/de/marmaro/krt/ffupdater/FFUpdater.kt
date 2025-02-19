package de.marmaro.krt.ffupdater

import android.app.Application
import androidx.annotation.Keep
import androidx.preference.PreferenceManager
import de.marmaro.krt.ffupdater.crash.CrashListener
import de.marmaro.krt.ffupdater.device.PowerUtil
import de.marmaro.krt.ffupdater.device.StorageCleaner
import de.marmaro.krt.ffupdater.network.file.FileDownloader
import de.marmaro.krt.ffupdater.security.StrictModeSetup
import de.marmaro.krt.ffupdater.settings.BackgroundSettings
import de.marmaro.krt.ffupdater.settings.DataStoreHelper
import de.marmaro.krt.ffupdater.settings.DeviceSettingsHelper
import de.marmaro.krt.ffupdater.settings.ForegroundSettings
import de.marmaro.krt.ffupdater.settings.InstallerSettings
import de.marmaro.krt.ffupdater.settings.NetworkSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Keep
class FFUpdater : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashListener.showNotificationForUncaughtException(applicationContext)

        StrictModeSetup.enableStrictMode()

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        BackgroundSettings.init(sharedPreferences)
        DataStoreHelper.init(sharedPreferences)
        DeviceSettingsHelper.init(sharedPreferences)
        ForegroundSettings.init(sharedPreferences)
        InstallerSettings.init(sharedPreferences)
        NetworkSettings.init(sharedPreferences)

        PowerUtil.init(applicationContext)
        FileDownloader.init(applicationContext)
        Migrator.migrate(applicationContext)

        cleanupUnusedApkFiles()
    }

    private fun cleanupUnusedApkFiles() {
        CoroutineScope(Job() + Dispatchers.IO).launch {
            delay(60 * 1000)
            StorageCleaner.deleteApksOfNotInstalledApps(applicationContext)
        }
    }


    companion object {
        const val LOG_TAG = "FFUpdater"
    }
}