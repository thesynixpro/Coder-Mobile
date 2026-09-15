package com.aprax.coderm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import com.aprax.coderm.ui.screens.MainViewModel
import com.aprax.coderm.ui.screens.RootScreen
import com.aprax.coderm.ui.theme.CoderMobileTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CoderMobileTheme(vm.theme.collectAsState().value) {
                RootScreen(vm, this)
            }
        }
    }
}
