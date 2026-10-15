package com.assassins377.remoteplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.assassins377.remoteplayer.nav.AppNavGraph
import com.assassins377.remoteplayer.ui.theme.RemotePlayerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RemotePlayerTheme { AppNavGraph() } }
    }
}
