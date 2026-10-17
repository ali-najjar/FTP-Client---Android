package com.glassftp.core

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Data-connection socket factory for FTPS that resumes the control connection's TLS session.
 *
 * Many servers (vsftpd, ProFTPD, FileZilla Server) refuse a data connection that does not
 * reuse the control session ("522 SSL connection failed: session reuse required").
 * Conscrypt (Android's TLS stack) looks up resumable sessions by hostname and by the *wrapped
 * socket's* port, so data sockets are wrapped with the control hostname and a plain socket
 * that reports the control port. JSSE uses the port passed to createSocket, which also matches.
 */
class SessionReuseSocketFactory(
    private val tls: SSLSocketFactory,
    private val controlHost: String,
    private val controlPort: Int,
) : SocketFactory() {
    override fun createSocket(): Socket = ReusingSslSocket(tls, controlHost, controlPort)

    override fun createSocket(host: String, port: Int): Socket = createSocket().apply { connect(java.net.InetSocketAddress(host, port)) }
    override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket = createSocket(host, port)
    override fun createSocket(host: InetAddress, port: Int): Socket = createSocket().apply { connect(java.net.InetSocketAddress(host, port)) }
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket = createSocket(address, port)
}

/** Plain TCP until connected, then a TLS socket keyed to the control connection. */
private class ReusingSslSocket(
    private val tls: SSLSocketFactory,
    private val sessionHost: String,
    private val sessionPort: Int,
) : SSLSocket() {
    private val plain = object : Socket() {
        override fun getPort() = sessionPort
    }
    private var ssl: SSLSocket? = null

    private fun s(): SSLSocket = ssl ?: throw IllegalStateException("Not connected")

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        plain.connect(endpoint, timeout)
        ssl = tls.createSocket(plain, sessionHost, sessionPort, true) as SSLSocket
    }

    override fun connect(endpoint: SocketAddress?) = connect(endpoint, 0)
    override fun bind(bindpoint: SocketAddress?) = plain.bind(bindpoint)

    override fun getInputStream(): InputStream = s().inputStream
    override fun getOutputStream(): OutputStream = s().outputStream
    override fun close() { ssl?.close() ?: plain.close() }
    override fun isConnected() = plain.isConnected
    override fun isClosed() = ssl?.isClosed ?: plain.isClosed
    override fun isBound() = plain.isBound
    override fun shutdownInput() = plain.shutdownInput()
    override fun shutdownOutput() = plain.shutdownOutput()
    override fun isInputShutdown() = plain.isInputShutdown
    override fun isOutputShutdown() = plain.isOutputShutdown
    override fun getInetAddress(): InetAddress? = plain.inetAddress
    override fun getLocalAddress(): InetAddress? = plain.localAddress
    override fun getPort() = plain.port
    override fun getLocalPort() = plain.localPort
    override fun getRemoteSocketAddress(): SocketAddress? = plain.remoteSocketAddress
    override fun getLocalSocketAddress(): SocketAddress? = plain.localSocketAddress
    override fun setSoTimeout(timeout: Int) { ssl?.soTimeout = timeout; plain.soTimeout = timeout }
    override fun getSoTimeout() = plain.soTimeout
    override fun setReceiveBufferSize(size: Int) { plain.receiveBufferSize = size }
    override fun getReceiveBufferSize() = plain.receiveBufferSize
    override fun setSendBufferSize(size: Int) { plain.sendBufferSize = size }
    override fun getSendBufferSize() = plain.sendBufferSize
    override fun setTcpNoDelay(on: Boolean) { plain.tcpNoDelay = on }
    override fun getTcpNoDelay() = plain.tcpNoDelay
    override fun setKeepAlive(on: Boolean) { plain.keepAlive = on }
    override fun getKeepAlive() = plain.keepAlive
    override fun setSoLinger(on: Boolean, linger: Int) = plain.setSoLinger(on, linger)
    override fun getSoLinger() = plain.soLinger

    override fun getSupportedCipherSuites(): Array<String> = s().supportedCipherSuites
    override fun getEnabledCipherSuites(): Array<String> = s().enabledCipherSuites
    override fun setEnabledCipherSuites(suites: Array<out String>?) { s().enabledCipherSuites = suites }
    override fun getSupportedProtocols(): Array<String> = s().supportedProtocols
    override fun getEnabledProtocols(): Array<String> = s().enabledProtocols
    override fun setEnabledProtocols(protocols: Array<out String>?) { s().enabledProtocols = protocols }
    override fun getSession(): SSLSession = s().session
    override fun getHandshakeSession(): SSLSession? = s().handshakeSession
    override fun addHandshakeCompletedListener(l: HandshakeCompletedListener?) = s().addHandshakeCompletedListener(l)
    override fun removeHandshakeCompletedListener(l: HandshakeCompletedListener?) = s().removeHandshakeCompletedListener(l)
    override fun startHandshake() = s().startHandshake()
    override fun setUseClientMode(mode: Boolean) { s().useClientMode = mode }
    override fun getUseClientMode() = s().useClientMode
    override fun setNeedClientAuth(need: Boolean) { s().needClientAuth = need }
    override fun getNeedClientAuth() = s().needClientAuth
    override fun setWantClientAuth(want: Boolean) { s().wantClientAuth = want }
    override fun getWantClientAuth() = s().wantClientAuth
    override fun setEnableSessionCreation(flag: Boolean) { s().enableSessionCreation = flag }
    override fun getEnableSessionCreation() = s().enableSessionCreation
    override fun getSSLParameters(): SSLParameters = s().sslParameters
    override fun setSSLParameters(params: SSLParameters?) { s().sslParameters = params }
    override fun toString() = "ReusingSslSocket[$plain]"
}
