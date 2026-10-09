package __APP_ID__

import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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
        fitSystemBars()
    }

    /**
     * Android 15 (targetSdk 35) draws the app edge-to-edge, so the WebView would extend under the status and navigation
     * bars (the bottom tabs ended up behind the nav bar). The insets are applied ONCE, here, as margins of the WebView:
     * the page then lives entirely between the bars, and env(safe-area-inset-*) is 0 inside it (the CSS does not apply
     * them a second time). The bars stay visible, gesture and 3-button navigation both report their real size, and the
     * area behind them is painted with the app's dark colour.
     */
    private fun fitSystemBars() {
        val dark = Color.parseColor("#080810")
        val window = window
        window.decorView.setBackgroundColor(dark)
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        ctl.isAppearanceLightStatusBars = false     // light icons on the dark UI
        ctl.isAppearanceLightNavigationBars = false
        val wv = bridge.webView
        ViewCompat.setOnApplyWindowInsetsListener(wv) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val lp = v.layoutParams as? ViewGroup.MarginLayoutParams
            if (lp != null && (lp.leftMargin != bars.left || lp.topMargin != bars.top || lp.rightMargin != bars.right || lp.bottomMargin != bars.bottom)) {
                lp.setMargins(bars.left, bars.top, bars.right, bars.bottom)
                v.layoutParams = lp
            }
            WindowInsetsCompat.CONSUMED // children (the page) get no insets: no double application
        }
        ViewCompat.requestApplyInsets(wv)
    }
}
