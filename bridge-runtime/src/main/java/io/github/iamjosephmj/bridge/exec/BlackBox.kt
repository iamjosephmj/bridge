package io.github.iamjosephmj.bridge.exec

import android.app.ActivityManager
import android.content.Context
import android.os.Build

interface BlackBox {
    fun stamp(workId: String, step: String, attempt: Int)
    fun clear()
}

class SystemBlackBox(context: Context) : BlackBox {
    private val am = context.getSystemService(ActivityManager::class.java)
    override fun stamp(workId: String, step: String, attempt: Int) {
        if (Build.VERSION.SDK_INT >= 30) {
            am.setProcessStateSummary("$workId|$step|$attempt".toByteArray(Charsets.UTF_8))
        }
    }
    override fun clear() {
        if (Build.VERSION.SDK_INT >= 30) am.setProcessStateSummary(null)
    }
}

class FakeBlackBox : BlackBox {
    var last: String? = null
    override fun stamp(workId: String, step: String, attempt: Int) {
        last = "$workId|$step|$attempt"
    }
    override fun clear() { last = null }
}
