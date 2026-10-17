package com.glassftp.core

import java.text.DateFormat
import java.util.Date
import java.util.Locale

object Format {
    fun size(bytes: Long): String {
        if (bytes < 0) return "—"
        if (bytes < 1000) return String.format(Locale.getDefault(), "%d B", bytes)
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes / 1000.0
        var u = 0
        while (v >= 1000 && u < units.size - 1) { v /= 1000; u++ }
        return String.format(Locale.getDefault(), if (v < 10) "%.1f %s" else "%.0f %s", v, units[u])
    }

    fun speed(bytesPerSec: Double): String = size(bytesPerSec.toLong()) + "/s"

    fun duration(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return if (s >= 3600) String.format(Locale.getDefault(), "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60)
    }

    fun date(millis: Long): String {
        if (millis <= 0) return ""
        val now = System.currentTimeMillis()
        val df = if (now - millis < 20 * 3600_000L && now >= millis)
            DateFormat.getTimeInstance(DateFormat.SHORT)
        else DateFormat.getDateInstance(DateFormat.MEDIUM)
        return df.format(Date(millis))
    }

    fun dateTime(millis: Long): String =
        if (millis <= 0) "—" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

    fun octal(perms: String): String {
        if (perms.length < 10) return ""
        var v = 0
        for (i in 1..9) if (perms[i] != '-') v = v or (1 shl (9 - i))
        return "%03o".format(v)
    }
}

object FileKind {
    enum class Kind { FOLDER, IMAGE, VIDEO, AUDIO, ARCHIVE, CODE, TEXT, PDF, APK, DOC, OTHER }

    private val map = buildMap {
        listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg", "ico", "tiff").forEach { put(it, Kind.IMAGE) }
        listOf("mp4", "mkv", "mov", "avi", "webm", "m4v", "3gp", "flv", "wmv").forEach { put(it, Kind.VIDEO) }
        listOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "opus", "wma").forEach { put(it, Kind.AUDIO) }
        listOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst").forEach { put(it, Kind.ARCHIVE) }
        listOf("kt", "java", "js", "ts", "py", "php", "c", "cpp", "h", "cs", "go", "rs", "rb", "sh", "html", "htm",
            "css", "scss", "json", "xml", "yml", "yaml", "sql", "swift", "dart", "vue", "jsx", "tsx", "toml", "gradle").forEach { put(it, Kind.CODE) }
        listOf("txt", "md", "log", "ini", "conf", "cfg", "csv", "env", "htaccess", "properties").forEach { put(it, Kind.TEXT) }
        put("pdf", Kind.PDF)
        put("apk", Kind.APK)
        listOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "rtf", "pages", "numbers", "key").forEach { put(it, Kind.DOC) }
    }

    fun of(name: String, isDir: Boolean): Kind {
        if (isDir) return Kind.FOLDER
        val ext = name.substringAfterLast('.', "").lowercase()
        if (name.startsWith(".") && !name.substring(1).contains('.')) return Kind.TEXT
        return map[ext] ?: Kind.OTHER
    }

    fun isEditable(name: String) = of(name, false).let { it == Kind.TEXT || it == Kind.CODE }

    fun mime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (of(name, false)) {
            Kind.TEXT, Kind.CODE -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}
