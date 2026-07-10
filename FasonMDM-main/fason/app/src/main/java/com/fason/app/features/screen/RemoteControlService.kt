package com.fason.app.features.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class RemoteControlService : AccessibilityService() {

    companion object {
        var instance: RemoteControlService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("RemoteControlService", "Service connected")
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Log.d("RemoteControlService", "Service unbound")
        instance = null
        return super.onUnbind(intent)
    }

    fun performTap(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 100))
        dispatchGesture(gestureBuilder.build(), null, null)
        Log.d("RemoteControlService", "Performed tap at $x, $y")
    }

    fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long) {
        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
        dispatchGesture(gestureBuilder.build(), null, null)
        Log.d("RemoteControlService", "Performed swipe from $startX, $startY to $endX, $endY")
    }

    fun performKey(keyCode: String) {
        val action = when (keyCode.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            else -> return
        }
        performGlobalAction(action)
    }

    fun performText(text: String) {
        if (text.isEmpty()) return
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        focused.recycle()
    }
}
