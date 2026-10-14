package com.modhub.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.modhub.app.ui.navigation.ModHubNavGraph
import com.modhub.app.ui.theme.ModHubTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Latest `nxm://` link received (launch or onNewIntent). Consumed by the nav graph. */
    private val pendingNxmLink = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on a fresh start: after a configuration change the activity is recreated with
        // the same launch intent, and the link must not start a second download.
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            ModHubTheme(dynamicColor = true) {
                ModHubNavGraph(
                    pendingNxmLink = pendingNxmLink.value,
                    onNxmLinkConsumed = { pendingNxmLink.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (intent.action == Intent.ACTION_VIEW && data.scheme == "nxm") pendingNxmLink.value = data
    }
}
