package eu.x45.cal45.sync

import android.accounts.AccountManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

class Cal45AuthenticatorService : Service() {
    private val authenticator by lazy { Cal45Authenticator(this) }

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == AccountManager.ACTION_AUTHENTICATOR_INTENT) {
            authenticator.iBinder
        } else {
            null
        }
}
