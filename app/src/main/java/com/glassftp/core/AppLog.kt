package com.glassftp.core

import android.os.Handler
import android.os.Looper

/** FileZilla-style message log: status lines, commands, replies and errors. */
object AppLog {
    enum class Kind { STATUS, COMMAND, REPLY, ERROR }

    class Entry(val time: Long, val kind: Kind, val source: String, val text: String)

    private const val MAX = 2000
    private val entries = ArrayDeque<Entry>()
    private val listeners = mutableListOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var notifyPosted = false

    fun status(source: String, text: String) = add(Kind.STATUS, source, text)
    fun command(source: String, text: String) = add(Kind.COMMAND, source, text)
    fun reply(source: String, text: String) = add(Kind.REPLY, source, text)
    fun error(source: String, text: String) = add(Kind.ERROR, source, text)

    fun add(kind: Kind, source: String, text: String) {
        val clean = text.trimEnd('\r', '\n')
        if (clean.isEmpty()) return
        synchronized(entries) {
            entries.addLast(Entry(System.currentTimeMillis(), kind, source, clean))
            while (entries.size > MAX) entries.removeFirst()
            if (notifyPosted) return
            notifyPosted = true
        }
        main.postDelayed({
            synchronized(entries) { notifyPosted = false }
            listeners.toList().forEach { it() }
        }, 120)
    }

    fun snapshot(): List<Entry> = synchronized(entries) { entries.toList() }

    fun clear() {
        synchronized(entries) { entries.clear() }
        listeners.toList().forEach { it() }
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
}
