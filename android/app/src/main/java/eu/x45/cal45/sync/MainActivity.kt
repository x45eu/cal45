package eu.x45.cal45.sync

import android.accounts.AccountAuthenticatorActivity
import android.accounts.AccountManager
import android.Manifest
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.provider.CalendarContract
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Cal45 Sync account enrollment and manual, import-only DAV SyncAdapters.
 * Requires separate Cal45 device app passwords and HTTPS DNS server URLs.
 * Server-to-Android guarded updates are available. Uploads and deletes remain disabled.
 */
class MainActivity : AccountAuthenticatorActivity() {
    private val contactsPermissionRequest = 4501
    private val calendarPermissionRequest = 4502
    private val backgroundPermissionRequest = 4503

    private fun importContactsNow() {
        val vault = CredentialVault(this)
        val server = vault.server()
        val user = vault.username()
        if (!vault.configured() || DavProbe.normalizedUrl(server) == null ||
            vault.readAppPassword() == null) return
        val name = Cal45SystemAccount.accountName(server, user)
        val matches = AccountManager.get(this).getAccountsByType(Cal45SystemAccount.TYPE)
            .firstOrNull { it.name == name } ?: return
        ContentResolver.setIsSyncable(matches, ContactsContract.AUTHORITY, 1)
        ContentResolver.requestSync(matches, ContactsContract.AUTHORITY, Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        })
    }

    private fun importCalendarNow() {
        val vault = CredentialVault(this)
        val server = vault.server()
        val user = vault.username()
        if (!vault.configured() || DavProbe.normalizedUrl(server) == null ||
            vault.readAppPassword() == null) return
        val name = Cal45SystemAccount.accountName(server, user)
        val account = AccountManager.get(this).getAccountsByType(Cal45SystemAccount.TYPE)
            .firstOrNull { it.name == name } ?: return
        ContentResolver.setIsSyncable(account, CalendarContract.AUTHORITY, 1)
        ContentResolver.requestSync(account, CalendarContract.AUTHORITY, Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        })
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isEmpty() || !grantResults.all { it == PackageManager.PERMISSION_GRANTED }) return
        when (requestCode) {
            contactsPermissionRequest -> importContactsNow()
            calendarPermissionRequest -> importCalendarNow()
            backgroundPermissionRequest -> {
                val enabled = Cal45BackgroundSync.enable(this)
                android.widget.Toast.makeText(this,
                    if (enabled) "Cal45-Hintergrundimport aktiviert." else "Hintergrundimport nicht möglich. Android-Kontosynchronisierung prüfen.",
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private val navy = Color.rgb(25, 34, 44)
    private val panel = Color.rgb(34, 48, 63)
    private val light = Color.rgb(212, 219, 229)
    private val accent = Color.rgb(96, 165, 250)
    private val muted = Color.rgb(166, 184, 203)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun bg(color: Int, radius: Int = 14): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = navy
        window.navigationBarColor = navy

        val vault = CredentialVault(this)
        // Discard credentials saved by older builds that accepted account passwords.
        if (vault.configured() && !DavProbe.looksLikeAppPassword(vault.readAppPassword() ?: "")) {
            vault.remove()
            Cal45SystemAccount.remove(this)
        }
        // Only a successful user-initiated DAV login may create a system account.
        // Never silently re-add an account removed through Android Settings.
        val scroll = ScrollView(this).apply { setBackgroundColor(navy) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
        }
        scroll.addView(root)
        setContentView(scroll)

        fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView =
            TextView(this).apply {
                this.text = value
                textSize = size
                setTextColor(color)
                if (bold) setTypeface(null, Typeface.BOLD)
            }

        root.addView(text("cal45", 40f, light, true))
        root.addView(text("SYNC  •  CALDAV + CARDDAV", 12f, accent, true))
        root.addView(text("Dein Kalender. Deine Kontakte. Deine Cloud.", 17f, light).apply {
            setPadding(0, dp(26), 0, dp(28))
        })

        val panelView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(23), dp(20), dp(23))
            background = bg(panel)
        }
        root.addView(panelView, LinearLayout.LayoutParams(-1, -2))
        panelView.addView(text("Cal45-Konto verbinden", 22f, light, true))
        panelView.addView(text("Nur HTTPS-Domainnamen und separate Cal45-App-Passwörter (cal45_…). Keine IP-Adressen und kein Kontopasswort.", 14f, muted).apply {
            setPadding(0, dp(8), 0, dp(20))
        })

        fun field(hintValue: String, type: Int): EditText =
            EditText(this).apply {
                hint = hintValue
                setHintTextColor(muted)
                setTextColor(light)
                setSingleLine(true)
                inputType = type
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = bg(navy, 8)
                val params = LinearLayout.LayoutParams(-1, dp(52))
                params.bottomMargin = dp(10)
                panelView.addView(this, params)
            }

        val server = field("https://cal45.example.com/", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val username = field("Benutzername", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL)
        val appPassword = field("Cal45-App-Passwort (cal45_…)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        appPassword.setTextIsSelectable(false)
        server.setText(vault.server())
        username.setText(vault.username())

        val status = text(if (vault.configured()) "Konto auf diesem Gerät gespeichert (Sync noch in Entwicklung)." else "Noch kein Konto verbunden", 13f, muted).apply {
            setPadding(0, dp(15), 0, dp(10))
        }
        val check = Button(this).apply {
            text = "DAV-Zugang prüfen und speichern"
            setTextColor(navy)
            backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
            isAllCaps = false
        }
        panelView.addView(check, LinearLayout.LayoutParams(-1, dp(54)))
        panelView.addView(status)

        check.setOnClickListener {
            val url = server.text.toString().trim()
            val user = username.text.toString().trim()
            val password = appPassword.text.toString()
            if (DavProbe.normalizedUrl(url) == null || user.isBlank() || !DavProbe.looksLikeAppPassword(password)) {
                status.text = "Nur HTTPS-Domain, Benutzername und Cal45-App-Passwort (cal45_…) zulässig."
                return@setOnClickListener
            }
            check.isEnabled = false
            status.text = "Cal45-App-Passwort wird über HTTPS geprüft …"
            thread {
                val result = DavProbe.check(url, user, password)
                val message = if (result.successful) {
                    try {
                        vault.save(DavProbe.normalizedUrl(url)!!, user, password)
                        result.message + " Zugangsdaten verschlüsselt gespeichert."
                    } catch (_: Exception) {
                        "DAV erreichbar, aber verschlüsselte Speicherung fehlgeschlagen."
                    }
                } else result.message
                runOnUiThread {
                    status.text = message
                    if (result.successful && message.contains("gespeichert")) {
                        appPassword.text.clear()
                        try {
                            val account = Cal45SystemAccount.enroll(this,
                                DavProbe.normalizedUrl(url)!!, user)
                            setAccountAuthenticatorResult(Bundle().apply {
                                putString(AccountManager.KEY_ACCOUNT_NAME, account.name)
                                putString(AccountManager.KEY_ACCOUNT_TYPE, account.type)
                            })
                            status.text = message + " Android-Systemkonto eingerichtet (Synchronisierung folgt)."
                        } catch (_: Exception) {
                            status.text = message + " Das Android-Systemkonto konnte noch nicht eingerichtet werden."
                        }
                    }
                    check.isEnabled = true
                }
            }
        }

        val contactsImport = Button(this).apply {
            text = "Kontakte aus Cal45 importieren (nur lesend)"
            isAllCaps = false
            setTextColor(navy)
            backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        panelView.addView(contactsImport)
        contactsImport.setOnClickListener {
            val stored = CredentialVault(this)
            if (!stored.configured()) {
                status.text = "Zuerst den Cal45-DAV-Zugang prüfen und speichern."
                return@setOnClickListener
            }
            val required = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
            if (required.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
                status.text = "Android benötigt die Kontakte-Berechtigung für den Import."
                requestPermissions(required, contactsPermissionRequest)
            } else {
                importContactsNow()
                status.text = "Kontakte-Import angefordert. Neue Kontakte erscheinen nach dem Abgleich in Android."
            }
        }

        val lastImport = Button(this).apply {
            text = "Letzten Kontakte-Import prüfen"
            isAllCaps = false
            setTextColor(light)
        }
        panelView.addView(lastImport)
        lastImport.setOnClickListener {
            val prefs = getSharedPreferences("cal45_contact_sync_status", MODE_PRIVATE)
            status.text = prefs.getString("message", null)
                ?: "Es liegt noch kein abgeschlossener Kontakte-Import vor."
        }

        val calendarImport = Button(this).apply {
            text = "Termine aus Cal45 importieren (nur lesend)"
            isAllCaps = false
            setTextColor(navy)
            backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        panelView.addView(calendarImport)
        calendarImport.setOnClickListener {
            if (!CredentialVault(this).configured()) {
                status.text = "Bitte zuerst den Cal45-DAV-Zugang prüfen und speichern."
                return@setOnClickListener
            }
            val required = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            if (required.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
                status.text = "Android benötigt die Kalenderberechtigung für den Terminimport."
                requestPermissions(required, calendarPermissionRequest)
            } else {
                importCalendarNow()
                status.text = "Kalenderimport angefordert. Den Abschluss anschließend prüfen."
            }
        }

        val calendarResult = Button(this).apply {
            text = "Letzten Kalender-Import prüfen"
            isAllCaps = false
            setTextColor(light)
        }
        panelView.addView(calendarResult)
        calendarResult.setOnClickListener {
            val prefs = getSharedPreferences("cal45_calendar_sync_status", MODE_PRIVATE)
            status.text = prefs.getString("message", null)
                ?: "Noch kein abgeschlossener Kalenderimport vorhanden."
        }

        val scheduleTitle = text("Hintergrundimport", 19f, light, true).apply {
            setPadding(0, dp(24), 0, dp(8))
        }
        panelView.addView(scheduleTitle)
        panelView.addView(text(
            "Optional: Android versucht etwa alle 6 Stunden neue Cal45-Kontakte und einfache Termine zu importieren. Keine Uploads oder Löschungen.",
            13f, muted
        ))
        val enableBackground = Button(this).apply {
            text = "Automatischen Import aktivieren"
            isAllCaps = false
            setTextColor(navy)
            backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        panelView.addView(enableBackground)
        enableBackground.setOnClickListener {
            val permissions = arrayOf(
                Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS,
                Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR
            )
            if (Cal45BackgroundSync.account(this) == null) {
                status.text = "Zuerst das Cal45-Systemkonto mit einem App-Passwort verbinden."
            } else if (permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
                status.text = "Für den automatischen Import sind Kontakte- und Kalenderberechtigungen nötig."
                requestPermissions(permissions, backgroundPermissionRequest)
            } else if (Cal45BackgroundSync.enable(this)) {
                status.text = "Automatischer Import eingerichtet. Android bestimmt den tatsächlichen Zeitpunkt."
            } else {
                status.text = "Automatischer Import nicht möglich. Globale Android-Synchronisierung prüfen."
            }
        }
        val disableBackground = Button(this).apply {
            text = "Automatischen Import ausschalten"
            isAllCaps = false
            setTextColor(light)
        }
        panelView.addView(disableBackground)
        disableBackground.setOnClickListener {
            Cal45BackgroundSync.disable(this)
            status.text = "Automatischer Cal45-Import deaktiviert."
        }
        val scheduleStatus = Button(this).apply {
            text = "Hintergrundimport-Status prüfen"
            isAllCaps = false
            setTextColor(light)
        }
        panelView.addView(scheduleStatus)
        scheduleStatus.setOnClickListener {
            status.text = if (Cal45BackgroundSync.enabled(this)) {
                "Der Cal45-Hintergrundimport ist für Kontakte und Kalender aktiviert."
            } else {
                "Automatischer Import ist nicht aktiv oder wurde in den Android-Einstellungen deaktiviert."
            }
        }

        val localChangesTitle = text("Lokale Änderungen – Vorschau", 19f, light, true).apply {
            setPadding(0, dp(24), 0, dp(8))
        }
        panelView.addView(localChangesTitle)
        panelView.addView(text(
            "Zeigt lokal bearbeitete Cal45-Kontakte und Termine. " +
                "Es werden dabei keine Daten an den Server gesendet.",
            13f, muted
        ))
        val previewButton = Button(this).apply {
            text = "Lokale Änderungen prüfen (ohne Upload)"
            isAllCaps = false
            setTextColor(navy)
            backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        panelView.addView(previewButton)
        val previewResults = text("", 13f, light).apply {
            setPadding(0, dp(10), 0, dp(14))
        }
        panelView.addView(previewResults)
        previewButton.setOnClickListener {
            val selectedAccount = Cal45BackgroundSync.account(this)
            if (selectedAccount == null) {
                status.text = "Zuerst ein gültiges Cal45-Systemkonto verbinden."
                return@setOnClickListener
            }
            previewButton.isEnabled = false
            previewResults.text = "Nur lokale Android-Daten werden geprüft …"
            thread {
                val message = try {
                    val items = LocalChangePreview(this).inspect(selectedAccount)
                    if (items.isEmpty()) {
                        "Keine als geändert markierten Cal45-Einträge gefunden."
                    } else buildString {
                        append("Vorschau: ").append(items.size)
                            .append(" Einträge (maximal 100).\n\n")
                        for (item in items) {
                            append(item.type).append(": ")
                                .append(item.name.take(100)).append("\n")
                                .append(item.status).append("\n\n")
                        }
                    }
                } catch (_: Exception) {
                    "Lokale Vorschau nicht verfügbar. Berechtigungen und Konto prüfen."
                }
                runOnUiThread {
                    previewResults.text = message
                    previewButton.isEnabled = true
                }
            }
        }

        val journalButton = Button(this).apply {
            text = "Abgleichsverlauf prüfen"
            isAllCaps = false
            setTextColor(light)
        }
        panelView.addView(journalButton)
        journalButton.setOnClickListener {
            val selectedAccount = Cal45BackgroundSync.account(this)
            if (selectedAccount == null) {
                status.text = "Zuerst das Cal45-Systemkonto verbinden."
                return@setOnClickListener
            }
            journalButton.isEnabled = false
            thread {
                val message = try {
                    val entries = SyncJournalStore(this, selectedAccount.name).list()
                    val summary = SyncJournalSummary.summarize(entries)
                    if (summary.total == 0) {
                        "Noch keine Versionen im privaten Abgleichsverlauf erfasst."
                    } else {
                        "Abgleichsverlauf: ${summary.total} Einträge " +
                            "(${summary.contacts} Kontakte, ${summary.events} Termine). " +
                            "${summary.imported} importiert, " +
                            "${summary.conflicts} Konflikte, " +
                            "${summary.blocked} gesperrt. " +
                            "Gespeichert sind nur Versionsnummern und Hashes."
                    }
                } catch (_: Exception) {
                    "Abgleichsverlauf beschädigt oder nicht lesbar. " +
                        "Kein Upload möglich. Bestehende Daten bleiben unangetastet."
                }
                runOnUiThread {
                    status.text = message
                    journalButton.isEnabled = true
                }
            }
        }

        val forget = Button(this).apply {
            text = "Gespeichertes Konto entfernen"
            isAllCaps = false
            setTextColor(light)
        }
        panelView.addView(forget)
        forget.setOnClickListener {
            Cal45SystemAccount.remove(this)
            vault.remove()
            server.text.clear()
            username.text.clear()
            appPassword.text.clear()
            status.text = "Lokale Zugangsdaten entfernt."
        }

        root.addView(text("Entwicklungsstand", 19f, light, true).apply {
            setPadding(0, dp(30), 0, dp(14))
        })
        root.addView(text("✓  Native Android-App ohne Google Play Services\n\n" +
            "✓  HTTPS-DAV-Prüfung nur mit Domainnamen\n\n" +
            "✓  Lokale Token-Formatprüfung und Android-Keystore-Speicherung\n\n" +
            "✓  Serverseitige App-Passwort-Prüfung über Cal45-Gateway\n\n" +
            "✓  Android-Systemkonto und Kontakte-SyncAdapter\n\n" +
            "✓  CardDAV-Kontakte: Import und geschützte Updates\n\n" +
            "○  Kontaktlöschungen und Uploads: noch offen\n\n" +
            "✓  CalDAV-Termine: Import, Updates und begrenzte Serien\n\n" +
            "✓  Optionaler Hintergrundimport (systemgesteuert)\n\n" +
            "✓  Vorschau lokal geänderter Einträge (ohne Upload)\n\n" +
            "○  Vollständiger bidirektionaler Abgleich: noch offen", 15f, muted))
        root.addView(text("Bereitgestellt von Cal45", 12f, muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(38), 0, 0)
        })
    }
}
