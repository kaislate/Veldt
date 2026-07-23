package com.kaislate.veldtplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.kaislate.veldtplayer.ui.dev.DevPlayerScreen
import com.kaislate.veldtplayer.ui.theme.VeldtTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VeldtTheme { Surface(Modifier.fillMaxSize()) { DevPlayerScreen() } } }
    }
}
