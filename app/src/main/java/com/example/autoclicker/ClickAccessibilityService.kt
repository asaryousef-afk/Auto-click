package com.example.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * خدمة الإتاحة هي الوحيدة القادرة فعليًا على "الضغط" نيابة عن المستخدم
 * على أندرويد الحديث (باستخدام dispatchGesture). البار العائم بيبعتلها إحداثيات
 * الضغط بس، وهي اللي بتنفذ.
 */
class ClickAccessibilityService : AccessibilityService() {

    companion object {
        // مرجع بسيط عشان أي مكان في التطبيق يقدر يوصل للخدمة النشطة
        var instance: ClickAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // مش محتاجين نتفاعل مع الأحداث، الخدمة هنا بس عشان صلاحية الضغط
    }

    override fun onInterrupt() {}

    /** ينفذ ضغطة واحدة عند الإحداثيات المحددة */
    fun performClick(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }
}
