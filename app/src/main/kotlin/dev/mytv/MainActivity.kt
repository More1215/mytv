package dev.mytv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import dev.mytv.remote.RemoteControl
import dev.mytv.ui.navigation.MyTvNavHost
import dev.mytv.ui.support.RemoteBanner
import dev.mytv.ui.theme.MyTvTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var remote: RemoteControl
    @Inject lateinit var updates: dev.mytv.update.UpdateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Tests (debug uniquement) : adb shell am start ... --ez mytv.remote true démarre l'aide.
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("mytv.remote", false) == true) remote.start()
        setContent {
            MyTvTheme {
                Box(Modifier.fillMaxSize()) {
                    MyTvNavHost(remote = remote)
                    RemoteBanner(remote, Modifier.align(Alignment.TopCenter))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        remote.attach(this)
        updates.onAppResumed(this) // retour du réglage « applis inconnues » : l'installation reprend seule
    }

    override fun onPause() {
        remote.detach(this)
        super.onPause()
    }

    override fun onStop() {
        // MyTV quitté (touche Accueil, autre appli) : l'aide à distance s'arrête.
        if (!isChangingConfigurations) remote.stop()
        super.onStop()
    }
}
