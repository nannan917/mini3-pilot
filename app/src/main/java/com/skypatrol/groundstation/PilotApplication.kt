package com.skypatrol.groundstation

import android.app.Application
import android.content.Context
import android.util.Log

/** No DJI callback, manager or listener types may appear in this bootstrap class. */
class PilotApplication : Application() {
    var bootstrapError: String? = null
        private set
    private var session: PilotSession? = null

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        try {
            com.cySdkyc.clx.Helper.install(this)
            Log.i("Mini3Bootstrap", "DJI protected library loaded")
        } catch (error: Throwable) {
            bootstrapError = "DJI 运行库加载失败：${error.javaClass.simpleName} ${error.message}"
            Log.e("Mini3Bootstrap", "Helper.install failed", error)
        }
    }

    @Synchronized fun session(): PilotSession? {
        if (bootstrapError != null) return null
        session?.let { return it }
        return try {
            // Resolve SDK-bearing classes only after Helper.install has returned.
            (Class.forName("com.skypatrol.groundstation.DjiMini3Session")
                .getDeclaredConstructor().newInstance() as PilotSession).also { session = it }
        } catch (error: Throwable) {
            bootstrapError = "DJI 模块加载失败：${error.cause?.message ?: error.message}"
            Log.e("Mini3Bootstrap", "Session load failed", error)
            null
        }
    }
}
