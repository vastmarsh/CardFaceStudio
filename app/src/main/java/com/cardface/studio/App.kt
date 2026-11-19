package com.cardface.studio

import android.app.Application
import rikka.shizuku.ShizukuProvider

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            ShizukuProvider.enableMultiProcessSupport(true)
        } catch (_: Throwable) {
        }
    }
}
