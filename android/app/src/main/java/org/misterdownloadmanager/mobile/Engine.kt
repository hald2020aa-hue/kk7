package org.misterdownloadmanager.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal object Engine {
    @Synchronized fun initialize(context: Context) {
        val directory = File(context.filesDir, "mdm")
        checkResult(NativeBridge.initialize(context.applicationContext, directory.absolutePath))
    }
    @Synchronized fun call(op: String, vararg args: Pair<String, Any?>): Any? {
        val request = JSONObject().put("op", op)
        args.forEach { (key, value) -> request.put(key, value ?: JSONObject.NULL) }
        return checkResult(NativeBridge.command(request.toString()))
    }
    private fun checkResult(raw: String): Any? {
        val json = JSONObject(raw)
        check(json.optBoolean("ok")) { json.optString("error", "Engine error") }
        return json.opt("value")
    }
    fun list(): List<Job> {
        val rows = call("list") as JSONArray
        return List(rows.length()) { Job(rows.getJSONObject(it)) }
    }
}
internal data class Job(val data: JSONObject) {
    val id get() = data.getLong("id")
    val name get() = data.getString("name")
    val status get() = data.getString("status")
    val downloaded get() = data.optLong("downloaded")
    val total get() = data.optLong("total")
    val speed get() = data.optLong("speed")
    val connections get() = data.optInt("active_connections")
    val file get() = data.optString("file").takeIf { !data.isNull("file") }
    val error get() = data.optString("error").takeIf { !data.isNull("error") }
    val running get() = status == "running" || status == "stopping"
}
