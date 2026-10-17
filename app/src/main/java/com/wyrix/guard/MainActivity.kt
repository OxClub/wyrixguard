package com.wyrix.guard

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.wyrix.guard.ui.WyrixRoot
import com.wyrix.guard.ui.theme.WyrixTheme
import dagger.hilt.android.AndroidEntryPoint

/** FragmentActivity so BiometricPrompt can be used to confirm sensitive on-device actions. */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { WyrixTheme { WyrixRoot() } }
    }
}
