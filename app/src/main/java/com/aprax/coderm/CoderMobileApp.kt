package com.aprax.coderm

import android.app.Application
import com.aprax.coderm.data.AppContainer

class CoderMobileApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
