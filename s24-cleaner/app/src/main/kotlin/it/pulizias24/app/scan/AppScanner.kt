package it.pulizias24.app.scan

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.view.accessibility.AccessibilityManager
import java.io.File
import java.security.MessageDigest

enum class Severity(val weight: Int, val label: String) {
    LOW(8, "Basso"),
    MEDIUM(18, "Medio"),
    HIGH(35, "Alto"),
}

data class Finding(val severity: Severity, val title: String, val detail: String)

enum class RiskLevel(val label: String) {
    SAFE("Nessun problema"),
    LOW("Rischio basso"),
    MEDIUM("Sospetta"),
    HIGH("Pericolosa"),
}

data class AppReport(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val isSystem: Boolean,
    val installerLabel: String,
    val apkPath: String?,
    val firstInstallTime: Long,
    val findings: List<Finding>,
    val isDeviceAdmin: Boolean,
    val hasAccessibilityEnabled: Boolean,
) {
    val score: Int get() = findings.sumOf { it.severity.weight }.coerceAtMost(100)
    val level: RiskLevel
        get() = when {
            score >= 50 -> RiskLevel.HIGH
            score >= 25 -> RiskLevel.MEDIUM
            score > 0 -> RiskLevel.LOW
            else -> RiskLevel.SAFE
        }
}

/**
 * Analizza le app installate con regole euristiche, senza connessione a internet.
 *
 * Android isola ogni app nella propria sandbox: nessuna app (antivirus compresi) può
 * cancellare da sola un'altra app. Questo scanner individua i segnali tipici di malware,
 * stalkerware e adware e poi lascia all'utente la scelta di disinstallare tramite la
 * finestra di sistema.
 */
class AppScanner(private val context: Context) {

    private val pm: PackageManager = context.packageManager

    fun scan(includeSystem: Boolean): List<AppReport> {
        val accessibilityEnabled = enabledAccessibilityPackages()
        val accessibilityDeclared = servicePackages(AccessibilityService.SERVICE_INTERFACE)
        val notificationListeners = servicePackages(NotificationListenerService.SERVICE_INTERFACE)
        val admins = activeAdminPackages()
        val launcherPackages = launcherPackages()
        val defaultSms = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val installOp = runCatching {
            AppOpsManager.permissionToOp(Manifest.permission.REQUEST_INSTALL_PACKAGES)
        }.getOrNull()

        return installedPackages()
            .asSequence()
            .filter { it.packageName != context.packageName }
            .mapNotNull { pi ->
                val ai: ApplicationInfo = pi.applicationInfo ?: return@mapNotNull null
                val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val pkg = pi.packageName
                val isAdmin = pkg in admins
                val hasA11y = pkg in accessibilityEnabled
                // Le app di sistema vengono mostrate solo se richiesto o se hanno privilegi attivi.
                if (isSystem && !includeSystem && !isAdmin && !hasA11y) return@mapNotNull null

                val label = ai.loadLabel(pm).toString()
                val installer = if (isSystem) null else installerOf(pkg)
                val trustedSource = isSystem || installer in TRUSTED_INSTALLERS
                val granted = grantedPermissions(pi)
                val findings = mutableListOf<Finding>()

                if (!trustedSource) {
                    findings += Finding(
                        Severity.MEDIUM,
                        "Installata fuori dagli store ufficiali",
                        "Origine: ${installerLabel(installer)}. La maggior parte dei malware " +
                            "Android arriva da APK scaricati da siti web, chat o link ricevuti.",
                    )
                }
                if (hasA11y) {
                    findings += Finding(
                        if (isSystem) Severity.LOW else Severity.HIGH,
                        "Servizio di accessibilità attivo",
                        "Può leggere tutto ciò che appare sullo schermo (anche password e codici) " +
                            "e toccare al posto tuo. È la tecnica principale dei trojan bancari. " +
                            "Disattivalo se non sai perché è attivo.",
                    )
                } else if (!isSystem && !trustedSource && pkg in accessibilityDeclared) {
                    findings += Finding(
                        Severity.LOW,
                        "Può chiedere l'accesso di accessibilità",
                        "Contiene un servizio di accessibilità. Non concederlo se l'app non è " +
                            "pensata per persone con disabilità.",
                    )
                }
                if (isAdmin) {
                    findings += Finding(
                        if (isSystem) Severity.LOW else Severity.HIGH,
                        "Amministratore del dispositivo attivo",
                        "Può bloccare lo schermo, cancellare i dati e impedire la propria " +
                            "disinstallazione. Rimuovi il permesso prima di disinstallarla.",
                    )
                }
                if (!isSystem && pkg !in launcherPackages) {
                    findings += Finding(
                        if (trustedSource) Severity.LOW else Severity.MEDIUM,
                        "Nessuna icona nel menu delle app",
                        "L'app non si vede nel cassetto delle app. Alcuni componenti legittimi " +
                            "(tastiere, plugin) sono così, ma è anche il modo in cui si nascondono " +
                            "spyware e malware.",
                    )
                }
                if (!isSystem && !trustedSource) {
                    if (SYSTEM_PREFIXES.any { pkg.startsWith(it) }) {
                        findings += Finding(
                            Severity.HIGH,
                            "Si spaccia per un'app di sistema",
                            "Il nome del pacchetto ($pkg) imita Android, Google o Samsung, ma " +
                                "l'app non è preinstallata né proviene da uno store ufficiale.",
                        )
                    } else if (DECEPTIVE_LABELS.any { label.lowercase().contains(it) }) {
                        findings += Finding(
                            Severity.MEDIUM,
                            "Nome ingannevole",
                            "\"$label\" sembra un componente di sistema o un aggiornamento: è un " +
                                "trucco comune per convincere a concedere permessi.",
                        )
                    }
                }
                if (!isSystem && pkg != defaultSms && SMS_PERMISSIONS.any { it in granted }) {
                    findings += Finding(
                        Severity.MEDIUM,
                        "Legge o invia SMS",
                        "Non è l'app SMS predefinita ma può accedere ai messaggi: potrebbe " +
                            "intercettare i codici di verifica della banca.",
                    )
                }
                if (!isSystem && Manifest.permission.READ_CALL_LOG in granted) {
                    findings += Finding(
                        Severity.LOW,
                        "Accesso al registro chiamate",
                        "Può vedere chi chiami e da chi vieni chiamato.",
                    )
                }
                if (!isSystem &&
                    Manifest.permission.RECORD_AUDIO in granted &&
                    Manifest.permission.CAMERA in granted &&
                    Manifest.permission.ACCESS_FINE_LOCATION in granted
                ) {
                    val background = Manifest.permission.ACCESS_BACKGROUND_LOCATION in granted
                    findings += Finding(
                        if (background) Severity.MEDIUM else Severity.LOW,
                        "Microfono, fotocamera e posizione" +
                            if (background) " (anche in background)" else "",
                        "Combinazione normale per app di chat e videochiamate, ma tipica degli " +
                            "stalkerware se l'app non ti serve per questo.",
                    )
                }
                if (!isSystem && appOps != null &&
                    opAllowed(appOps, AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, ai.uid, pkg)
                ) {
                    findings += Finding(
                        if (trustedSource) Severity.LOW else Severity.MEDIUM,
                        "Mostra finestre sopra le altre app",
                        "Usato da adware per pubblicità a tutto schermo e da malware per " +
                            "finte schermate di login.",
                    )
                }
                if (!isSystem && appOps != null && installOp != null &&
                    opAllowed(appOps, installOp, ai.uid, pkg)
                ) {
                    findings += Finding(
                        if (trustedSource) Severity.LOW else Severity.MEDIUM,
                        "Può installare altre app",
                        "Ha il permesso \"Installa app sconosciute\": può scaricare e installare " +
                            "altri APK. Revocalo se non ti serve.",
                    )
                }
                if (!isSystem && !trustedSource && pkg in notificationListeners) {
                    findings += Finding(
                        Severity.LOW,
                        "Può chiedere l'accesso alle notifiche",
                        "Con questo accesso leggerebbe i messaggi e i codici che ricevi.",
                    )
                }
                if (!isSystem && ai.targetSdkVersion < Build.VERSION_CODES.O) {
                    findings += Finding(
                        Severity.LOW,
                        "App molto vecchia",
                        "Progettata per Android ${ai.targetSdkVersion}: aggira molte protezioni " +
                            "moderne e non riceve più aggiornamenti.",
                    )
                }
                if (!isSystem && (ai.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                    findings += Finding(
                        Severity.LOW,
                        "Versione di debug",
                        "Le app pubblicate negli store non sono mai in modalità debug.",
                    )
                }

                AppReport(
                    packageName = pkg,
                    label = label,
                    versionName = pi.versionName,
                    isSystem = isSystem,
                    installerLabel = if (isSystem) "Preinstallata" else installerLabel(installer),
                    apkPath = ai.sourceDir,
                    firstInstallTime = pi.firstInstallTime,
                    findings = findings.sortedByDescending { it.severity.weight },
                    isDeviceAdmin = isAdmin,
                    hasAccessibilityEnabled = hasA11y,
                )
            }
            .sortedWith(compareByDescending<AppReport> { it.score }.thenBy { it.label.lowercase() })
            .toList()
    }

    fun isInstalled(pkg: String): Boolean =
        runCatching { pm.getApplicationInfo(pkg, 0) }.isSuccess

    @Suppress("DEPRECATION")
    private fun installedPackages(): List<PackageInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
            )
        } else {
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        }

    @Suppress("DEPRECATION")
    private fun installerOf(pkg: String): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(pkg).installingPackageName
        } else {
            pm.getInstallerPackageName(pkg)
        }
    } catch (e: Exception) {
        null
    }

    private fun installerLabel(installer: String?): String = when {
        installer == null -> "sconosciuta (APK installato manualmente o via cavo)"
        installer in TRUSTED_INSTALLERS -> TRUSTED_INSTALLERS.getValue(installer)
        installer in PACKAGE_INSTALLERS -> "file APK installato manualmente"
        else -> runCatching {
            pm.getApplicationInfo(installer, 0).loadLabel(pm).toString()
        }.getOrDefault(installer)
    }

    private fun grantedPermissions(pi: PackageInfo): Set<String> {
        val requested = pi.requestedPermissions ?: return emptySet()
        val flags = pi.requestedPermissionsFlags ?: IntArray(0)
        return requested.indices
            .filter { i -> ((flags.getOrNull(i) ?: 0) and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0 }
            .map { requested[it] }
            .toSet()
    }

    private fun enabledAccessibilityPackages(): Set<String> {
        val result = mutableSetOf<String>()
        runCatching {
            val am = context.getSystemService(AccessibilityManager::class.java)
            am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?.mapNotNullTo(result) { it.resolveInfo?.serviceInfo?.packageName }
        }
        runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )?.split(':')
                ?.mapNotNull { it.substringBefore('/').takeIf { p -> p.isNotBlank() } }
                ?.let { result.addAll(it) }
        }
        return result
    }

    private fun activeAdminPackages(): Set<String> = runCatching {
        context.getSystemService(DevicePolicyManager::class.java)
            ?.activeAdmins
            ?.map { it.packageName }
            ?.toSet()
    }.getOrNull() ?: emptySet()

    @Suppress("DEPRECATION")
    private fun servicePackages(action: String): Set<String> = runCatching {
        pm.queryIntentServices(Intent(action), 0)
            .mapNotNull { it.serviceInfo?.packageName }
            .toSet()
    }.getOrDefault(emptySet())

    @Suppress("DEPRECATION")
    private fun launcherPackages(): Set<String> = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }.getOrDefault(emptySet())

    private fun opAllowed(appOps: AppOpsManager, op: String, uid: Int, pkg: String): Boolean =
        try {
            appOps.unsafeCheckOpNoThrow(op, uid, pkg) == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }

    companion object {
        val TRUSTED_INSTALLERS = mapOf(
            "com.android.vending" to "Google Play Store",
            "com.google.android.feedback" to "Google Play Store",
            "com.sec.android.app.samsungapps" to "Galaxy Store",
        )
        private val PACKAGE_INSTALLERS = setOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.android.shell",
        )
        private val SYSTEM_PREFIXES = listOf(
            "android.",
            "com.android.",
            "com.google.android.",
            "com.samsung.",
            "com.sec.",
        )
        private val DECEPTIVE_LABELS = listOf(
            "system update", "aggiornamento di sistema", "aggiornamento sistema",
            "android update", "google update", "system service", "servizio di sistema",
            "play services", "flash player", "security update", "aggiornamento sicurezza",
            "wifi service", "sync service", "device health",
        )
        private val SMS_PERMISSIONS = setOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
        )

        /** SHA-256 dell'APK: si può verificare su VirusTotal senza caricare il file. */
        fun sha256(path: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            File(path).inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
