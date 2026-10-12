package com.nevoit.cirro

import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.nevoit.cirro.data.SongLikeRepository
import com.nevoit.material.core.component.Surface
import com.nevoit.material.navigation.shape.DeviceCornerState
import com.nevoit.material.navigation.shape.LocalDeviceCorners
import com.nevoit.material.theme.MaterialTheme
import org.koin.android.ext.android.get

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        get<SongLikeRepository>().onForeground()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val corners = DeviceCornerState()

        setContent {
            CompositionLocalProvider(LocalDeviceCorners provides corners) {
                MaterialTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Main()
                    }
                }
            }
        }

        val content = findViewById<View>(android.R.id.content)

        content.setOnApplyWindowInsetsListener { view, insets ->
            corners.update(view.rootWindowInsets ?: insets)
            view.onApplyWindowInsets(insets)
        }

        content.rootWindowInsets?.let(corners::update)
        content.requestApplyInsets()
    }
}
