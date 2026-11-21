package music.ai.recommend

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import music.ai.recommend.platform.ContextProvider

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextProvider.context = applicationContext
        setContent {
            App()
        }
    }
}
