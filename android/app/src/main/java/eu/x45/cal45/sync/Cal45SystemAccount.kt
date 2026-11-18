package eu.x45.cal45.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.ContentResolver
import android.provider.ContactsContract
import android.os.Bundle
import java.net.URI
import java.util.Locale

/**
 * Registers a visible Cal45 Android system account. No app password or
 * authentication token is ever stored in Android's AccountManager.
 *
 * This is not a SyncAdapter; calendar/contact syncing remains forthcoming.
 * Initial implementation supports a single Cal45 account per installation,
 * matching CredentialVault's current one-account storage model.
 */
object Cal45SystemAccount {
    const val TYPE = "eu.x45.cal45.sync"

    fun accountName(server: String, username: String): String {
        val host = URI(server).host.lowercase(Locale.ROOT)
        return "$username@$host"
    }

    fun enroll(context: Context, server: String, username: String): Account {
        require(DavProbe.normalizedUrl(server) != null) { "Ungültige Cal45-Adresse" }
        require(username.isNotBlank()) { "Benutzername fehlt" }
        val manager = AccountManager.get(context)
        val account = Account(accountName(server, username), TYPE)
        // One system account only until multi-account credential storage exists.
        for (existing in manager.getAccountsByType(TYPE)) {
            if (existing.name != account.name) manager.removeAccountExplicitly(existing)
        }
        val present = manager.getAccountsByType(TYPE).any { it.name == account.name }
        if (!present) {
            check(manager.addAccountExplicitly(account, null, Bundle().apply {
                putString("server", server)
            })) { "Cal45-Systemkonto konnte nicht erstellt werden" }
        } else {
            manager.setUserData(account, "server", server)
        }
        ContentResolver.setIsSyncable(account, ContactsContract.AUTHORITY, 1)
        ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, false)
        return account
    }

    fun remove(context: Context) {
        val manager = AccountManager.get(context)
        for (account in manager.getAccountsByType(TYPE)) {
            manager.removeAccountExplicitly(account)
        }
    }
}
