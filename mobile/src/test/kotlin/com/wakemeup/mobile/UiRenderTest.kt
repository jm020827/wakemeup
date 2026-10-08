package com.wakemeup.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.wakemeup.core.*
import com.wakemeup.mobile.data.*
import com.wakemeup.mobile.ui.*
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowChoreographer

class UiPreviewActivity : ComponentActivity() {
    companion object { var page = "waiting" }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = ViewModelProvider(this)[HomeViewModel::class.java]
        val start = Instant.parse("2025-01-14T15:10:00Z")
        val onset = start.plusSeconds(900)
        val session = SleepSession("preview", start, 450, start.plusSeconds(9 * 3600), false, "watch",
            onsetAt = if (page == "waiting") null else onset,
            receivedAt = if (page == "waiting") null else onset.plusSeconds(1201),
            alarmAt = if (page == "waiting") null else onset.plusSeconds(450 * 60),
            status = if (page == "waiting") SessionStatus.MONITORING else SessionStatus.SCHEDULED,
            watchMonitoring = true, targetScheduled = page != "waiting", backupScheduled = page == "waiting")
        val displayed = if (page == "flow") session.copy(status = SessionStatus.COMPLETED, firedKind = AlarmKind.TARGET, firedAt = session.alarmAt, targetScheduled = false) else session
        val state = HomeState(watch = WatchStatus(nodeId = "watch", name = "Galaxy Watch", supported = true, permission = true),
            session = if (page == "ready") null else displayed, history = if (page == "ready") emptyList() else listOf(displayed),
            device = DeviceState(connectedNodes = setOf("watch"), exact = true, notifications = true, fullScreen = true, permissionsChecked = true))
        setContent { WakeTheme {
            WakeScreen(if (page == "setup") HomeState(device = DeviceState(permissionsChecked = true)) else state,
                model, {}, {}, {}, {}, {}, initialTab = if (page == "flow") 1 else 0)
        } }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi", application = WakeMeUpApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class UiRenderTest {
    @Test fun mainStatesOnboardingAndSleepClockRenderWithAndroidGraphics() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16))
        for (page in listOf("ready", "waiting", "scheduled", "flow", "setup")) {
            UiPreviewActivity.page = page
            val controller = Robolectric.buildActivity(UiPreviewActivity::class.java).setup().visible()
            try {
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
                val view = controller.get().window.decorView
                view.measure(View.MeasureSpec.makeMeasureSpec(1179, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2556, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 1179, 2556)
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
                val bitmap = Bitmap.createBitmap(1179, 2556, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                val colors = mutableSetOf<Int>()
                for (y in 100 until 2450 step 13) for (x in 30 until 1150 step 11) colors += bitmap.getPixel(x, y)
                assertTrue("$page must render real content", colors.size > 20)
                val directory = File("build/reports/ui").apply { mkdirs() }
                File(directory, "$page.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            } finally { controller.pause().stop().destroy() }
        }
    }
}
