package com.wakemeup.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import androidx.activity.*
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.wakemeup.mobile.alarm.*
import com.wakemeup.mobile.sync.RecoveryWorker
import com.wakemeup.mobile.ui.*
import kotlinx.coroutines.*
import java.io.File
import java.time.Instant

class MainActivity : ComponentActivity() {
    private val model: HomeViewModel by viewModels()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { model.refresh() }
    private val settingsPage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { model.refresh() }
    private var askedNotifications = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        askedNotifications = savedInstanceState?.getBoolean("askedNotifications") ?: false
        RecoveryWorker.enqueue(this)
        setContent { WakeTheme { WakeApp(model,
            onExact = { if (Build.VERSION.SDK_INT >= 31) settingsPage.launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) },
            onNotifications = { requestNotifications() },
            onFullScreen = { if (Build.VERSION.SDK_INT >= 34) settingsPage.launch(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))) },
            onAlarm = { startActivity(Intent(this, AlarmActivity::class.java)) }, onExport = { exportLogs() },
        ) } }
    }
    override fun onResume() { super.onResume(); model.refresh() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("askedNotifications", askedNotifications); super.onSaveInstanceState(outState) }
    private fun requestNotifications() {
        val missingRuntime = Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (missingRuntime && (!askedNotifications || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
            askedNotifications = true
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            val channel = getSystemService(android.app.NotificationManager::class.java).getNotificationChannel("wake_alarm")
            val intent = if (channel?.importance == android.app.NotificationManager.IMPORTANCE_NONE) {
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName).putExtra(Settings.EXTRA_CHANNEL_ID, "wake_alarm")
            } else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            settingsPage.launch(intent)
        }
    }
    private fun exportLogs() { lifecycleScope.launch {
        val file = withContext(Dispatchers.IO) {
            val entries = (application as WakeMeUpApp).database.sessions().allLogs()
            val directory = File(cacheDir, "reports").apply { mkdirs() }
            File(directory, "wakemeup-log.csv").apply {
                fun quote(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
                writeText("sessionId,atUTC,kind,detail\n" + entries.joinToString("\n") { listOf(it.sessionId ?: "", Instant.ofEpochMilli(it.at).toString(), it.kind, it.detail).joinToString(",", transform = ::quote) })
            }
        }
        val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "진단 기록 내보내기"))
    } }
}
