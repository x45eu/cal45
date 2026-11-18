package eu.x45.cal45.sync

import android.app.Activity
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
 * Cal45 Sync credential enrollment, no calendar/contact sync yet.
 * Supports existing DAV user credentials; dedicated app passwords will
 * be issued by Cal45 server once its account management is implemented.
 */
class MainActivity : Activity() {
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
                    if (result.successful && message.contains("gespeichert")) appPassword.text.clear()
                    check.isEnabled = true
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
            vault.remove()
            server.text.clear()
            username.text.clear()
            appPassword.text.clear()
            status.text = "Lokale Zugangsdaten entfernt."
        }

        root.addView(text("Entwicklungsstand", 19f, light, true).apply {
            setPadding(0, dp(30), 0, dp(14))
        })
        root.addView(text("✓  Native Android-App ohne Google Play Services\\n\\n" +
            "✓  HTTPS-DAV-Prüfung nur mit Domainnamen\\n\\n" +
            "✓  Lokale Token-Formatprüfung und Android-Keystore-Speicherung\\n\\n" +
            "○  Serverseitige App-Passwort-Erkennung: noch offen\\n\\n" +
            "○  Android-Systemkonto: noch offen\\n\\n" +
            "○  Kontakte-Synchronisierung: noch offen\\n\\n" +
            "○  Kalender-Synchronisierung: noch offen", 15f, muted))
        root.addView(text("Bereitgestellt von Cal45", 12f, muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(38), 0, 0)
        })
    }
}
