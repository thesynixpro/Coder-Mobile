package com.aprax.coderm

import android.app.Application
import android.content.Context
import android.system.Os

class CoderMobileApplication : Application() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        try {
            // Silence Mesa driver logs in virtualized/containerized emulator environments
            // where Direct Rendering Manager (DRM) rendernodes (/dev/dri) do not exist.
            Os.setenv("MESA_LOG_FILE", "/dev/null", true)
            Os.setenv("MESA_LOG_LEVEL", "none", true)
            Os.setenv("MESA_DEBUG", "silent", true)
            Os.setenv("LIBGL_ALWAYS_SOFTWARE", "1", true)
            Os.setenv("LIBGL_DRI3_DISABLE", "1", true)
        } catch (_: Throwable) {
            // Fallback gracefully if Os.setenv is restricted
        }
    }
}
