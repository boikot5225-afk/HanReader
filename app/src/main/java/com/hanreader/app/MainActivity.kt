package com.hanreader.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        IncomingBooks.take(intent)
    }

    /** In page mode, volume down/up turn the page forward/back instead of changing the volume. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val code = event.keyCode
        if (ReaderKeys.active && (code == android.view.KeyEvent.KEYCODE_VOLUME_DOWN || code == android.view.KeyEvent.KEYCODE_VOLUME_UP)) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                ReaderKeys.turns.tryEmit(if (code == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) 1 else -1)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AppearanceStore.load(this)
        IncomingBooks.take(intent)
        // The language to read (Chinese unless switched in the library), then its dictionary in the
        // background right away, so it is ready by the first tap.
        com.hanreader.app.lang.Languages.init(this)
        com.hanreader.app.lang.LanguageEngines.warmUp(this)
        setContent { LexiconTheme { LexiconScreen() } }
    }
}
