package com.example.nichirin

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.nichirin.dsp.MicSpectrumEngine
import com.example.nichirin.dsp.Spectrum12Processor
import com.example.nichirin.dsp.FileSpectrumEngine
import com.example.nichirin.ui.theme.NichirinTheme
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

// 你工程里如果已有这些，删除本文件的重复定义即可


// FFE0/FFE1/FFE2（16-bit）对应 128-bit 基准



private enum class TxSource { SAW_400HZ, MIC_400HZ, FILE_400HZ }
private enum class TxUiMode { STOP, SAW, MIC, FILE }

private data class AudioParams(
    val sampleRate: Int = 48000,
    val blockSize: Int = 512,
    val floorDb: Float = 20f,
)

class MainActivity : ComponentActivity() {

    private val uiHandler = Handler(Looper.getMainLooper())

    // ---- BLE system ----
    private lateinit var btManager: BluetoothManager
    private var btAdapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null

    private var scanning = false
    private var scanCallback: ScanCallback? = null

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    // ---- TX 400HZ ----
    private val txHandler = Handler(Looper.getMainLooper())
    private var txRunning = false
    private var txSource: TxSource = TxSource.SAW_400HZ
    private var testPhase = 0
    private val txPeriodMs = 1000.0 / 400.0
    private var txNextTimeMs = 0.0

    // ---- MIC spectrum ----
    private var micEngine: MicSpectrumEngine? = null
    private val latestBandsRef = AtomicReference(IntArray(12))
    private var fileEngine: FileSpectrumEngine? = null

    // ---- Compose states (activity scope) ----
    private val screenState = mutableStateOf(Screen.SCAN)
    private val statusState = mutableStateOf("未扫描")
    private val scanList = mutableStateListOf<BleDeviceItem>()

    private val selectedDevice = mutableStateOf<BleDeviceItem?>(null)
    private val connState = mutableStateOf("未连接")
    private val servicesState = mutableStateOf("服务未发现")

    private val txState = mutableStateOf("TX: STOP")
    private val micState = mutableStateOf("MIC: STOP")
    private val fileState = mutableStateOf("FILE: STOP")
    private val txUiModeState = mutableStateOf(TxUiMode.STOP)

    private val audioParamsState = mutableStateOf(AudioParams())
    private val debugModeState = mutableStateOf(false)
    private val fileUriState = mutableStateOf<Uri?>(null)
    private val fileNameState = mutableStateOf("未选择文件")
    private val fileProgressState = mutableStateOf(0f)
    private val fileProgressTextState = mutableStateOf("00:00 / 00:00")
    private val fileDurationMsState = mutableStateOf(0)
    private val fileSeekingState = mutableStateOf(false)
    private val fileFloorDbState = mutableStateOf(10f)
    private val fileRangeDbState = mutableStateOf(90f)

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        val scanOk = res[Manifest.permission.BLUETOOTH_SCAN] == true
        val connOk = res[Manifest.permission.BLUETOOTH_CONNECT] == true
        val micOk = res[Manifest.permission.RECORD_AUDIO] == true
        uiHandler.post {
            statusState.value = "权限：SCAN=$scanOk CONNECT=$connOk MIC=$micOk"
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            fileUriState.value = uri
            fileNameState.value = getDisplayName(uri) ?: "已选择文件"
            fileState.value = "FILE: READY"
            fileProgressState.value = 0f
            fileProgressTextState.value = "00:00 / 00:00"
            fileDurationMsState.value = 0
        }
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        registerDynamicShortcuts()

        btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        btAdapter = btManager.adapter
        scanner = btAdapter?.bluetoothLeScanner

        setContent {
            NichirinTheme {
                var filterFfe0 by remember { mutableStateOf(true) }
                var requestedOnce by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    if (!requestedOnce) {
                        val req = mutableListOf<String>()
                        req += Manifest.permission.BLUETOOTH_SCAN
                        req += Manifest.permission.BLUETOOTH_CONNECT
                        req += Manifest.permission.RECORD_AUDIO
                        permLauncher.launch(req.toTypedArray())
                    }
                }

                val screen by screenState
                val status by statusState
                val debugMode by debugModeState

                Box(modifier = Modifier.fillMaxSize()) {
                    Image(
                        painter = painterResource(id = R.drawable.background),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.2f))
                    )
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = Color.Transparent
                    ) { inner ->
                        when (screen) {
                            Screen.SCAN -> ScanScreen(
                                innerPadding = inner,
                                status = status,
                                list = scanList,
                                filterFfe0 = filterFfe0,
                                debugMode = debugMode,
                                onDebugModeChange = { debugModeState.value = it },
                                onFilterChange = { },
                                onStartScan = { startScan(filterFfe0) },
                                onStopScan = { stopScan() },
                                onClickItem = { item ->
                                    stopScan()
                                    connectTo(item)
                                }
                            )

                            Screen.DETAIL -> DetailScreen(
                                innerPadding = inner,
                                dev = selectedDevice.value,
                                conn = connState.value,
                                svc = servicesState.value,
                                txSt = txState.value,
                                micSt = micState.value,
                                fileSt = fileState.value,
                                uiMode = txUiModeState.value,
                                audioParams = audioParamsState.value,
                                debugMode = debugMode,
                                fileName = fileNameState.value,
                                fileProgress = fileProgressState.value,
                                fileProgressText = fileProgressTextState.value,
                                fileDurationMs = fileDurationMsState.value,
                                fileSeeking = fileSeekingState.value,
                                onDebugModeChange = { debugModeState.value = it },
                                onAudioParamsChange = { audioParamsState.value = it },
                                onFileSeekStart = { fileSeekingState.value = true },
                                onFileSeek = { progress ->
                                    fileProgressState.value = progress.coerceIn(0f, 1f)
                                    val dur = fileDurationMsState.value
                                    val pos = (dur * fileProgressState.value).toInt()
                                    fileProgressTextState.value = "${fmtMmSs(pos)} / ${fmtMmSs(dur)}"
                                },
                                onFileSeekEnd = { progress ->
                                    fileSeekingState.value = false
                                    val durMs = fileDurationMsState.value
                                    val targetMs = (durMs * progress.coerceIn(0f, 1f)).toLong()
                                    fileEngine?.seekTo(targetMs * 1000L)
                                },

                                onBack = {
                                    stopAllTx()
                                    disconnect()
                                    screenState.value = Screen.SCAN
                                },
                                onReconnect = { reconnect() },
                                onToggleSaw = {
                                    if (txUiModeState.value == TxUiMode.SAW) {
                                        stopAllTx()
                                    } else {
                                        stopAllTx()
                                        startTx400HZ(TxSource.SAW_400HZ)
                                        txUiModeState.value = TxUiMode.SAW
                                    }
                                },
                                onToggleMic = {
                                    if (txUiModeState.value == TxUiMode.MIC) {
                                        stopAllTx()
                                    } else {
                                        // 切换到 MIC：先确保文件引擎彻底停止，避免资源占用
                                        stopTx400HZ()
                                        stopFileSpectrum()
                                        stopMicSpectrum()
                                        startMicSpectrum()          // 会读取 audioParamsState
                                        startTx400HZ(TxSource.MIC_400HZ)
                                        txUiModeState.value = TxUiMode.MIC
                                    }
                                },
                                onToggleFile = {
                                    if (txUiModeState.value == TxUiMode.FILE) {
                                        stopAllTx()
                                    } else {
                                        // 切换到 FILE：先确保麦克风停止，再启动文件引擎
                                        stopTx400HZ()
                                        stopMicSpectrum()
                                        stopFileSpectrum()
                                        startFileSpectrum()
                                        startTx400HZ(TxSource.FILE_400HZ)
                                        txUiModeState.value = TxUiMode.FILE
                                    }
                                },
                                onPickFile = { filePickerLauncher.launch(arrayOf("audio/*", "video/*")) },
                                onDisconnect = { disconnect() }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun registerDynamicShortcuts() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        val mgr = getSystemService(ShortcutManager::class.java) ?: return

        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
        }

        val s1 = ShortcutInfo.Builder(this, "watching_1")
            .setShortLabel(getString(R.string.shortcut_watching))
            .setLongLabel(getString(R.string.shortcut_watching))
            .setIcon(Icon.createWithResource(this, R.drawable.shortcut_1))
            .setIntent(intent)
            .build()

        val s2 = ShortcutInfo.Builder(this, "watching_2")
            .setShortLabel(getString(R.string.shortcut_watching))
            .setLongLabel(getString(R.string.shortcut_watching))
            .setIcon(Icon.createWithResource(this, R.drawable.shortcut_2))
            .setIntent(intent)
            .build()

        val s3 = ShortcutInfo.Builder(this, "watching_3")
            .setShortLabel(getString(R.string.shortcut_watching))
            .setLongLabel(getString(R.string.shortcut_watching))
            .setIcon(Icon.createWithResource(this, R.drawable.shortcut_3))
            .setIntent(intent)
            .build()

        mgr.dynamicShortcuts = listOf(s1, s2, s3)
    }

    // ---------------- UI composable ----------------

    @Composable
    private fun ScanScreen(
        innerPadding: PaddingValues,
        status: String,
        list: List<BleDeviceItem>,
        filterFfe0: Boolean,
        debugMode: Boolean,
        onDebugModeChange: (Boolean) -> Unit,
        onFilterChange: (Boolean) -> Unit,
        onStartScan: () -> Unit,
        onStopScan: () -> Unit,
        onClickItem: (BleDeviceItem) -> Unit
    ) {
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("小日轮", style = MaterialTheme.typography.titleLarge)
                    Text("选择小日轮并连接", style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("进阶设置")
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = debugMode, onCheckedChange = onDebugModeChange)
                }
            }

            ElevatedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = onStartScan,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("开始扫描") }
                        OutlinedButton(
                            onClick = onStopScan,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("停止") }
                    }

                    if (debugMode) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("只显示 FFE0 设备")
                            Switch(checked = filterFfe0, onCheckedChange = onFilterChange)
                        }
                        Text(status)
                        Text("已发现：${list.size}")
                    }
                }
            }

            ElevatedCard {
                Column(Modifier.padding(vertical = 4.dp)) {
                    if (list.isEmpty()) {
                        Text(
                            "暂无设备",
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(list) { item ->
                            ListItem(
                                headlineContent = { Text(item.name) },
                                supportingContent = {
                                    if (debugMode) {
                                        Text("MAC: ${item.address}   RSSI: ${item.rssi}")
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onClickItem(item) }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ModeBtn(
        text: String,
        selected: Boolean,
        onClick: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        if (selected) {
            Button(onClick = onClick, modifier = modifier) { Text(text) }
        } else {
            OutlinedButton(onClick = onClick, modifier = modifier) { Text(text) }
        }
    }

    @Composable
    private fun DetailScreen(
        innerPadding: PaddingValues,
        dev: BleDeviceItem?,
        conn: String,
        svc: String,
        txSt: String,
        micSt: String,
        fileSt: String,
        uiMode: TxUiMode,
        audioParams: AudioParams,
        debugMode: Boolean,
        fileName: String,
        fileProgress: Float,
        fileProgressText: String,
        fileDurationMs: Int,
        fileSeeking: Boolean,
        onDebugModeChange: (Boolean) -> Unit,
        onAudioParamsChange: (AudioParams) -> Unit,
        onFileSeekStart: () -> Unit,
        onFileSeek: (Float) -> Unit,
        onFileSeekEnd: (Float) -> Unit,
        onBack: () -> Unit,
        onReconnect: () -> Unit,
        onToggleSaw: () -> Unit,
        onToggleMic: () -> Unit,
        onToggleFile: () -> Unit,
        onPickFile: () -> Unit,
        onDisconnect: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("设备详情", style = MaterialTheme.typography.titleLarge)
                    Text(dev?.name ?: "未选择设备", style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("进阶设置")
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = debugMode, onCheckedChange = onDebugModeChange)
                }
            }

            // ✅ 返回/重连/断开 同一行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Spacer(Modifier.width(0.dp))
                OutlinedButton(
                    onClick = onBack,
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White)
                ) { Text("返回") }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(
                    onClick = onReconnect,
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White)
                ) { Text("重连") }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White)
                ) { Text("断开") }
            }

            ElevatedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("运行模式", style = MaterialTheme.typography.titleMedium)
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (debugMode) {
                            ModeBtn(
                                text = "锯齿",
                                selected = (uiMode == TxUiMode.SAW),
                                onClick = onToggleSaw,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        ModeBtn(
                            text = "麦克风",
                            selected = (uiMode == TxUiMode.MIC),
                            onClick = onToggleMic,
                            modifier = Modifier.fillMaxWidth()
                        )
                        ModeBtn(
                            text = "文件",
                            selected = (uiMode == TxUiMode.FILE),
                            onClick = onToggleFile,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (debugMode) {
                        Text("连接：$conn")
                        Text("状态：$svc")
                        Text("MAC: ${dev?.address ?: "(none)"}")
                        Text(txSt)
                        Text(micSt)
                        Text(fileSt)
                    }
                }
            }

            ElevatedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("文件频谱", style = MaterialTheme.typography.titleMedium)
                    Text("当前：$fileName")
                    LinearProgressIndicator(
                        progress = { fileProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Slider(
                        value = fileProgress.coerceIn(0f, 1f),
                        onValueChange = {
                            if (!fileSeeking) onFileSeekStart()
                            onFileSeek(it)
                        },
                        onValueChangeFinished = { onFileSeekEnd(fileProgress) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = fileDurationMs > 0
                    )
                    Text(fileProgressText, style = MaterialTheme.typography.bodySmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        OutlinedButton(onClick = onPickFile) { Text("选择文件") }
                    }
                }
            }

            if (debugMode) {
                ElevatedCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("文件频谱参数", style = MaterialTheme.typography.titleMedium)
                        Text("floor=${fileFloorDbState.value}  range=${fileRangeDbState.value}")

                        var fileParamText by remember {
                            mutableStateOf("${fileFloorDbState.value.toInt()},${fileRangeDbState.value.toInt()}")
                        }
                        var fileHint by remember { mutableStateOf("") }

                        OutlinedTextField(
                            value = fileParamText,
                            onValueChange = { fileParamText = it },
                            singleLine = true,
                            label = { Text("floor,range（例：10,90）") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Button(onClick = {
                                val parsed = parseFileParams(fileParamText)
                                if (parsed == null) {
                                    fileHint = "格式错误：请输入 “10,90”"
                                } else {
                                    fileFloorDbState.value = parsed.first
                                    fileRangeDbState.value = parsed.second
                                    fileHint = "已应用：floor=${parsed.first}, range=${parsed.second}"
                                    if (uiMode == TxUiMode.FILE) {
                                        stopAllTx()
                                        startFileSpectrum()
                                        startTx400HZ(TxSource.FILE_400HZ)
                                        txUiModeState.value = TxUiMode.FILE
                                    }
                                }
                            }) { Text("应用") }

                            Spacer(Modifier.width(12.dp))

                            OutlinedButton(onClick = {
                                fileFloorDbState.value = 10f
                                fileRangeDbState.value = 90f
                                fileParamText = "10,90"
                                fileHint = "已重置默认参数"
                                if (uiMode == TxUiMode.FILE) {
                                    stopAllTx()
                                    startFileSpectrum()
                                    startTx400HZ(TxSource.FILE_400HZ)
                                    txUiModeState.value = TxUiMode.FILE
                                }
                            }) { Text("默认") }
                        }

                        if (fileHint.isNotEmpty()) {
                            Text(fileHint, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                ElevatedCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("音频参数", style = MaterialTheme.typography.titleMedium)
                        Text("采样率=${audioParams.sampleRate}  块=${audioParams.blockSize}  floor=${audioParams.floorDb} dB")

                        var paramText by remember {
                            mutableStateOf("${audioParams.sampleRate},${audioParams.blockSize},${audioParams.floorDb.toInt()}")
                        }
                        var hint by remember { mutableStateOf("") }

                        OutlinedTextField(
                            value = paramText,
                            onValueChange = { paramText = it },
                            singleLine = true,
                            label = { Text("采样率,块,floor（例：48000,512,20）") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Button(onClick = {
                                val parsed = parseAudioParamsSingleField(paramText, audioParams)
                                if (parsed == null) {
                                    hint = "格式错误：请输入 “48000,512,20” 或只填 “20”"
                                } else {
                                    val applied = parsed.copy(blockSize = normalizeBlockSize(parsed.blockSize))
                                    onAudioParamsChange(applied)
                                    hint = "已应用：sr=${applied.sampleRate}, block=${applied.blockSize}, floor=${applied.floorDb}"
                                    // 如果正在 MIC 模式：让参数立即生效（重启 MIC 引擎）
                                    if (uiMode == TxUiMode.MIC) {
                                        stopAllTx()
                                        startMicSpectrum()
                                        startTx400HZ(TxSource.MIC_400HZ)
                                        txUiModeState.value = TxUiMode.MIC
                                    }
                                }
                            }) { Text("应用") }

                            Spacer(Modifier.width(12.dp))

                            OutlinedButton(onClick = {
                                val def = AudioParams(48000, 512, 20f)
                                paramText = "48000,512,20"
                                onAudioParamsChange(def)
                                hint = "已重置默认参数"
                                if (uiMode == TxUiMode.MIC) {
                                    stopAllTx()
                                    startMicSpectrum()
                                    startTx400HZ(TxSource.MIC_400HZ)
                                    txUiModeState.value = TxUiMode.MIC
                                }
                            }) { Text("默认") }
                        }

                        if (hint.isNotEmpty()) {
                            Text(hint, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }

    // ---------------- Permissions ----------------

    private fun hasPerm(p: String): Boolean =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun ensureBlePermOrHint(): Boolean {
        val need = mutableListOf<String>()
        if (!hasPerm(Manifest.permission.BLUETOOTH_SCAN)) need += Manifest.permission.BLUETOOTH_SCAN
        if (!hasPerm(Manifest.permission.BLUETOOTH_CONNECT)) need += Manifest.permission.BLUETOOTH_CONNECT
        return if (need.isNotEmpty()) {
            statusState.value = "缺少 BLE 权限：请允许“附近设备”"
            permLauncher.launch(need.toTypedArray())
            false
        } else true
    }

    private fun ensureMicPermOrHint(): Boolean {
        return if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
            statusState.value = "缺少麦克风权限：请允许录音"
            permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            false
        } else true
    }

    // ---------------- Audio Params utils ----------------

    private fun parseAudioParamsSingleField(input: String, current: AudioParams): AudioParams? {
        val t = input.trim()
        if (t.isEmpty()) return null
        val parts = t.split(',', ' ', '/', ';', '|')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        fun toIntSafe(s: String) = s.toIntOrNull()
        fun toFloatSafe(s: String) = s.toFloatOrNull()

        return when (parts.size) {
            1 -> {
                val f = toFloatSafe(parts[0]) ?: return null
                current.copy(floorDb = f)
            }
            3 -> {
                val sr = toIntSafe(parts[0]) ?: return null
                val bs = toIntSafe(parts[1]) ?: return null
                val fl = toFloatSafe(parts[2]) ?: return null
                if (sr <= 0 || bs <= 0) return null
                current.copy(sampleRate = sr, blockSize = bs, floorDb = fl)
            }
            else -> null
        }
    }

    private fun parseFileParams(input: String): Pair<Float, Float>? {
        val t = input.trim()
        if (t.isEmpty()) return null
        val parts = t.split(',', ' ', '/', ';', '|')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        fun toFloatSafe(s: String) = s.toFloatOrNull()

        return if (parts.size == 2) {
            val floor = toFloatSafe(parts[0]) ?: return null
            val range = toFloatSafe(parts[1]) ?: return null
            if (range <= 0f || floor < 0f) return null
            floor to range
        } else null
    }

    private fun getDisplayName(uri: Uri): String? {
        return try {
            val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
            contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeBlockSize(n: Int): Int {
        if (n <= 0) return 512
        var v = 1
        while (v < n) v = v shl 1
        return v.coerceAtLeast(128).coerceAtMost(8192)
    }

    // ---------------- BLE scan ----------------

    @SuppressLint("MissingPermission")
    private fun startScan(filterFfe0: Boolean) {
        if (!ensureBlePermOrHint()) return

        val adapter = btAdapter
        val sc = scanner

        if (adapter == null) {
            statusState.value = "未检测到蓝牙适配器"
            return
        }
        if (!adapter.isEnabled) {
            statusState.value = "请先打开手机蓝牙"
            return
        }
        if (sc == null) {
            statusState.value = "BLE 扫描器不可用"
            return
        }
        if (scanning) return

        scanList.clear()
        statusState.value = if (filterFfe0) "扫描中…（过滤 FFE0）" else "扫描中…"
        scanning = true

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .build()

        val filters: List<ScanFilter>? = if (filterFfe0) {
            listOf(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(UUID_FFE0))
                    .build()
            )
        } else null

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device ?: return
                val addr = dev.address ?: return
                val rssi = result.rssi

                val name = if (hasPerm(Manifest.permission.BLUETOOTH_CONNECT)) {
                    dev.name ?: result.scanRecord?.deviceName ?: "(no name)"
                } else {
                    result.scanRecord?.deviceName ?: "(no name)"
                }

                uiHandler.post {
                    val idx = scanList.indexOfFirst { it.address == addr }
                    val item = BleDeviceItem(name, addr, rssi, dev)
                    if (idx >= 0) scanList[idx] = item else scanList.add(item)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                uiHandler.post { statusState.value = "扫描失败：$errorCode" }
            }
        }

        scanCallback = cb
        try {
            sc.startScan(filters, settings, cb)
        } catch (e: SecurityException) {
            scanning = false
            scanCallback = null
            statusState.value = "启动扫描被拒：${e.message}"
            return
        }

        uiHandler.postDelayed({
            if (scanning) stopScan()
        }, 10_000)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        try {
            val sc = scanner
            val cb = scanCallback
            if (sc != null && cb != null) sc.stopScan(cb)
        } catch (_: Exception) {
        } finally {
            scanning = false
            scanCallback = null
            statusState.value = "已停止"
        }
    }

    // ---------------- BLE connect / gatt ----------------

    @SuppressLint("MissingPermission")
    private fun connectTo(item: BleDeviceItem) {
        if (!ensureBlePermOrHint()) return

        stopAllTx()
        safeCloseGatt()

        selectedDevice.value = item
        screenState.value = Screen.DETAIL

        connState.value = "连接中…"
        servicesState.value = "服务未发现"
        txState.value = "TX: STOP"
        micState.value = "MIC: STOP"
        txUiModeState.value = TxUiMode.STOP

        try {
            gatt =
                item.device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            connState.value = "连接被拒：${e.message}"
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnect() {
        stopAllTx()
        try {
            gatt?.disconnect()
        } catch (_: Exception) {
        }
        safeCloseGatt()
        uiHandler.post {
            connState.value = "已断开"
            servicesState.value = "服务未发现"
        }
    }

    private fun reconnect() {
        val item = selectedDevice.value ?: return
        connectTo(item)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            uiHandler.post { connState.value = "status=$status state=$newState" }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                stopAllTx()
                safeCloseGatt()
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                uiHandler.post { connState.value = "已连接，发现服务中…" }
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                stopAllTx()
                safeCloseGatt()
                uiHandler.post {
                    connState.value = "已断开"
                    servicesState.value = "服务未发现"
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                uiHandler.post { servicesState.value = "服务发现失败：$status" }
                return
            }

            val svc = g.getService(UUID_FFE0)
            val wc = svc?.getCharacteristic(UUID_FFE1)
            val nc = svc?.getCharacteristic(UUID_FFE2)

            writeChar = wc
            notifyChar = nc

            uiHandler.post {
                servicesState.value = "服务已发现"
            }

            if (nc != null) {
                enableNotify(g, nc)
            }
        }

        @Deprecated("Deprecated in Java")


        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            // 可选：处理上行
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotify(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        try {
            val ok = g.setCharacteristicNotification(c, true)
            val desc = c.getDescriptor(UUID_CCCD)
            if (!ok || desc == null) return

            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(desc)
            }
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeCloseGatt() {
        try {
            gatt?.close()
        } catch (_: Exception) {
        } finally {
            gatt = null
            writeChar = null
            notifyChar = null
        }
    }

    // ---------------- TX 400HZ ----------------

    private val txRunnable = object : Runnable {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun run() {
            if (!txRunning) return

            val bands = when (txSource) {
                TxSource.SAW_400HZ -> {
                    IntArray(12) { i -> ((i * 20 + testPhase) and 0xFF) }
                        .also { testPhase = (testPhase + 10) and 0xFF }
                }
                TxSource.MIC_400HZ -> latestBandsRef.get()
                TxSource.FILE_400HZ -> latestBandsRef.get()
            }

            sendBandsFrame(bands)
            val now = SystemClock.uptimeMillis().toDouble()
            if (txNextTimeMs == 0.0) txNextTimeMs = now
            txNextTimeMs += txPeriodMs
            val delay = (txNextTimeMs - now).coerceAtLeast(0.0)
            txHandler.postDelayed(this, delay.toLong()) // 400Hz (≈2.5ms)
        }
    }

    private fun startTx400HZ(source: TxSource) {
        if (!ensureBlePermOrHint()) return
        if (gatt == null || writeChar == null) {
            txState.value = "TX: 未连接或未找到 FFE1"
            return
        }

        txSource = source

        if (txRunning) {
            txState.value = "TX: RUN (400Hz, ${source.name})"
            return
        }

        txRunning = true
        txState.value = "TX: RUN (400Hz, ${source.name})"
        ForegroundKeepAliveService.start(this)
        txHandler.post(txRunnable)
    }

    private fun stopTx400HZ() {
        if (!txRunning) return
        txRunning = false
        txHandler.removeCallbacksAndMessages(null)
        txNextTimeMs = 0.0
        txState.value = "TX: STOP"
        ForegroundKeepAliveService.stop(this)
    }

    // ---------------- MIC spectrum ----------------

    private fun applyFloorToProcessorIfPossible(processor: Any, floorDb: Float) {
        // 1) 尝试 public setFloorDb(float)
        try {
            val m: Method? = processor::class.java.methods.firstOrNull {
                it.name.equals("setFloorDb", ignoreCase = true) &&
                        it.parameterTypes.size == 1 &&
                        (it.parameterTypes[0] == Float::class.javaPrimitiveType || it.parameterTypes[0] == Float::class.java)
            }
            if (m != null) {
                m.invoke(processor, floorDb)
                return
            }
        } catch (_: Exception) {}

        // 2) 尝试字段 floorDb
        try {
            val f = processor::class.java.declaredFields.firstOrNull { it.name == "floorDb" }
            if (f != null) {
                f.isAccessible = true
                f.setFloat(processor, floorDb)
                return
            }
        } catch (_: Exception) {}
    }

    private fun startMicSpectrum() {
        if (!ensureMicPermOrHint()) return
        if (micEngine != null) return

        val p = audioParamsState.value
        val sr = p.sampleRate.coerceIn(8000, 192000)
        val fft = normalizeBlockSize(p.blockSize)

        // 400Hz 刷新目标：hop ≈ sr/400；并保证 hop < fft
        val hop = max(80, sr / 400).coerceAtMost(fft / 2)

        val processor = Spectrum12Processor(
            sampleRate = sr,
            fftSize = fft,
            bands = 12,
            fMin = 60f,
            fMax = (sr / 2f).coerceAtMost(8000f)
        )

        // floor 如果 Spectrum12Processor 支持，就会生效
        applyFloorToProcessorIfPossible(processor, p.floorDb)

        val engine = MicSpectrumEngine(
            sampleRate = sr,
            fftSize = fft,
            hopSize = hop,
            processor = processor
        ) { bands12 ->
            latestBandsRef.set(bands12)
        }

        val ok = try {
            engine.start()
        } catch (_: SecurityException) {
            micState.value = "MIC: 权限被拒绝"
            false
        }
        if (ok) {
            micEngine = engine
            micState.value = "MIC: RUN (sr=$sr, fft=$fft, hop=$hop, floor=${p.floorDb}dB)"
        } else {
            if (micState.value != "MIC: 权限被拒绝") {
                micState.value = "MIC: START FAIL"
            }
            engine.stop()
        }
    }

    private fun stopMicSpectrum() {
        micEngine?.stop()
        micEngine = null
        micState.value = "MIC: STOP"
        latestBandsRef.set(IntArray(12))
    }

    private fun startFileSpectrum() {
        val uri = fileUriState.value
        if (uri == null) {
            fileState.value = "FILE: 未选择文件"
            return
        }
        if (fileEngine != null) return

        val p = audioParamsState.value
        val fft = normalizeBlockSize(p.blockSize)

        val engine = FileSpectrumEngine(
            context = this,
            uri = uri,
            fftSize = fft,
            hopSizeProvider = { sr -> max(80, sr / 400) },
            processorFactory = { sr ->
                Spectrum12Processor(
                    sampleRate = sr,
                    fftSize = fft,
                    bands = 12,
                    fMin = 60f,
                    fMax = (sr / 2f).coerceAtMost(8000f)
                ).also {
                    applyFloorToProcessorIfPossible(it, fileFloorDbState.value)
                    it.setRangeDb(fileRangeDbState.value)
                }
            },
            onBands = { bands12 -> latestBandsRef.set(bands12) },
            onInfo = { sr, ch ->
                fileState.value = "FILE: RUN (sr=$sr, ch=$ch, fft=$fft, floor=${p.floorDb}dB)"
            },
            playAudio = true,
            onProgress = { posUs, durUs ->
                uiHandler.post {
                    if (durUs > 0) {
                        fileDurationMsState.value = (durUs / 1000L).toInt()
                    }
                    if (fileSeekingState.value) return@post
                    if (durUs > 0) {
                        val progress = (posUs.toDouble() / durUs.toDouble()).toFloat()
                        fileProgressState.value = progress.coerceIn(0f, 1f)
                        val posMs = (posUs / 1000L).toInt()
                        val durMs = (durUs / 1000L).toInt()
                        fileProgressTextState.value = "${fmtMmSs(posMs)} / ${fmtMmSs(durMs)}"
                    }
                }
            }
        )

        val ok = try {
            engine.start()
        } catch (e: SecurityException) {
            fileState.value = "FILE: 权限被拒绝"
            false
        }

        if (ok) {
            fileEngine = engine
            if (!fileState.value.startsWith("FILE: RUN")) {
                fileState.value = "FILE: RUN (fft=$fft, floor=${p.floorDb}dB)"
            }
        } else {
            if (fileState.value != "FILE: 权限被拒绝") {
                fileState.value = "FILE: START FAIL"
            }
            engine.stop()
        }
    }

    private fun stopFileSpectrum() {
        fileEngine?.stop()
        fileEngine = null
        fileState.value = "FILE: STOP"
        latestBandsRef.set(IntArray(12))
        fileProgressState.value = 0f
        fileProgressTextState.value = "00:00 / 00:00"
        fileDurationMsState.value = 0
    }

    private fun stopAllTx() {
        stopTx400HZ()
        stopMicSpectrum()
        stopFileSpectrum()
        txUiModeState.value = TxUiMode.STOP
    }

    // ---------------- Send frame ----------------

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun sendBandsFrame(bands12: IntArray) {
        val g = gatt ?: run {
            txState.value = "TX: 未连接"
            return
        }
        val wc = writeChar ?: run {
            txState.value = "TX: 未找到 FFE1"
            return
        }

        val payload = buildFrame(bands12)

        try {
            val supportsWnr =
                (wc.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            val writeType =
                if (supportsWnr) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(wc, payload, writeType)
            } else {
                wc.writeType = writeType
                @Suppress("DEPRECATION")
                wc.value = payload
                @Suppress("DEPRECATION")
                g.writeCharacteristic(wc)
            }
        } catch (_: SecurityException) {
            txState.value = "TX: 权限被拒绝"
        } catch (e: Exception) {
            txState.value = "TX: 写入异常 ${e.message}"
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onDestroy() {
        try { stopScan() } catch (_: Exception) {}
        try { stopAllTx() } catch (_: Exception) {}
        safeCloseGatt()
        super.onDestroy()
    }

    // ---------------- Helpers ----------------

    private fun buildFrame(bands12: IntArray): ByteArray {
        // 16 bytes: [ADDR=0x01][FUNC=0x20][12*U8][CRC16_L][CRC16_H]
        val out = ByteArray(16)
        out[0] = 0x01
        out[1] = 0x20
        for (i in 0 until 12) {
            out[2 + i] = (bands12.getOrNull(i) ?: 0).toByte()
        }
        val crc = crc16Modbus(out)
        out[14] = (crc and 0xFF).toByte()
        out[15] = ((crc ushr 8) and 0xFF).toByte()
        return out
    }

    private fun crc16Modbus(data: ByteArray): Int {
        var crc = 0xFFFF
        for (i in 0 until 14) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 0x0001) != 0) (crc ushr 1) xor 0xA001 else (crc ushr 1)
            }
        }
        return crc and 0xFFFF
    }

    private fun fmtMmSs(ms: Int): String {
        val s = max(0, ms / 1000)
        val m = s / 60
        val r = s % 60
        return String.format("%02d:%02d", m, r)
    }
}
