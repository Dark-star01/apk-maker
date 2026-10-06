package __APP_ID__

import android.os.Bundle
import __APP_ID__.bridge.MvmBridgePlugin
import com.getcapacitor.BridgeActivity

// Intentionally tiny: it only registers plugins. Engines live in their own packages.
class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Local (in-app) plugins must be registered before super.onCreate.
        registerPlugin(MvmBridgePlugin::class.java)
        super.onCreate(savedInstanceState)
    }
}
