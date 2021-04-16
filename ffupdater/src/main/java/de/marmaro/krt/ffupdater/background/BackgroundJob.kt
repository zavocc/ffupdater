package de.marmaro.krt.ffupdater.background

import android.content.Context
import android.util.Log
import androidx.work.*
import androidx.work.ExistingPeriodicWorkPolicy.REPLACE
import de.marmaro.krt.ffupdater.R
import de.marmaro.krt.ffupdater.app.App
import de.marmaro.krt.ffupdater.app.impl.exceptions.ApiNetworkException
import de.marmaro.krt.ffupdater.device.DeviceEnvironment
import de.marmaro.krt.ffupdater.download.DownloadManagerAdapter
import de.marmaro.krt.ffupdater.download.DownloadManagerAdapter.DownloadStatus.Status.FAILED
import de.marmaro.krt.ffupdater.download.DownloadManagerAdapter.DownloadStatus.Status.SUCCESSFUL
import de.marmaro.krt.ffupdater.download.DownloadedApkCache
import de.marmaro.krt.ffupdater.download.NetworkTester
import de.marmaro.krt.ffupdater.download.StorageTester
import de.marmaro.krt.ffupdater.settings.PreferencesHelper
import de.marmaro.krt.ffupdater.settings.SettingsHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit.MINUTES

/**
 * This class will call the [WorkManager] to check regularly for app updates in the background.
 * When an app update is available, a notification will be displayed.
 *
 * doWork can be interrupted at any time and cause a CancellationException.
 */
class BackgroundJob(
        context: Context,
        workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {
    val deviceEnvironment = DeviceEnvironment()

    override suspend fun doWork(): Result {
        try {
            waitForInternet()
            val appsWithUpdates = findAppsWithUpdates()
            waitForUnmeteredNetwork()
            downloadUpdatesInBackground(appsWithUpdates)
            showUpdateNotification(appsWithUpdates)
            PreferencesHelper(applicationContext).lastBackgroundCheck = LocalDateTime.now()
            Result.success()
        } catch (e: CancellationException) {
            // when the network is disabled, this exception will be thrown -> ignore it
        } catch (e: ApiNetworkException) {
            val message = applicationContext.getString(R.string.background_network_issue_notification__text)
            ErrorNotificationBuilder.showNotification(applicationContext, e, message)
        } catch (e: Exception) {
            val message = applicationContext.getString(R.string.background_unknown_bug_notification__text)
            ErrorNotificationBuilder.showNotification(applicationContext, e, message)
        }
        // always return success() because failure() will remove the scheduled job
        return Result.success()
    }

    /**
     * Wait until Internet is available. Abort after 10 seconds.
     */
    private suspend fun waitForInternet() {
        repeat(10) {
            if (!NetworkTester.isInternetUnavailable(applicationContext)) {
                return
            }
            delay(1000)
        }
    }

    /**
     * Wait until the current network is unmetered. Abort after 60 seconds.
     * This is necessary because the background download will only work on unmetered networks.
     * If internet becomes unavailable, the CoroutineWorker will automatically be stopped
     */
    private suspend fun waitForUnmeteredNetwork() {
        repeat(60) {
            if (NetworkTester.isActiveNetworkUnmetered(applicationContext)) {
                return
            }
            delay(1000)
        }
    }

    /**
     * Returns apps which:
     *  - are installed
     *  - are not disabled (in the settings "excluded applications")
     *  - have an available update
     * @throws InvalidApiResponseException
     * @throws ApiNetworkException
     * @throws CancellationException
     */
    private suspend fun findAppsWithUpdates(): List<App> {
        val disabledApps = SettingsHelper(applicationContext).disabledApps
        return App.values()
                .filter { it !in disabledApps }
                .filter { it.detail.isInstalled(applicationContext) }
                // nice side effect: check for updates by calling updateCheck()
                .filter { it.detail.updateCheck(applicationContext, deviceEnvironment).isUpdateAvailable }
    }

    /**
     * If the current network is unmetered, then download the update for the given apps
     * with the DownloadManager in the background.
     */
    private suspend fun downloadUpdatesInBackground(appsWithUpdates: List<App>) {
        if (NetworkTester.isActiveNetworkUnmetered(applicationContext)) {
            val downloadManager = DownloadManagerAdapter.create(applicationContext)
            appsWithUpdates.forEach { downloadUpdateInBackground(it, downloadManager) }
        }
    }

    /**
     * Start the download of an app update and wait until the download is finished.
     * Preconditions for the background download:
     *  - enough memory
     *  - update must not be already downloaded
     */
    private suspend fun downloadUpdateInBackground(app: App, downloadManager: DownloadManagerAdapter) {
        val apkCache = DownloadedApkCache(app, applicationContext)
        val cachedUpdateChecker = app.detail.updateCheck(applicationContext, deviceEnvironment)
        val availableResult = cachedUpdateChecker.availableResult
        Log.e("tobias", "downloadUpdateInBackground " + app + " " + apkCache.isCacheAvailable(availableResult))
        if (apkCache.isCacheAvailable(availableResult) ||
                !StorageTester.isEnoughStorageAvailable(applicationContext)) {
            return
        }
        Log.e("tobias", "download: " + app)

        val fileReservation = downloadManager.reserveFile(app, applicationContext)
        val downloadId = downloadManager.enqueue(applicationContext, app, availableResult, fileReservation)
        repeat(5 * 60) {
            when (downloadManager.getStatusAndProgress(downloadId).status) {
                SUCCESSFUL -> {
                    apkCache.copyFileToCache(fileReservation.downloadLocation)
                    return
                }
                FAILED -> {
                    downloadManager.remove(downloadId)
                    return
                }
                else -> delay(1000)
            }
        }
        downloadManager.remove(downloadId)
    }

    private fun showUpdateNotification(appsWithUpdates: List<App>) {
        UpdateNotificationBuilder.showNotifications(appsWithUpdates, applicationContext)
    }

    companion object {
        private const val WORK_MANAGER_KEY: String = "update_checker"

        fun startOrStopBackgroundUpdateCheck(context: Context) {
            if (SettingsHelper(context).automaticCheck) {
                startBackgroundUpdateCheck(context)
            } else {
                stopBackgroundUpdateCheck(context)
            }
        }

        private fun startBackgroundUpdateCheck(context: Context) {
            val settingsHelper = SettingsHelper(context)
            val repeatInterval = settingsHelper.checkInterval
            val onlyUnmetered = settingsHelper.onlyUnmeteredNetwork

            val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .also { if (onlyUnmetered) it.setRequiredNetworkType(NetworkType.UNMETERED) }
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build()
            val saveRequest = PeriodicWorkRequest.Builder(
                    BackgroundJob::class.java, repeatInterval.toMinutes(), MINUTES)
                    .setConstraints(constraints)
                    .build()

            val workManager = WorkManager.getInstance(context)
            workManager.enqueueUniquePeriodicWork(WORK_MANAGER_KEY, REPLACE, saveRequest)
        }

        private fun stopBackgroundUpdateCheck(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_MANAGER_KEY)
        }
    }
}