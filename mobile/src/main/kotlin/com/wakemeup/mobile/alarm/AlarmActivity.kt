package com.wakemeup.mobile.alarm

import android.content.Intent
import android.os.*
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wakemeup.core.*
import com.wakemeup.mobile.WakeMeUpApp
import com.wakemeup.mobile.ui.WakeTheme
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val app = application as WakeMeUpApp
        setContent { WakeTheme {
            val entity by app.database.sessions().observeLatest().collectAsStateWithLifecycle(initialValue = null)
            val session = entity?.session
            LaunchedEffect(session?.status) { if (session != null && session.status != SessionStatus.RINGING) finish() }
            Column(Modifier.fillMaxSize().background(Color(0xFF101625)).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("WAKEMEUP", color = Color(0xFFB2AEF7), letterSpacing = 4.sp)
                Spacer(Modifier.height(36.dp)); Text("좋은 아침이에요", style = MaterialTheme.typography.headlineLarge)
                Text(java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")), fontSize = 76.sp, color = Color(0xFFFFD49E))
                Text(if (session?.firedKind == AlarmKind.BACKUP) "예비 알람이 울리고 있어요" else "입면 후 ${session?.targetMinutes?.div(60) ?: 0}시간 ${session?.targetMinutes?.rem(60) ?: 0}분이 지났어요")
                session?.onsetAt?.let { Text("추정 입면 ${it.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))}") }
                Spacer(Modifier.height(48.dp))
                Button(onClick = {
                    startService(Intent(this@AlarmActivity, AlarmPlaybackService::class.java).setAction(AlarmPlaybackService.DISMISS).putExtra("sessionId", session?.sessionId))
                    finish()
                }, modifier = Modifier.fillMaxWidth().height(64.dp)) { Text("알람 해제", fontSize = 20.sp) }
            }
        } }
    }
}
