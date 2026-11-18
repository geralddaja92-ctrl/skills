package it.pulizias24.app.clean

import android.content.Context
import android.os.Environment
import java.io.File
import java.security.MessageDigest

enum class JunkCategory(val title: String, val description: String, val preselected: Boolean) {
    APK(
        "File di installazione APK",
        "APK scaricati: dopo l'installazione non servono più e sono il modo in cui " +
            "arrivano i virus. Se non li hai scaricati tu, eliminali.",
        true,
    ),
    TEMP(
        "File temporanei e log",
        "File .tmp, .log e simili lasciati da app e download interrotti.",
        true,
    ),
    THUMBNAILS(
        "Miniature in cache",
        "Anteprime delle foto: vengono ricreate automaticamente quando servono.",
        true,
    ),
    ORPHANS(
        "Resti di app disinstallate",
        "Cartelle in Android/media di app che non sono più installate.",
        true,
    ),
    EMPTY_DIRS(
        "Cartelle vuote",
        "Cartelle che non contengono nessun file.",
        true,
    ),
    DUPLICATES(
        "File duplicati",
        "Copie identiche di altri file. Viene tenuta la copia più vecchia. Controlla prima di eliminare.",
        false,
    ),
    LARGE(
        "File molto grandi (oltre 100 MB)",
        "Video e archivi pesanti. Non sono spazzatura: elimina solo quelli che non ti servono.",
        false,
    ),
}

data class JunkItem(
    val path: String,
    val size: Long,
    val category: JunkCategory,
    val isDirectory: Boolean = false,
    val note: String? = null,
)

data class DeleteResult(val deleted: Int, val failed: Int, val freedBytes: Long)

/** Analizza la memoria condivisa (/storage/emulated/0). Richiede "Accesso a tutti i file". */
class JunkScanner(private val context: Context) {

    private val root: File = Environment.getExternalStorageDirectory()

    fun hasAccess(): Boolean = Environment.isExternalStorageManager()

    fun scan(onProgress: (scanned: Int, current: String) -> Unit): List<JunkItem> {
        val items = mutableListOf<JunkItem>()
        val androidDir = File(root, "Android").absolutePath
        val skipped = setOf(
            File(root, "Android/data").absolutePath,
            File(root, "Android/obb").absolutePath,
        )

        // 1. Resti di app disinstallate in Android/media
        val orphanDirs = mutableSetOf<String>()
        File(root, "Android/media").listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.name.contains('.') && !isInstalled(dir.name)) {
                orphanDirs += dir.absolutePath
                items += JunkItem(
                    dir.absolutePath, dirSize(dir), JunkCategory.ORPHANS, isDirectory = true,
                    note = "App: ${dir.name}",
                )
            }
        }

        // 2. Scansione di tutti i file
        val candidatesForDuplicates = mutableListOf<File>()
        var scanned = 0
        root.walkTopDown()
            .onEnter { dir -> dir.absolutePath !in skipped && dir.absolutePath !in orphanDirs }
            .maxDepth(MAX_DEPTH)
            .filter { it.isFile }
            .forEach { file ->
                scanned++
                if (scanned % 200 == 0) onProgress(scanned, file.parent ?: "")
                val name = file.name.lowercase()
                val ext = name.substringAfterLast('.', "")
                val size = file.length()
                val category = when {
                    ext in APK_EXT -> JunkCategory.APK
                    file.absolutePath.contains("/.thumbnails/") -> JunkCategory.THUMBNAILS
                    ext in TEMP_EXT || name in TEMP_NAMES -> JunkCategory.TEMP
                    size >= LARGE_THRESHOLD -> JunkCategory.LARGE
                    else -> null
                }
                if (category != null) {
                    items += JunkItem(file.absolutePath, size, category)
                }
                if (category == null || category == JunkCategory.LARGE) {
                    if (size >= DUPLICATE_MIN_SIZE && !file.absolutePath.startsWith(androidDir)) {
                        candidatesForDuplicates += file
                    }
                }
            }
        onProgress(scanned, "Ricerca duplicati…")

        // 3. Duplicati: stessa dimensione e stesso SHA-256
        candidatesForDuplicates
            .groupBy { it.length() }
            .filterValues { it.size > 1 }
            .values
            .forEach { sameSize ->
                sameSize
                    .groupBy { runCatching { sha256(it) }.getOrNull() }
                    .filterKeys { it != null }
                    .values
                    .filter { it.size > 1 }
                    .forEach { group ->
                        val original = group.minBy { it.lastModified() }
                        group.filter { it != original }.forEach { copy ->
                            items.removeAll { it.path == copy.absolutePath && it.category == JunkCategory.LARGE }
                            items += JunkItem(
                                copy.absolutePath, copy.length(), JunkCategory.DUPLICATES,
                                note = "Copia di ${original.absolutePath.removePrefix(root.absolutePath)}",
                            )
                        }
                    }
            }

        // 4. Cartelle vuote (escluse le cartelle standard e Android/)
        root.walkBottomUp()
            .onEnter { dir ->
                dir.absolutePath !in skipped && !dir.absolutePath.startsWith(androidDir)
            }
            .filter { it.isDirectory && it != root }
            .filter { it.parentFile != root || it.name !in STANDARD_DIRS }
            .filter { dir -> dir.list()?.isEmpty() == true }
            .forEach { items += JunkItem(it.absolutePath, 0, JunkCategory.EMPTY_DIRS, isDirectory = true) }

        return items.sortedWith(compareBy<JunkItem> { it.category.ordinal }.thenByDescending { it.size })
    }

    fun delete(items: Collection<JunkItem>): DeleteResult {
        var deleted = 0
        var failed = 0
        var freed = 0L
        // Prima i file, poi le cartelle (dalla più profonda)
        items.sortedWith(compareBy<JunkItem> { it.isDirectory }.thenByDescending { it.path.length })
            .forEach { item ->
                val file = File(item.path)
                // Protezione: mai uscire dalla memoria condivisa.
                if (!file.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
                    failed++
                    return@forEach
                }
                val ok = if (item.isDirectory) file.deleteRecursively() else file.delete()
                if (ok || !file.exists()) {
                    deleted++
                    freed += item.size
                } else {
                    failed++
                }
            }
        return DeleteResult(deleted, failed, freed)
    }

    private fun isInstalled(pkg: String): Boolean =
        runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess

    private fun dirSize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MAX_DEPTH = 16
        private const val LARGE_THRESHOLD = 100L * 1024 * 1024
        private const val DUPLICATE_MIN_SIZE = 512L * 1024
        private val APK_EXT = setOf("apk", "apks", "xapk", "apkm")
        private val TEMP_EXT = setOf("tmp", "temp", "log", "dmp", "crdownload", "part")
        private val TEMP_NAMES = setOf("thumbs.db", ".ds_store", "desktop.ini")
        private val STANDARD_DIRS = setOf(
            "Alarms", "Android", "Audiobooks", "DCIM", "Documents", "Download", "Movies",
            "Music", "Notifications", "Pictures", "Podcasts", "Recordings", "Ringtones",
        )
    }
}
