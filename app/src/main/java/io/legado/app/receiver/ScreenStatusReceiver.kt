package io.legado.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import io.legado.app.constant.EventBus
import io.legado.app.utils.postEvent

/**
 * 屏幕状态监听
 */
class ScreenStatusReceiver : BroadcastReceiver() {

    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_ON)
        addAction(Intent.ACTION_SCREEN_OFF)
        addAction(Intent.ACTION_USER_PRESENT)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> {
                postEvent(EventBus.SCREEN_ON, "")
            }
            Intent.ACTION_SCREEN_OFF -> {
                postEvent(EventBus.SCREEN_OFF, "")
            }
            Intent.ACTION_USER_PRESENT -> {
                postEvent(EventBus.USER_PRESENT, "")
            }
        }
    }
}