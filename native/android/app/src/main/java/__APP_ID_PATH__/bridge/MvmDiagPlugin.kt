package __APP_ID__.bridge

import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/** Deliberately trivial (no activity callbacks, no state): if this registers but MvmBridge does not, the fault is inside MvmBridgePlugin. */
@CapacitorPlugin(name = "MvmDiag")
class MvmDiagPlugin : Plugin() {
    @PluginMethod
    fun report(call: PluginCall) {
        val ret = JSObject()
        ret.put("text", BootDiag.text)
        call.resolve(ret)
    }
}
