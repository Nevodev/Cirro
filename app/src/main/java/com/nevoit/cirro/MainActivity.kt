package com.nevoit.cirro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.nevoit.cirro.data.SongLikeRepository
import com.nevoit.material.core.component.Surface
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
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Main()
                }
            }
        }
    }
}
