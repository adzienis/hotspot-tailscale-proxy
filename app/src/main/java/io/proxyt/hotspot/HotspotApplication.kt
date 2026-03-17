package io.proxyt.hotspot

import android.app.Application
import android.content.Context

class HotspotApplication : Application(), HotspotDependencyProvider {
    override lateinit var hotspotRuntimeDependencies: HotspotRuntimeDependencies

    override fun onCreate() {
        super.onCreate()
        hotspotRuntimeDependencies = HotspotRuntimeDependencies.create(this)
    }
}

fun Context.hotspotRuntimeDependencies(): HotspotRuntimeDependencies =
    (applicationContext as HotspotDependencyProvider).hotspotRuntimeDependencies
