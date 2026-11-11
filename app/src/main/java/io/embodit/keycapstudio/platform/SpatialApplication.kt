package io.embodit.keycapstudio.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.keycapstudio.KsApp
import io.embodit.keycapstudio.input.BoardInputLog
import io.embodit.keycapstudio.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        BoardInputLog.attach(this)
        KsApp.init(this)
        launch(::mainApp)
    }
}
