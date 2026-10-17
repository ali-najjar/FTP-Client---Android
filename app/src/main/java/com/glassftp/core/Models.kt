package com.glassftp.core

import org.json.JSONObject
import java.util.UUID

enum class Protocol(val defaultPort: Int, val label: String) {
    FTP(21, "FTP"),
    FTPES(21, "FTPES"),   // FTP over explicit TLS (AUTH TLS)
    FTPS(990, "FTPS"),    // FTP over implicit TLS
    SFTP(22, "SFTP");

    val isFtp get() = this != SFTP
    val isTls get() = this == FTPES || this == FTPS
}

/** A saved server, FileZilla's "Site Manager" entry. */
data class Site(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val protocol: Protocol = Protocol.FTP,
    val host: String = "",
    val port: Int = protocol.defaultPort,
    val user: String = "",
    val password: String = "",
    val anonymous: Boolean = false,
    val savePassword: Boolean = true,
    val passive: Boolean = true,
    val encoding: String = ENCODING_AUTO,
    val remoteDir: String = "",
    val privateKey: String = "",
    val keyPassphrase: String = "",
    val lastUsed: Long = 0L,
) {
    val displayName get() = name.ifBlank { host }
    val effectiveUser get() = if (anonymous && protocol.isFtp) "anonymous" else user
    val subtitle get() = buildString {
        if (effectiveUser.isNotEmpty()) append(effectiveUser).append('@')
        append(host)
        if (port != protocol.defaultPort) append(':').append(port)
    }

    /** Secrets are returned separately so the caller can encrypt them. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("protocol", protocol.name)
        put("host", host); put("port", port); put("user", user)
        put("anonymous", anonymous); put("savePassword", savePassword)
        put("passive", passive); put("encoding", encoding); put("remoteDir", remoteDir)
        put("lastUsed", lastUsed)
    }

    companion object {
        const val ENCODING_AUTO = "Auto"
        val ENCODINGS = listOf(ENCODING_AUTO, "UTF-8", "windows-1256", "ISO-8859-1")

        fun fromJson(o: JSONObject) = Site(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name"),
            protocol = runCatching { Protocol.valueOf(o.optString("protocol")) }.getOrDefault(Protocol.FTP),
            host = o.optString("host"),
            port = o.optInt("port", 21),
            user = o.optString("user"),
            anonymous = o.optBoolean("anonymous"),
            savePassword = o.optBoolean("savePassword", true),
            passive = o.optBoolean("passive", true),
            encoding = o.optString("encoding", ENCODING_AUTO),
            remoteDir = o.optString("remoteDir"),
            lastUsed = o.optLong("lastUsed"),
        )
    }
}

data class RemoteEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val permissions: String = "",
    val owner: String = "",
    val group: String = "",
    val isLink: Boolean = false,
    val linkTarget: String = "",
) {
    val isHidden get() = name.startsWith(".")
}

object RemotePath {
    fun join(dir: String, name: String): String = when {
        name.startsWith("/") -> name
        dir.endsWith("/") -> dir + name
        else -> "$dir/$name"
    }

    fun parent(path: String): String {
        val p = path.trimEnd('/')
        if (p.isEmpty()) return "/"
        val i = p.lastIndexOf('/')
        return if (i <= 0) "/" else p.substring(0, i)
    }

    fun name(path: String): String = path.trimEnd('/').substringAfterLast('/')

    /** "/a/b/c" -> ["/", "/a", "/a/b", "/a/b/c"] */
    fun crumbs(path: String): List<String> {
        val out = mutableListOf("/")
        var acc = ""
        path.split('/').filter { it.isNotEmpty() }.forEach { acc += "/$it"; out += acc }
        return out
    }
}

class UntrustedServerException(
    val kind: Kind,
    val key: String,
    val fingerprint: String,
    val details: String,
) : Exception("Untrusted server identity") {
    enum class Kind { CERTIFICATE, HOST_KEY_NEW, HOST_KEY_CHANGED }
}

class LoginFailedException(message: String) : Exception(message)
