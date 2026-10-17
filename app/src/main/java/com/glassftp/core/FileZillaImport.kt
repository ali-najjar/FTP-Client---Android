package com.glassftp.core

import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * Reads FileZilla's sitemanager.xml (or an exported FileZilla.xml) so users can
 * bring their existing Site Manager entries over.
 */
object FileZillaImport {
    fun parse(input: InputStream): List<Site> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        val sites = mutableListOf<Site>()
        val folders = ArrayDeque<String>()
        var fields: MutableMap<String, String>? = null
        var passEncoding = ""
        var tag = ""
        var folderPendingName = false

        while (true) {
            when (parser.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> {
                    tag = parser.name
                    when {
                        tag == "Folder" -> { folders.addLast(""); folderPendingName = true }
                        tag == "Server" -> { fields = mutableMapOf(); folderPendingName = false }
                        tag == "Pass" -> passEncoding = parser.getAttributeValue(null, "encoding").orEmpty()
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text ?: ""
                    val f = fields
                    if (f != null && tag.isNotEmpty()) {
                        f[tag] = (f[tag] ?: "") + text
                    } else if (folderPendingName && text.isNotBlank() && folders.isNotEmpty()) {
                        folders.removeLast(); folders.addLast(text.trim())
                        folderPendingName = false
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "Server" -> {
                            fields?.let { toSite(it, passEncoding, folders.filter(String::isNotEmpty)) }?.let(sites::add)
                            fields = null
                            passEncoding = ""
                        }
                        "Folder" -> if (folders.isNotEmpty()) folders.removeLast()
                    }
                    tag = ""
                }
            }
        }
        return sites
    }

    private fun toSite(f: Map<String, String>, passEncoding: String, folders: List<String>): Site? {
        val host = f["Host"]?.trim().orEmpty()
        if (host.isEmpty()) return null
        val protocol = when (f["Protocol"]?.trim()) {
            "1" -> Protocol.SFTP
            "3" -> Protocol.FTPS
            "4" -> Protocol.FTPES
            else -> Protocol.FTP
        }
        val logon = f["Logontype"]?.trim()
        val rawPass = f["Pass"]?.trim().orEmpty()
        val pass = when (passEncoding) {
            "base64" -> runCatching { String(Base64.decode(rawPass, Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault("")
            "crypt" -> "" // protected with a FileZilla master password
            else -> rawPass
        }
        val name = f["Name"]?.trim().orEmpty()
        val encoding = when (f["EncodingType"]?.trim()) {
            "UTF-8" -> "UTF-8"
            "Custom" -> f["CustomEncoding"]?.trim()?.takeIf { it in Site.ENCODINGS } ?: Site.ENCODING_AUTO
            else -> Site.ENCODING_AUTO
        }
        return Site(
            name = (folders + name.ifEmpty { host }).joinToString(" / "),
            protocol = protocol,
            host = host,
            port = f["Port"]?.trim()?.toIntOrNull() ?: protocol.defaultPort,
            user = f["User"]?.trim().orEmpty(),
            password = pass,
            anonymous = logon == "0",
            savePassword = logon != "2" && logon != "3",
            passive = f["PasvMode"]?.trim() != "MODE_ACTIVE",
            encoding = encoding,
            remoteDir = parseRemoteDir(f["RemoteDir"]?.trim().orEmpty()),
        )
    }

    /** FileZilla serializes paths as "<type> <prefixLen> [prefix] (<len> <segment>)*". */
    fun parseRemoteDir(s: String): String {
        if (s.isEmpty()) return ""
        var i = 0
        fun readInt(): Int? {
            while (i < s.length && s[i] == ' ') i++
            val start = i
            while (i < s.length && s[i].isDigit()) i++
            return s.substring(start, i).toIntOrNull()
        }
        fun readChars(n: Int): String {
            if (i < s.length && s[i] == ' ') i++
            val end = (i + n).coerceAtMost(s.length)
            return s.substring(i, end).also { i = end }
        }
        readInt() ?: return ""
        val prefixLen = readInt() ?: return ""
        val prefix = if (prefixLen > 0) readChars(prefixLen) else ""
        val segments = mutableListOf<String>()
        while (i < s.length) {
            val n = readInt() ?: break
            segments += readChars(n)
        }
        return prefix + "/" + segments.joinToString("/")
    }
}
