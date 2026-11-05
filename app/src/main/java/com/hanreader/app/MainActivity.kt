package com.hanreader.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Load the Chinese dictionary in the background right away, so it is ready by the first tap.
        com.hanreader.app.reader.ZhEngine.warmUp(this)
        setContent { LexiconTheme { LexiconScreen() } }
    }
}
