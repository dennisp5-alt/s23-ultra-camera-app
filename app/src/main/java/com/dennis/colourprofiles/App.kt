package com.dennis.colourprofiles

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.seed(this)
        ShizukuBridge.init(this)
    }
}
