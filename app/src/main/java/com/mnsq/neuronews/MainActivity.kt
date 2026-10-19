package com.mnsq.neuronews

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mnsq.neuronews.ui.theme.NeuralNewsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NeuralNewsTheme {
                val vm: MainViewModel = viewModel()
                NewsScreen(vm)
            }
        }
    }
}
