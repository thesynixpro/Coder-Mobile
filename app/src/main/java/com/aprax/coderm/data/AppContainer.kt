package com.aprax.coderm.data

import android.content.Context

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val projectStore = ProjectStore(appContext)
    val secretStore = SecretStore(appContext)
    val projects = ProjectRepository(appContext)
    val ai = AiRepository(secretStore)
    val providers = AiProviderStore(appContext, secretStore)
}
