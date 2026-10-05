package com.nevoit.cirro.login

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.nevoit.material.core.component.Surface
import com.nevoit.material.theme.MaterialTheme

class LoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    QrLoginScreen(
                        onClose = { finish() },
                        onLoginConfirmed = {
                            setResult(Activity.RESULT_OK)
                            finish()
                        },
                    )
                }
            }
        }
    }
}
