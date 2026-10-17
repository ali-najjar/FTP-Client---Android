package com.glassftp.core

import org.apache.commons.net.ProtocolCommandEvent
import org.apache.commons.net.ProtocolCommandListener
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient as CommonsFtp
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.text.DateFormat
import java.time.Duration
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** FTP, FTPES (explicit TLS) and FTPS (implicit TLS) on top of Apache Commons Net. */
class FtpClient(override val site: Site, private val trust: TrustStore) : RemoteClient {
    private var ftp: CommonsFtp? = null
    private var trustManager: PinningTrustManager? = null
    private val tag get() = site.displayName

    override val isConnected get() = ftp?.isConnected == true

    private fun client() = ftp ?: throw IOException("Not connected")

    override fun connect() {
        val c = when (site.protocol) {
            Protocol.FTPES -> FTPSClient("TLS", false)
            Protocol.FTPS -> FTPSClient("TLS", true)
            else -> CommonsFtp()
        }
        if (c is FTPSClient) {
            val tm = PinningTrustManager(site.host, site.port, trust)
            trustManager = tm
            c.trustManager = tm
            c.setEndpointCheckingEnabled(false) // hostname is checked by PinningTrustManager
        }
        if (site.encoding == Site.ENCODING_AUTO) {
            c.controlEncoding = "UTF-8"
            c.autodetectUTF8 = true
        } else {
            c.controlEncoding = site.encoding
        }
        c.connectTimeout = 20_000
        c.defaultTimeout = 30_000
        c.setDataTimeout(Duration.ofSeconds(60))
        c.bufferSize = 64 * 1024
        c.setListHiddenFiles(true)
        c.addProtocolCommandListener(LogListener(tag))
        ftp = c

        AppLog.status(tag, "Connecting to ${site.host}:${site.port}…")
        try {
            c.connect(site.host, site.port)
        } catch (e: IOException) {
            val rejected = trustManager?.rejected
            if (rejected != null) throw rejected
            throw e
        }
        if (!FTPReply.isPositiveCompletion(c.replyCode)) {
            val msg = c.replyString?.trim().orEmpty()
            close()
            throw IOException(msg.ifEmpty { "Server refused connection" })
        }
        if (c is FTPSClient) {
            c.execPBSZ(0)
            c.execPROT("P")
        }
        val user = site.effectiveUser
        val pass = if (site.anonymous && site.protocol.isFtp) "glassftp@example.com" else site.password
        if (!c.login(user, pass)) {
            val msg = c.replyString?.trim().orEmpty()
            close()
            throw LoginFailedException(msg.ifEmpty { "Login incorrect" })
        }
        if (site.encoding == Site.ENCODING_AUTO && c.hasFeature("UTF8")) {
            c.sendCommand("OPTS UTF8 ON")
        }
        c.setFileType(FTP.BINARY_FILE_TYPE)
        if (site.passive) c.enterLocalPassiveMode() else c.enterLocalActiveMode()
        c.setControlKeepAliveTimeout(Duration.ofSeconds(30))
        AppLog.status(tag, "Logged in")
    }

    override fun home(): String {
        if (site.remoteDir.isNotBlank()) return site.remoteDir
        return client().printWorkingDirectory()?.takeIf { it.isNotBlank() } ?: "/"
    }

    private var mlsd: Boolean? = null

    private fun supportsMlsd(): Boolean =
        mlsd ?: runCatching { client().hasFeature("MLSD") }.getOrDefault(false).also { mlsd = it }

    override fun list(path: String): List<RemoteEntry> {
        val c = client()
        AppLog.status(tag, "Retrieving directory listing of \"$path\"…")
        val files: Array<FTPFile> = if (supportsMlsd()) {
            c.mlistDir(path)
        } else {
            if (!c.changeWorkingDirectory(path)) throw IOException(c.replyString.trim())
            c.listFiles()
        }
        if (files.isEmpty() && !FTPReply.isPositiveCompletion(c.replyCode)) {
            throw IOException(c.replyString.trim())
        }
        return files.filter { it.name != "." && it.name != ".." && it.name.isNotEmpty() }
            .map { it.toEntry(RemotePath.join(path, RemotePath.name(it.name))) }
    }

    override fun stat(path: String): RemoteEntry? {
        val c = client()
        if (supportsMlsd() || runCatching { c.hasFeature("MLST") }.getOrDefault(false)) {
            return c.mlistFile(path)?.toEntry(path)
        }
        val parent = RemotePath.parent(path)
        val name = RemotePath.name(path)
        return runCatching { list(parent) }.getOrNull()?.firstOrNull { it.name == name }
    }

    override fun mkdir(path: String) = check(client().makeDirectory(path))
    override fun deleteFile(path: String) = check(client().deleteFile(path))
    override fun removeDir(path: String) = check(client().removeDirectory(path))
    override fun rename(from: String, to: String) = check(client().rename(from, to))
    override fun chmod(path: String, mode: Int) = check(client().sendSiteCommand("CHMOD %o %s".format(mode, path)))

    override fun openRead(path: String, offset: Long): InputStream {
        val c = client()
        c.restartOffset = offset
        return c.retrieveFileStream(path) ?: throw IOException(c.replyString.trim())
    }

    override fun openWrite(path: String, append: Boolean): OutputStream {
        val c = client()
        val out = if (append) c.appendFileStream(path) else c.storeFileStream(path)
        return out ?: throw IOException(c.replyString.trim())
    }

    override fun finishRead() = check(client().completePendingCommand())
    override fun finishWrite() = check(client().completePendingCommand())

    override fun keepAlive() { client().sendNoOp() }

    override fun abort() {
        runCatching { ftp?.abort() }
        close()
    }

    override fun close() {
        val c = ftp ?: return
        ftp = null
        runCatching { if (c.isConnected) c.logout() }
        runCatching { c.disconnect() }
        AppLog.status(tag, "Disconnected")
    }

    private fun check(ok: Boolean) {
        if (!ok) throw IOException(client().replyString?.trim().orEmpty().ifEmpty { "Command failed" })
    }

    private fun FTPFile.toEntry(path: String): RemoteEntry {
        val perms = if (isValid) buildString {
            append(if (isDirectory) 'd' else if (isSymbolicLink) 'l' else '-')
            for (access in intArrayOf(FTPFile.USER_ACCESS, FTPFile.GROUP_ACCESS, FTPFile.WORLD_ACCESS)) {
                append(if (hasPermission(access, FTPFile.READ_PERMISSION)) 'r' else '-')
                append(if (hasPermission(access, FTPFile.WRITE_PERMISSION)) 'w' else '-')
                append(if (hasPermission(access, FTPFile.EXECUTE_PERMISSION)) 'x' else '-')
            }
        } else ""
        return RemoteEntry(
            name = RemotePath.name(name),
            path = path,
            isDir = isDirectory,
            size = size,
            modified = timestamp?.timeInMillis ?: 0L,
            permissions = perms,
            owner = user.orEmpty(),
            group = group.orEmpty(),
            isLink = isSymbolicLink,
            linkTarget = link.orEmpty(),
        )
    }

    private class LogListener(val tag: String) : ProtocolCommandListener {
        override fun protocolCommandSent(event: ProtocolCommandEvent) {
            val msg = event.message.orEmpty()
            AppLog.command(tag, if (msg.startsWith("PASS", true)) "PASS ********" else msg)
        }

        override fun protocolReplyReceived(event: ProtocolCommandEvent) {
            event.message.orEmpty().lineSequence().forEach { AppLog.reply(tag, it) }
        }
    }
}

/**
 * Accepts certificates the system trusts (with a matching hostname) or the user
 * pinned before. Otherwise records the certificate so the UI can ask the user.
 */
class PinningTrustManager(
    private val host: String,
    private val port: Int,
    private val trust: TrustStore,
) : X509TrustManager {
    var rejected: UntrustedServerException? = null
        private set

    private val system: X509TrustManager? = runCatching {
        val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        f.init(null as KeyStore?)
        f.trustManagers.filterIsInstance<X509TrustManager>().first()
    }.getOrNull()

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {}

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("Empty chain")
        val key = TrustStore.certKey(host, port)
        val fp = TrustStore.sha256Hex(leaf.encoded)
        if (trust.get(key) == fp) return

        var reason: String? = null
        try {
            system?.checkServerTrusted(chain, authType) ?: throw CertificateException("No system trust")
            if (!HostMatcher.matches(leaf, host)) reason = "Hostname mismatch"
        } catch (e: CertificateException) {
            reason = e.message ?: "Not trusted"
        }
        if (reason == null) return

        val df = DateFormat.getDateInstance(DateFormat.MEDIUM)
        val details = buildString {
            append("Subject: ").append(leaf.subjectX500Principal.name).append('\n')
            append("Issuer: ").append(leaf.issuerX500Principal.name).append('\n')
            append("Valid: ").append(df.format(leaf.notBefore)).append(" – ").append(df.format(leaf.notAfter)).append('\n')
            append("Reason: ").append(reason)
        }
        rejected = UntrustedServerException(UntrustedServerException.Kind.CERTIFICATE, key, fp, details)
        throw CertificateException(reason)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = system?.acceptedIssuers ?: emptyArray()
}

object HostMatcher {
    fun matches(cert: X509Certificate, host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        val names = runCatching { cert.subjectAlternativeNames }.getOrNull()
            ?.filter { it.size >= 2 && (it[0] == 2 || it[0] == 7) }
            ?.map { it[1].toString().lowercase() }
            .orEmpty()
        val candidates = names.ifEmpty {
            Regex("CN=([^,]+)").find(cert.subjectX500Principal.name)?.groupValues?.get(1)?.let { listOf(it.lowercase()) }.orEmpty()
        }
        return candidates.any { pattern ->
            if (pattern.startsWith("*.")) {
                val suffix = pattern.substring(1)
                h.endsWith(suffix) && h.substring(0, h.length - suffix.length).let { it.isNotEmpty() && !it.contains('.') }
            } else pattern == h
        }
    }
}
