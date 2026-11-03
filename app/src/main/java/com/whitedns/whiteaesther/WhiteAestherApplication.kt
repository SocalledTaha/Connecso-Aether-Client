package com.whitedns.whiteaesther

import android.app.Application
import com.whitedns.whiteaesther.data.InstalledApps

class WhiteAestherApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        InstalledApps.registerPackageChanges(this)
    }
}
