package __APP_ID__

import android.os.Bundle
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // تسجيل بلاجن محلي (داخل التطبيق) — لازم قبل super.onCreate
        registerPlugin(CustomPlugin::class.java)
        super.onCreate(savedInstanceState)
    }
}
