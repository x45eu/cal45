package eu.x45.cal45.sync

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.ContactsContract

/**
 * User-controlled system scheduled synchronization.
 *
 * The operating system decides when periodic sync actually runs. These
 * SyncAdapters remain download/import-only; this never enables DAV writes.
 * A single Cal45 system account is supported by CredentialVault.
 */
object Cal45BackgroundSync {
    const val PERIOD_SECONDS: Long = 6L * 60L * 60L
    private val authorities = listOf(ContactsContract.AUTHORITY, CalendarContract.AUTHORITY)

    fun account(context: Context): Account? {
        val vault = CredentialVault(context)
        val server = DavProbe.normalizedUrl(vault.server()) ?: return null
        val name = try {
            Cal45SystemAccount.accountName(server, vault.username())
        } catch (_: Exception) { return null }
        if (!vault.configured() || !DavProbe.looksLikeAppPassword(vault.readAppPassword() ?: "")) return null
        return AccountManager.get(context).getAccountsByType(Cal45SystemAccount.TYPE)
            .firstOrNull { it.name == name }
    }

    fun hasPermissions(context: Context): Boolean =
        listOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR
        ).all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun enabled(context: Context): Boolean {
        val account = account(context) ?: return false
        return authorities.all {
            ContentResolver.getSyncAutomatically(account, it) &&
                ContentResolver.getPeriodicSyncs(account, it)
                    .any { sync -> sync.period >= PERIOD_SECONDS }
        }
    }

    /**
     * Atomic from the user's point of view: validate all prerequisites
     * before enabling either authority. An incomplete setup fails closed.
     */
    fun enable(context: Context): Boolean {
        val account = account(context) ?: return false
        if (!hasPermissions(context)) return false
        if (!ContentResolver.getMasterSyncAutomatically()) return false
        // Avoid accumulating multiple schedules after repeated button taps.
        for (authority in authorities) {
            ContentResolver.setIsSyncable(account, authority, 1)
            ContentResolver.removePeriodicSync(account, authority, Bundle.EMPTY)
            ContentResolver.addPeriodicSync(account, authority, Bundle.EMPTY, PERIOD_SECONDS)
            ContentResolver.setSyncAutomatically(account, authority, true)
        }
        context.getSharedPreferences("cal45_sync_schedule", Context.MODE_PRIVATE)
            .edit().putBoolean("enabled", true).apply()
        return true
    }

    fun disable(context: Context) {
        // Turn off automatic work even if permissions have since been revoked.
        for (account in AccountManager.get(context).getAccountsByType(Cal45SystemAccount.TYPE)) {
            for (authority in authorities) {
                ContentResolver.setSyncAutomatically(account, authority, false)
                ContentResolver.removePeriodicSync(account, authority, Bundle.EMPTY)
                ContentResolver.cancelSync(account, authority)
            }
        }
        context.getSharedPreferences("cal45_sync_schedule", Context.MODE_PRIVATE)
            .edit().putBoolean("enabled", false).apply()
    }
}
