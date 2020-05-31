package de.marmaro.krt.ffupdater.version;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.google.common.base.Preconditions;

import java.util.concurrent.TimeUnit;

import de.marmaro.krt.ffupdater.App;

/**
 * This class uses the SharedPreferences API to cache metadata about apps. These metadata includes:
 * - latest available version name
 * - latest available download url
 * - timestamp of the last "latest version name / latest download url" check
 *
 * By caching these metadata, I can:
 * - speed up the UI
 * - reduce network bandwidth
 * - prevent of being temporary blocked by GitHub (for an hour).
 */
class MetadataCache {
    private static final String VERSION_NAME_TEMPLATE = "download_metadata_%1$s_version_name";
    private static final String DOWNLOAD_URL_TEMPLATE = "download_metadata_%1$s_download_url";
    private static final String TIMESTAMP_TEMPLATE = "download_metadata_%1$s_timestamp";
    private static final long CACHE_TTL = TimeUnit.MINUTES.toMillis(10);

    private final SharedPreferences sharedPreferences;

    MetadataCache(SharedPreferences sharedPreferences) {
        this.sharedPreferences = sharedPreferences;
    }

    /**
     * @param app app
     * @return (cached) latest version name or empty string
     */
    @NonNull
    String getVersionName(App app) {
        return sharedPreferences.getString(String.format(VERSION_NAME_TEMPLATE, getName(app)), "");
    }

    /**
     * @param app app
     * @return (cached) latest download url or empty string
     */
    @NonNull
    String getDownloadUrl(App app) {
        return sharedPreferences.getString(String.format(DOWNLOAD_URL_TEMPLATE, getName(app)), "");
    }

    /**
     *
     * @param app app
     * @return is the cache entry for the app too old and must be renewed?
     */
    boolean isTimestampTooOld(App app) {
        return System.currentTimeMillis() - getTimestamp(app) > CACHE_TTL;
    }

    /**
     * @param app app
     * @return timestamp of the cache entry or -1
     */
    private long getTimestamp(App app) {
        return sharedPreferences.getLong(String.format(TIMESTAMP_TEMPLATE, getName(app)), -1);
    }

    /**
     * Sets over override the cache entry for the app.
     * @param app app
     * @param versionName version name
     * @param downloadUrl download url
     */
    void setMetadata(App app, String versionName, String downloadUrl) {
        Preconditions.checkNotNull(app, "Parameter app must not be null");
        Preconditions.checkNotNull(versionName, "Parameter versionName must not be null");
        Preconditions.checkNotNull(downloadUrl, "Parameter downloadUrl must not be null");
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(String.format(VERSION_NAME_TEMPLATE, getName(app)), versionName);
        editor.putString(String.format(DOWNLOAD_URL_TEMPLATE, getName(app)), downloadUrl);
        editor.putLong(String.format(TIMESTAMP_TEMPLATE, getName(app)), System.currentTimeMillis());
        editor.apply();
    }

    private String getName(App app) {
        return app.toString();
    }
}