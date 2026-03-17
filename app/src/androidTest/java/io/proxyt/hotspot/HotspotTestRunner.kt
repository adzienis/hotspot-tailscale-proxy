package io.proxyt.hotspot

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

class HotspotTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String?, context: Context): Application {
        return super.newApplication(cl, HotspotApplication::class.java.name, context)
    }
}
