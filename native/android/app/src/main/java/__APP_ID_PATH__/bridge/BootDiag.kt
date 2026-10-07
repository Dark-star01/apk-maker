package __APP_ID__.bridge

import com.getcapacitor.Bridge
import com.getcapacitor.PluginHandle

/**
 * Records what really happened while Capacitor built its bridge. Capacitor's own registerPlugin()
 * swallows failures (it only logs them), so without this a broken plugin is invisible.
 * Read from JS via the tiny MvmDiag plugin.
 */
object BootDiag {
    @Volatile var text: String = "MainActivity.kt did not run (the default MainActivity is in use)"

    fun collect(bridge: Bridge?) {
        val sb = StringBuilder("MainActivity.kt: ran")
        if (bridge == null) {
            sb.append("\nBridge: null after onCreate")
            text = sb.toString()
            return
        }
        for (id in listOf("MvmDiag", "MvmBridge")) {
            sb.append("\n").append(id).append(": ").append(if (bridge.getPlugin(id) != null) "registered" else "NOT registered")
        }
        if (bridge.getPlugin("MvmBridge") == null) probe(bridge, sb)
        text = sb.toString()
    }

    // Repeats the registration steps one by one to expose the exception Capacitor hides.
    private fun probe(bridge: Bridge, sb: StringBuilder) {
        val cls = MvmBridgePlugin::class.java
        try {
            val a = cls.getAnnotation(com.getcapacitor.annotation.CapacitorPlugin::class.java)
            sb.append("\nannotation: ").append(if (a == null) "MISSING" else a.name)
            cls.getDeclaredConstructor().newInstance()
            sb.append("\nnewInstance: ok")
        } catch (t: Throwable) {
            sb.append("\nnewInstance FAILED: ").append(describe(t))
            return
        }
        try {
            PluginHandle(bridge, cls)
            sb.append("\nPluginHandle: ok (registration step failed elsewhere)")
        } catch (t: Throwable) {
            sb.append("\nPluginHandle FAILED: ").append(describe(t))
        }
    }

    private fun describe(t: Throwable): String {
        var c: Throwable = t
        while (c.cause != null && c.cause !== c) c = c.cause!!
        return c.javaClass.simpleName + ": " + (c.message ?: "")
    }
}
