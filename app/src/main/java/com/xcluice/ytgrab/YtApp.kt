package com.xcluice.ytgrab

import android.app.Application

class YtApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Engine.init(this)
        Extractor.init()
    }
}
