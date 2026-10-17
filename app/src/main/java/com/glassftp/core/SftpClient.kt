package com.glassftp.core

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Logger
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** SFTP over SSH via JSch (mwiede fork). */
class SftpClient(override val site: Site, private val trust: TrustStore) : RemoteClient {
    private var session: Session? = null
    private var channel: ChannelSftp? = null
    private val tag get() = site.displayName

    override val isConnected get() = session?.isConnected == true && channel?.isConnected == true

    private fun sftp() = channel ?: throw IOException("Not connected")

    override fun connect() {
        val jsch = JSch()
        val hostKeys = PinnedHostKeys(site.host, site.port, trust)
        jsch.hostKeyRepository = hostKeys
        if (site.privateKey.isNotBlank()) {
            jsch.addIdentity(
                "key",
                site.privateKey.toByteArray(Charsets.UTF_8),
                null,
                site.keyPassphrase.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8),
            )
        }
        val s = jsch.getSession(site.user, site.host, site.port)
        if (site.password.isNotEmpty()) s.setPassword(site.password)
        s.userInfo = PasswordUserInfo(site.password)
        s.setConfig("StrictHostKeyChecking", "yes")
        s.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
        // Like FileZilla, still talk to older servers that only offer SHA-1 RSA keys.
        s.setConfig("server_host_key", s.getConfig("server_host_key") + ",ssh-rsa")
        s.setConfig("PubkeyAcceptedAlgorithms", s.getConfig("PubkeyAcceptedAlgorithms") + ",ssh-rsa")
        s.serverAliveInterval = 30_000
        session = s

        AppLog.status(tag, "Connecting to ${site.host}:${site.port}…")
        try {
            s.connect(20_000)
        } catch (e: JSchException) {
            close()
            hostKeys.rejected?.let { throw it }
            val msg = e.message.orEmpty()
            if (msg.contains("Auth fail", true) || msg.contains("Auth cancel", true)) {
                throw LoginFailedException("Authentication failed")
            }
            throw IOException(msg.ifEmpty { "Connection failed" }, e)
        }
        AppLog.status(tag, "Connected (${s.serverVersion})")
        val ch = s.openChannel("sftp") as ChannelSftp
        ch.connect(15_000)
        if (site.encoding != Site.ENCODING_AUTO && site.encoding != "UTF-8") {
            runCatching { ch.setFilenameEncoding(site.encoding) }
        }
        channel = ch
        AppLog.status(tag, "SFTP session started")
    }

    override fun home(): String {
        if (site.remoteDir.isNotBlank()) return site.remoteDir
        return runCatching { sftp().home }.getOrNull() ?: "/"
    }

    override fun list(path: String): List<RemoteEntry> = wrap {
        AppLog.status(tag, "Listing \"$path\"…")
        val out = mutableListOf<RemoteEntry>()
        sftp().ls(path) { e ->
            if (e.filename != "." && e.filename != "..") {
                val full = RemotePath.join(path, e.filename)
                var attrs = e.attrs
                var isDir = attrs.isDir
                if (attrs.isLink) {
                    runCatching { sftp().stat(full) }.getOrNull()?.let { isDir = it.isDir; attrs = it }
                }
                out += entry(e.filename, full, attrs, isDir, e.attrs.isLink)
            }
            ChannelSftp.LsEntrySelector.CONTINUE
        }
        AppLog.status(tag, "Listed ${out.size} items")
        out
    }

    override fun stat(path: String): RemoteEntry? = try {
        val a = sftp().stat(path)
        entry(RemotePath.name(path), path, a, a.isDir, false)
    } catch (e: SftpException) {
        if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) null else throw IOException(e.message, e)
    }

    override fun mkdir(path: String) = wrap { sftp().mkdir(path) }
    override fun deleteFile(path: String) = wrap { sftp().rm(path) }
    override fun removeDir(path: String) = wrap { sftp().rmdir(path) }
    override fun rename(from: String, to: String) = wrap { sftp().rename(from, to) }
    override fun chmod(path: String, mode: Int) = wrap { sftp().chmod(mode, path) }

    override fun openRead(path: String, offset: Long): InputStream =
        wrap { sftp().get(path, null as com.jcraft.jsch.SftpProgressMonitor?, offset) }

    override fun openWrite(path: String, append: Boolean): OutputStream =
        wrap { sftp().put(path, null as com.jcraft.jsch.SftpProgressMonitor?, if (append) ChannelSftp.APPEND else ChannelSftp.OVERWRITE) }

    override fun finishRead() {}
    override fun finishWrite() {}

    override fun keepAlive() { session?.sendKeepAliveMsg() }

    override fun abort() = close()

    override fun close() {
        val had = session != null
        runCatching { channel?.disconnect() }
        runCatching { session?.disconnect() }
        channel = null
        session = null
        if (had) AppLog.status(tag, "Disconnected")
    }

    private fun entry(name: String, path: String, a: SftpATTRS, isDir: Boolean, isLink: Boolean) = RemoteEntry(
        name = name,
        path = path,
        isDir = isDir,
        size = a.size,
        modified = a.mTime * 1000L,
        permissions = a.permissionsString.orEmpty(),
        owner = a.uId.toString(),
        group = a.gId.toString(),
        isLink = isLink,
    )

    private inline fun <T> wrap(block: () -> T): T = try {
        block()
    } catch (e: SftpException) {
        throw IOException(e.message ?: "SFTP error ${e.id}", e)
    }

    private class PasswordUserInfo(private val password: String) : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String = password
        override fun promptPassword(message: String?) = password.isNotEmpty()
        override fun promptPassphrase(message: String?) = false
        override fun promptYesNo(message: String?) = false
        override fun showMessage(message: String?) { message?.let { AppLog.status("SSH", it) } }
        override fun promptKeyboardInteractive(
            destination: String?, name: String?, instruction: String?,
            prompt: Array<out String>?, echo: BooleanArray?,
        ): Array<String>? {
            if (password.isEmpty() || prompt == null) return null
            return Array(prompt.size) { password }
        }
    }

    companion object {
        init {
            JSch.setLogger(object : Logger {
                override fun isEnabled(level: Int) = level >= Logger.INFO
                override fun log(level: Int, message: String?) {
                    val m = message ?: return
                    if (level >= Logger.ERROR) AppLog.error("SSH", m) else AppLog.status("SSH", m)
                }
            })
        }
    }
}

/** Trust-on-first-use host key store backed by [TrustStore]. */
private class PinnedHostKeys(
    private val host: String,
    private val port: Int,
    private val trust: TrustStore,
) : HostKeyRepository {
    var rejected: UntrustedServerException? = null

    override fun check(host: String?, key: ByteArray): Int {
        val k = TrustStore.sshKey(this.host, port)
        val type = runCatching { HostKey(this.host, key).type }.getOrDefault("ssh")
        val fp = TrustStore.sha256Base64(key)
        val stored = trust.get(k)
        if (stored == "$type $fp") return HostKeyRepository.OK
        val kind = if (stored == null) UntrustedServerException.Kind.HOST_KEY_NEW else UntrustedServerException.Kind.HOST_KEY_CHANGED
        rejected = UntrustedServerException(kind, k, "$type $fp", "$type\n$fp")
        return if (stored == null) HostKeyRepository.NOT_INCLUDED else HostKeyRepository.CHANGED
    }

    override fun add(hostkey: HostKey?, ui: UserInfo?) {}
    override fun remove(host: String?, type: String?) {}
    override fun remove(host: String?, type: String?, key: ByteArray?) {}
    override fun getKnownHostsRepositoryID() = "glassftp"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}
