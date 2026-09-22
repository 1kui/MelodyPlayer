package com.melody.player

import android.app.Application

/** 只保留全局 Application 实例，方便在没有 Context 的地方（极少）取用。 */
class MelodyApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: MelodyApp
            private set
    }
}
