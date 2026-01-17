package com.nostrtv.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import com.nostrtv.android.navigation.NostrTVNavHost
import com.nostrtv.android.ui.theme.NostrTVTheme

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NostrTVTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    NostrTVNavHost()
                }
            }
        }
    }
}
