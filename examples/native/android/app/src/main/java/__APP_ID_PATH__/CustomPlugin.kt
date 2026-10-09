package __APP_ID__

import android.widget.Toast
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

@CapacitorPlugin(name = "CustomPlugin")
class CustomPlugin : Plugin() {

    // من JS:  await Capacitor.Plugins.CustomPlugin.toast({ message: "أهلاً" })
    @PluginMethod
    fun toast(call: PluginCall) {
        val msg = call.getString("message") ?: "👋"
        activity.runOnUiThread {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
        call.resolve(JSObject().put("ok", true))
    }
}
