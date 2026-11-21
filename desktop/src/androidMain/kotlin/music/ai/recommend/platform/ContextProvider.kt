package music.ai.recommend.platform

import android.annotation.SuppressLint
import android.content.Context

@SuppressLint("StaticFieldLeak")
object ContextProvider {
    var context: Context? = null
}
