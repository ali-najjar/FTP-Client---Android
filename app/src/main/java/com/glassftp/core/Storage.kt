package com.glassftp.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts secrets with an AES-GCM key that never leaves the Android Keystore.
 * Devices with a broken Keystore fall back to a key in app-private storage
 * rather than losing saved passwords.
 */
object SecretBox {
    private const val ALIAS = "glassftp.secrets"
    private const val KEYSTORE_PREFIX = "k1:"
    private const val SOFT_PREFIX = "s1:"

    private fun keystoreKey(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }.getOrNull()

    private fun softKey(): SecretKey {
        val prefs = com.glassftp.App.instance.getSharedPreferences("secret_key", Context.MODE_PRIVATE)
        val stored = prefs.getString("k", null)
        val raw = if (stored != null) Base64.decode(stored, Base64.NO_WRAP) else ByteArray(32).also {
            java.security.SecureRandom().nextBytes(it)
            prefs.edit().putString("k", Base64.encodeToString(it, Base64.NO_WRAP)).apply()
        }
        return javax.crypto.spec.SecretKeySpec(raw, "AES")
    }

    fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        val hw = keystoreKey()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, hw ?: softKey())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return (if (hw != null) KEYSTORE_PREFIX else SOFT_PREFIX) + Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun open(sealed: String): String {
        if (sealed.isEmpty()) return ""
        return runCatching {
            val soft = sealed.startsWith(SOFT_PREFIX)
            val raw = Base64.decode(sealed.substringAfter(':'), Base64.NO_WRAP)
            val key = if (soft) softKey() else keystoreKey() ?: return ""
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        }.getOrDefault("")
    }
}

class SiteStore(context: Context) {
    private val prefs = context.getSharedPreferences("sites", Context.MODE_PRIVATE)
    private val listeners = mutableListOf<() -> Unit>()
    private var cache: List<Site>? = null

    fun all(): List<Site> = cache ?: load().also { cache = it }

    fun get(id: String) = all().firstOrNull { it.id == id }

    fun save(site: Site) {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.id == site.id }
        if (i >= 0) list[i] = site else list += site
        persist(list)
    }

    fun delete(id: String) = persist(all().filterNot { it.id == id })

    fun touch(id: String) {
        get(id)?.let { save(it.copy(lastUsed = System.currentTimeMillis())) }
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    private fun load(): List<Site> {
        val arr = runCatching { JSONArray(prefs.getString("list", "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Site.fromJson(o).copy(
                password = SecretBox.open(o.optString("pass")),
                privateKey = SecretBox.open(o.optString("key")),
                keyPassphrase = SecretBox.open(o.optString("keyPass")),
            )
        }
    }

    private fun persist(list: List<Site>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(s.toJson().apply {
                put("pass", if (s.savePassword) SecretBox.seal(s.password) else "")
                put("key", SecretBox.seal(s.privateKey))
                put("keyPass", if (s.savePassword) SecretBox.seal(s.keyPassphrase) else "")
            })
        }
        prefs.edit().putString("list", arr.toString()).apply()
        cache = list
        listeners.toList().forEach { it() }
    }
}

enum class ExistsAction { OVERWRITE, RESUME, RENAME, SKIP }
enum class SortMode { NAME, DATE, SIZE, TYPE }

class Prefs(private val context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 0 = follow system, 1 = light, 2 = dark */
    var theme: Int
        get() = p.getInt("theme", 0)
        set(v) = p.edit().putInt("theme", v).apply()

    /** "" = system, "en", "fa" */
    var language: String
        get() = p.getString("language", "") ?: ""
        set(v) = p.edit().putString("language", v).commit().let { }

    var showHidden: Boolean
        get() = p.getBoolean("showHidden", false)
        set(v) = p.edit().putBoolean("showHidden", v).apply()

    var sortMode: SortMode
        get() = runCatching { SortMode.valueOf(p.getString("sort", "NAME")!!) }.getOrDefault(SortMode.NAME)
        set(v) = p.edit().putString("sort", v.name).apply()

    var sortDescending: Boolean
        get() = p.getBoolean("sortDesc", false)
        set(v) = p.edit().putBoolean("sortDesc", v).apply()

    var concurrentTransfers: Int
        get() = p.getInt("concurrent", 2)
        set(v) = p.edit().putInt("concurrent", v.coerceIn(1, 5)).apply()

    var existsAction: ExistsAction
        get() = runCatching { ExistsAction.valueOf(p.getString("exists", "OVERWRITE")!!) }.getOrDefault(ExistsAction.OVERWRITE)
        set(v) = p.edit().putString("exists", v.name).apply()

    var keepScreenOn: Boolean
        get() = p.getBoolean("keepScreenOn", false)
        set(v) = p.edit().putBoolean("keepScreenOn", v).apply()

    var downloadDir: File
        get() = p.getString("downloadDir", null)?.let(::File) ?: defaultDownloadDir()
        set(v) = p.edit().putString("downloadDir", v.absolutePath).apply()

    fun defaultDownloadDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "GlassFTP")

    /** Where downloads go when storage permission is missing. */
    fun privateDownloadDir(): File =
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.filesDir, "downloads")
}

/** Remembers server identities the user explicitly trusted (TOFU). */
class TrustStore(context: Context) {
    private val p = context.getSharedPreferences("trust", Context.MODE_PRIVATE)

    fun get(key: String): String? = p.getString(key, null)
    fun trust(key: String, fingerprint: String) = p.edit().putString(key, fingerprint).apply()
    fun clear() = p.edit().clear().apply()
    fun count() = p.all.size

    companion object {
        fun certKey(host: String, port: Int) = "cert:${host.lowercase()}:$port"
        fun sshKey(host: String, port: Int) = "ssh:${host.lowercase()}:$port"

        fun sha256Hex(data: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(data).joinToString(":") { "%02X".format(it) }

        fun sha256Base64(data: ByteArray): String =
            "SHA256:" + Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(data), Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
