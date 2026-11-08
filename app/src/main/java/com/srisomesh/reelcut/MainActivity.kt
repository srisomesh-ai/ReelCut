package com.srisomesh.reelcut

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.media3.common.util.UnstableApi
import com.srisomesh.reelcut.ui.App
import com.srisomesh.reelcut.ui.ReelCutTheme

@UnstableApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // "open_project" opens a saved timeline project directly (used by the emulator test)
        val startProject = intent?.getStringExtra("open_project")
        setContent { ReelCutTheme { App(startProject = startProject) } }
    }
}
