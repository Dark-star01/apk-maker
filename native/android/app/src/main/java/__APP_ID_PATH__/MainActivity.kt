package __APP_ID__

import android.os.Bundle
import __APP_ID__.bridge.BootDiag
import __APP_ID__.bridge.MvmBridgePlugin
import __APP_ID__.bridge.MvmDiagPlugin
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Capacitor builds the WebView (and its JS plugin export) inside super.onCreate,
        // so custom plugins must be registered before it.
        registerPlugin(MvmDiagPlugin::class.java)
        registerPlugin(MvmBridgePlugin::class.java)
        super.onCreate(savedInstanceState)
        BootDiag.collect(this.bridge)
    }
}
