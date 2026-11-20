package com.agendek.bramav2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.agendek.bramav2.ui.BramaNav
import com.agendek.bramav2.ui.GateViewModel
import com.agendek.bramav2.ui.theme.BramaV2Theme

/** Jedyna aktywność; podpięcie UI telefonu do domeny śledzi proces (`BramaApp`), nie aktywność. */
class MainActivity : ComponentActivity() {

    private val vm: GateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BramaV2Theme { BramaNav(vm, this) } }
    }
}
