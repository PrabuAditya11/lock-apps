package com.prabu.voicelock.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class InstalledApp(
    val packageName: String,
    val label: String,
)

/**
 * Lists apps that have a launcher entry. Resolved through a LAUNCHER intent query, which
 * the manifest <queries> element covers, so QUERY_ALL_PACKAGES is not needed.
 */
class InstalledAppsProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun loadLaunchableApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
            .asSequence()
            .mapNotNull { it.activityInfo?.applicationInfo }
            // Locking ourselves out of the settings UI would be unrecoverable.
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map { InstalledApp(it.packageName, packageManager.getApplicationLabel(it).toString()) }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}
