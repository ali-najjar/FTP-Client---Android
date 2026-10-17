package com.glassftp

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import com.glassftp.core.Prefs
import com.glassftp.core.SiteStore
import com.glassftp.core.TrustStore
import com.glassftp.transfer.TransferManager
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        sites = SiteStore(this)
        trust = TrustStore(this)
        transfers = TransferManager(this, prefs, trust)
    }

    companion object {
        lateinit var instance: App; private set
        lateinit var prefs: Prefs; private set
        lateinit var sites: SiteStore; private set
        lateinit var trust: TrustStore; private set
        lateinit var transfers: TransferManager; private set

        /** Applies the in-app language override, if any. */
        fun localized(base: Context): Context {
            val lang = base.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("language", "") ?: ""
            if (lang.isEmpty()) return base
            val locale = Locale.forLanguageTag(lang)
            Locale.setDefault(locale)
            val config = Configuration(base.resources.configuration)
            config.setLocale(locale)
            config.setLayoutDirection(locale)
            return base.createConfigurationContext(config)
        }
    }
}
