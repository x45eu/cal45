package eu.x45.cal45.sync

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

/**
 * Initial setup screen only. Does not manage Android accounts, store credentials,
 * implement CalDAV/CardDAV, or claim to synchronize data.
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
        window.decorView.systemUiVisibility = 0

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

        val title = text("cal45", 40f, light, true)
        root.addView(title)
        val subtitle = text("SYNC  •  CALDAV + CARDDAV", 12f, accent, true)
        root.addView(subtitle)
        val intro = text("Dein Kalender. Deine Kontakte. Deine Cloud.", 17f, light).apply {
            setPadding(0, dp(26), 0, dp(28))
        }
        root.addView(intro)

        val panelView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(23), dp(20), dp(23))
            background = bg(panel)
        }
        root.addView(panelView, LinearLayout.LayoutParams(-1, -2))
        panelView.addView(text("Server verbinden", 22f, light, true))
        panelView.addView(text("Trage die HTTPS-Adresse deines Cal45-Servers ein.", 14f, muted).apply {
            setPadding(0, dp(8), 0, dp(20))
        })

        val server = EditText(this).apply {
            hint = "https://cal45.example.com/"
            setHintTextColor(muted)
            setTextColor(light)
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = bg(navy, 8)
            setText(getPreferences(MODE_PRIVATE).getString("server_url", "") ?: "")
        }
        panelView.addView(server, LinearLayout.LayoutParams(-1, -2))
        val status = text("Noch nicht verbunden", 13f, muted).apply {
            setPadding(0, dp(15), 0, dp(10))
        }
        val check = Button(this).apply {
            text = "HTTPS-Verbindung prüfen"
            setTextColor(navy)
            backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
            isAllCaps = false
        }
        panelView.addView(check, LinearLayout.LayoutParams(-1, dp(54)))
        panelView.addView(status)

        check.setOnClickListener {
            val input = server.text.toString().trim()
            val uri = try { URI(input) } catch (_: Exception) { null }
            if (uri == null || !uri.scheme.equals("https", true) ||
                uri.host.isNullOrBlank() || !uri.rawUserInfo.isNullOrBlank() ||
                uri.rawQuery != null || uri.rawFragment != null) {
                status.text = "Bitte eine gültige HTTPS-Adresse ohne Zugangsdaten verwenden."
                return@setOnClickListener
            }
            // Never send credentials, and never follow redirects to other destinations.
            check.isEnabled = false
            status.text = "Verbindung wird geprüft …"
            thread {
                var conn: HttpsURLConnection? = null
                val result = try {
                    conn = URL(uri.toString()).openConnection() as HttpsURLConnection
                    conn!!.connectTimeout = 6000
                    conn!!.readTimeout = 6000
                    conn!!.instanceFollowRedirects = false
                    conn!!.requestMethod = "GET"
                    val code = conn!!.responseCode
                    if (code in listOf(200, 207, 401, 403)) {
                        getPreferences(MODE_PRIVATE).edit().putString("server_url", uri.toString()).apply()
                        "HTTPS-Server erreichbar (HTTP $code). DAV-Anmeldung ist noch nicht implementiert."
                    } else {
                        "Server antwortet mit HTTP $code. Bitte Adresse und Proxy prüfen."
                    }
                } catch (_: Exception) {
                    "HTTPS-Verbindung fehlgeschlagen. Zertifikat, Adresse und Netzwerk prüfen."
                } finally {
                    conn?.disconnect()
                }
                runOnUiThread {
                    status.text = result
                    check.isEnabled = true
                }
            }
        }
        root.addView(text("Entwicklungsstand", 19f, light, true).apply {
            setPadding(0, dp(30), 0, dp(14))
        })
        root.addView(text("✓  Native Android-App ohne Google Play Services\n\n" +
            "✓  HTTPS-Prüfung ohne gespeicherte Passwörter\n\n" +
            "○  Konten und App-Passwörter: noch offen\n\n" +
            "○  Kontakte-Sync: noch offen\n\n" +
            "○  Kalender-Sync: noch offen", 15f, muted))
        root.addView(text("Bereitgestellt von Cal45", 12f, muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(38), 0, 0)
        })
    }
}
