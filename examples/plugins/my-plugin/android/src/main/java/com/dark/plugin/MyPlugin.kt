package com.dark.plugin

import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

@CapacitorPlugin(name = "MyPlugin")
class MyPlugin : Plugin() {

    // من JS:  await Capacitor.Plugins.MyPlugin.echo({ value: "مرحبا" })
    @PluginMethod
    fun echo(call: PluginCall) {
        val value = call.getString("value") ?: ""
        val ret = JSObject()
        ret.put("value", value)
        call.resolve(ret)
    }
}
