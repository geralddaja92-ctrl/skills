package it.pulizias24.app.device

import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class CheckStatus { OK, INFO, WARNING, DANGER }

/** Azione che apre una schermata di sistema, con ripiego se non esiste sul dispositivo. */
sealed interface CheckAction {
    val label: String

    data class OpenSettings(
        override val label: String,
        val action: String,
        val fallback: String = Settings.ACTION_SETTINGS,
    ) : CheckAction

    data class LaunchApp(
        override val label: String,
        val packageName: String,
        val fallback: String = Settings.ACTION_SETTINGS,
    ) : CheckAction

    data class OpenComponent(
        override val label: String,
        val packageName: String,
        val className: String,
        val fallback: String = Settings.ACTION_SECURITY_SETTINGS,
    ) : CheckAction

    data class AppNotifications(override val label: String, val packageName: String) : CheckAction
}

data class DeviceCheck(
    val title: String,
    val status: CheckStatus,
    val detail: String,
    val actions: List<CheckAction> = emptyList(),
)

object DeviceChecks {

    const val DEVICE_CARE_PACKAGE = "com.samsung.android.lool"

    fun run(context: Context): List<DeviceCheck> = buildList {
        add(lockScreen(context))
        add(securityPatch())
        add(developerOptions(context))
        add(root())
        add(
            DeviceCheck(
                "Google Play Protect",
                CheckStatus.INFO,
                "Controlla che \"Scansiona app con Play Protect\" sia attivo: verifica " +
                    "continuamente le app con il database aggiornato di Google.",
                listOf(
                    CheckAction.OpenComponent(
                        "Apri Play Protect",
                        "com.google.android.gms",
                        "com.google.android.gms.security.settings.VerifyAppsSettingsActivity",
                    ),
                ),
            ),
        )
        if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            add(
                DeviceCheck(
                    "Blocco automatico Samsung (Auto Blocker)",
                    CheckStatus.INFO,
                    "Su Galaxy S24 Ultra attivalo in Impostazioni › Sicurezza e privacy › " +
                        "Blocco automatico: impedisce l'installazione di app da fonti non " +
                        "autorizzate e blocca comandi via cavo USB.",
                    listOf(
                        CheckAction.OpenSettings(
                            "Sicurezza e privacy",
                            Settings.ACTION_SECURITY_SETTINGS,
                        ),
                    ),
                ),
            )
            add(
                DeviceCheck(
                    "Assistenza dispositivo (Device Care)",
                    CheckStatus.INFO,
                    "La scansione antimalware integrata di Samsung e la pulizia della cache " +
                        "delle app si trovano in Impostazioni › Assistenza dispositivo.",
                    listOf(CheckAction.LaunchApp("Apri Device Care", DEVICE_CARE_PACKAGE)),
                ),
            )
        }
        add(
            DeviceCheck(
                "Pubblicità e notifiche spam dal browser",
                CheckStatus.INFO,
                "Se compaiono avvisi tipo \"Il tuo telefono è infetto!\" non è un virus: sono " +
                    "notifiche di siti web a cui hai dato il permesso. Bloccale nelle " +
                    "notifiche del browser e non installare le app che propongono.",
                listOf(
                    CheckAction.AppNotifications("Samsung Internet", "com.sec.android.app.sbrowser"),
                    CheckAction.AppNotifications("Chrome", "com.android.chrome"),
                ),
            ),
        )
        add(
            DeviceCheck(
                "Installazione da fonti sconosciute",
                CheckStatus.INFO,
                "Controlla quali app possono installare altri APK e revoca il permesso a quelle " +
                    "che non ne hanno bisogno (browser, app di messaggistica, file manager).",
                listOf(
                    CheckAction.OpenSettings(
                        "Installa app sconosciute",
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    ),
                ),
            ),
        )
    }

    private fun lockScreen(context: Context): DeviceCheck {
        val secure = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
        return if (secure) {
            DeviceCheck("Blocco schermo", CheckStatus.OK, "Protetto da PIN, sequenza, password o biometria.")
        } else {
            DeviceCheck(
                "Blocco schermo",
                CheckStatus.DANGER,
                "Il telefono non ha un blocco schermo sicuro: chiunque lo prenda in mano può " +
                    "installare app spia.",
                listOf(CheckAction.OpenSettings("Imposta blocco", Settings.ACTION_SECURITY_SETTINGS)),
            )
        }
    }

    private fun securityPatch(): DeviceCheck {
        val patch = Build.VERSION.SECURITY_PATCH
        val days = runCatching { ChronoUnit.DAYS.between(LocalDate.parse(patch), LocalDate.now()) }
            .getOrNull()
        val update = listOf(
            CheckAction.OpenSettings(
                "Aggiornamento software",
                "android.settings.SYSTEM_UPDATE_SETTINGS",
                Settings.ACTION_DEVICE_INFO_SETTINGS,
            ),
        )
        val base = "Android ${Build.VERSION.RELEASE}, patch di sicurezza del $patch"
        return when {
            days == null -> DeviceCheck("Aggiornamenti di sicurezza", CheckStatus.INFO, base, update)
            days > 180 -> DeviceCheck(
                "Aggiornamenti di sicurezza", CheckStatus.DANGER,
                "$base (${days / 30} mesi fa). Installa subito gli aggiornamenti.", update,
            )
            days > 90 -> DeviceCheck(
                "Aggiornamenti di sicurezza", CheckStatus.WARNING,
                "$base (${days / 30} mesi fa). Controlla se ci sono aggiornamenti.", update,
            )
            else -> DeviceCheck("Aggiornamenti di sicurezza", CheckStatus.OK, base, update)
        }
    }

    private fun developerOptions(context: Context): DeviceCheck {
        val cr = context.contentResolver
        val devEnabled = Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        val adbEnabled = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1
        val action = listOf(
            CheckAction.OpenSettings(
                "Opzioni sviluppatore",
                Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
            ),
        )
        return when {
            adbEnabled -> DeviceCheck(
                "Debug USB",
                CheckStatus.WARNING,
                "Il debug USB è attivo: un computer collegato può installare app senza che tu " +
                    "te ne accorga. Disattivalo se non sei uno sviluppatore.",
                action,
            )
            devEnabled -> DeviceCheck(
                "Opzioni sviluppatore",
                CheckStatus.INFO,
                "Le opzioni sviluppatore sono attive (il debug USB è spento).",
                action,
            )
            else -> DeviceCheck("Debug USB", CheckStatus.OK, "Opzioni sviluppatore e debug USB disattivati.")
        }
    }

    private fun root(): DeviceCheck {
        val suFound = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/app/Superuser.apk",
            "/data/local/xbin/su", "/data/local/bin/su", "/data/adb/magisk",
        ).any { runCatching { File(it).exists() }.getOrDefault(false) }
        val testKeys = Build.TAGS?.contains("test-keys") == true
        return if (suFound || testKeys) {
            DeviceCheck(
                "Integrità del sistema",
                CheckStatus.DANGER,
                "Trovati segni di root o firmware modificato. Se non l'hai fatto tu, il " +
                    "telefono potrebbe essere compromesso: valuta un ripristino ai dati di fabbrica.",
            )
        } else {
            DeviceCheck("Integrità del sistema", CheckStatus.OK, "Nessun segno di root o firmware modificato.")
        }
    }
}

/** Esegue un'azione aprendo la schermata giusta, con ripiego se non disponibile. */
fun Context.perform(action: CheckAction) {
    val primary: Intent? = when (action) {
        is CheckAction.OpenSettings -> Intent(action.action)
        is CheckAction.LaunchApp -> packageManager.getLaunchIntentForPackage(action.packageName)
        is CheckAction.OpenComponent ->
            Intent().setComponent(ComponentName(action.packageName, action.className))
        is CheckAction.AppNotifications ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, action.packageName)
    }
    val fallback: Intent = when (action) {
        is CheckAction.OpenSettings -> Intent(action.fallback)
        is CheckAction.LaunchApp -> Intent(action.fallback)
        is CheckAction.OpenComponent -> Intent(action.fallback)
        is CheckAction.AppNotifications ->
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${action.packageName}"))
    }
    startSafely(primary, fallback)
}

fun Context.startSafely(primary: Intent?, fallback: Intent? = Intent(Settings.ACTION_SETTINGS)) {
    for (intent in listOfNotNull(primary, fallback)) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: ActivityNotFoundException) {
            // prova il ripiego
        } catch (e: SecurityException) {
            // prova il ripiego
        }
    }
}
