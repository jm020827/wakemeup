package com.wakemeup.wear

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RegistrationWorker.enqueue(this)
        setContent {
            val state by (application as WatchApp).store.state.collectAsStateWithLifecycle(initialValue = WatchState())
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { RegistrationWorker.enqueue(this) }
            MaterialTheme(colors = Colors(primary = Color(0xFFC0B8FF), background = Color(0xFF101625), surface = Color(0xFF20283A))) {
                Scaffold(timeText = { TimeText() }) {
                    ScalingLazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        item { Text("wakemeup", color = MaterialTheme.colors.primary, style = MaterialTheme.typography.title2) }
                        item { Text(if (state.monitoring) "수면 감시 중" else "수면 감시 대기", style = MaterialTheme.typography.title3) }
                        item { Text(if (state.supported) "수면 상태 지원 확인됨" else "수면 상태 미지원 / 확인 중", textAlign = TextAlign.Center) }
                        if (state.active) item { Text("목표 ${state.targetMinutes / 60}시간 ${state.targetMinutes % 60}분\n${if (state.validation) "수신 검증 모드" else "자동 알람 모드"}", textAlign = TextAlign.Center) }
                        if (state.onsetAt > 0) item { Text("추정 입면\n${Instant.ofEpochMilli(state.onsetAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))}", textAlign = TextAlign.Center, color = Color(0xFFFFD49E)) }
                        if (state.error.isNotBlank()) item { Text(state.error, textAlign = TextAlign.Center, style = MaterialTheme.typography.caption1) }
                        if (!state.permission) item { Button(onClick = { permission.launch(Manifest.permission.ACTIVITY_RECOGNITION) }) { Text("권한 허용", style = MaterialTheme.typography.caption1) } }
                        item { Chip(onClick = { RegistrationWorker.enqueue(this@MainActivity) }, label = { Text("지원 확인 / 재등록") }, modifier = Modifier.fillMaxWidth()) }
                        item { Text("알람은 휴대폰에서 울립니다.", textAlign = TextAlign.Center, style = MaterialTheme.typography.caption2) }
                    }
                }
            }
        }
    }
}
