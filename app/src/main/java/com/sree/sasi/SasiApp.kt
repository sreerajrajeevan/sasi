package com.sree.sasi

import android.app.Application

class SasiApp : Application() {

    lateinit var prefs: com.sree.sasi.data.Prefs
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = com.sree.sasi.data.Prefs(this)
    }
}
