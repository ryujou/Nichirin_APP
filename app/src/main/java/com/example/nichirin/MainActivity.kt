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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.core.content.ContextCompat
import com.example.nichirin.dsp.MicSpectrumEngine
import com.example.nichirin.dsp.Spectrum12Processor
import com.example.nichirin.dsp.FileSpectrumEngine
import com.example.nichirin.ui.theme.NichirinTheme
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.pow
import kotlin.random.Random

// 你工程里如果已有这些，删除本文件的重复定义即可


// FFE0/FFE1/FFE2（16-bit）对应 128-bit 基准



private enum class TxSource { SAW_400HZ, MIC_400HZ, FILE_400HZ }
private enum class TxUiMode { STOP, SAW, MIC, FILE }

private data class AudioParams(
    val sampleRate: Int = 48000,
    val blockSize: Int = 512,
    val floorDb: Float = 20f,
)

private data class LampConfig(
    val mode: Int = 5,
    val hue: Int = 0,
    val sat: Int = 255,
    val value: Int = 255,
    val param: Int = 128,
)

private data class HsvColor(val h: Int, val s: Int, val v: Int)

private enum class DetailTab { CONSOLE, CHARACTERS }

private const val COLOR_TX_GAMMA = 2.2

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
    private var txPeriodMs = 1000.0 / 400.0
    private var txNextTimeMs = 0.0
    private var bandChunkOffset = 0
    private var writeInFlight = false
    private var writeInFlightToken = 0
    private val txQueue: ArrayDeque<ByteArray> = ArrayDeque()

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
    private val fileProgressState = mutableFloatStateOf(0f)
    private val fileProgressTextState = mutableStateOf("00:00 / 00:00")
    private val fileDurationMsState = mutableIntStateOf(0)
    private val fileSeekingState = mutableStateOf(false)
    private val fileFloorDbState = mutableFloatStateOf(10f)
    private val fileRangeDbState = mutableFloatStateOf(90f)

    private val lampConfigState = mutableStateOf(LampConfig())
    private val configStatusState = mutableStateOf("配置未发送")
    private val detailTabState = mutableStateOf(DetailTab.CONSOLE)

    private var awaitingConfigRead = false
    private val notifyBuffer = java.io.ByteArrayOutputStream()
    private var configReadToken = 0
    private var negotiatedMtu = 23

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
            fileProgressState.floatValue = 0f
            fileProgressTextState.value = "00:00 / 00:00"
            fileDurationMsState.intValue = 0
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
                val lampConfig by lampConfigState
                val configStatus by configStatusState
                val detailTab by detailTabState
                val context = LocalContext.current
                val cardImageUrls = remember(context) {
                    CardRepository.loadCardImageUrls(context)
                }
                val characters = remember(context) {
                    CharacterRepository.loadCharacters(context)
                }
                val dynamicBackgroundUrl = remember(cardImageUrls) {
                    cardImageUrls.takeIf { it.isNotEmpty() }?.random()
                }
                val defaultBackgroundPainter = painterResource(id = R.drawable.background)
                val backgroundAlignment = remember {
                    if (Random.nextBoolean()) Alignment.BottomStart else Alignment.BottomEnd
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = dynamicBackgroundUrl ?: R.drawable.background,
                        contentDescription = null,
                        placeholder = defaultBackgroundPainter,
                        error = defaultBackgroundPainter,
                        fallback = defaultBackgroundPainter,
                        contentScale = ContentScale.None,
                        modifier = Modifier.align(backgroundAlignment)
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
                                onFilterChange = { filterFfe0 = it },
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
                                detailTab = detailTab,
                                lampConfig = lampConfig,
                                configStatus = configStatus,
                                characters = characters,
                                fileName = fileNameState.value,
                                fileProgress = fileProgressState.floatValue,
                                fileProgressText = fileProgressTextState.value,
                                fileDurationMs = fileDurationMsState.intValue,
                                fileSeeking = fileSeekingState.value,
                                onDebugModeChange = { debugModeState.value = it },
                                onDetailTabChange = { detailTabState.value = it },
                                onLampConfigChange = { updateLampConfig(it) },
                                onSendConfig = { sendLampConfig(lampConfigState.value) },
                                onReadConfig = { requestConfigRead() },
                                onPickCharacterColor = { hex -> setLampColorFromHex(hex) },
                                onAudioParamsChange = { audioParamsState.value = it },
                                onFileSeekStart = { fileSeekingState.value = true },
                                onFileSeek = { progress ->
                                    fileProgressState.floatValue = progress.coerceIn(0f, 1f)
                                    val dur = fileDurationMsState.intValue
                                    val pos = (dur * fileProgressState.floatValue).toInt()
                                    fileProgressTextState.value = "${fmtMmSs(pos)} / ${fmtMmSs(dur)}"
                                },
                                onFileSeekEnd = { progress ->
                                    fileSeekingState.value = false
                                    val durMs = fileDurationMsState.intValue
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

                                        if (lampConfigState.value.mode != 5) {

                                            configStatusState.value = "请先切换到模式 5（频谱）"

                                        } else {

                                            // Switch to MIC: stop file engine first to avoid resource conflict

                                            stopTx400HZ()

                                            stopFileSpectrum()

                                            stopMicSpectrum()

                                            sendLampConfig(lampConfigState.value)

                                            startMicSpectrum()          // Reads audioParamsState

                                            startTx400HZ(TxSource.MIC_400HZ)

                                            txUiModeState.value = TxUiMode.MIC

                                        }

                                    }

                                },

                                onToggleFile = {

                                    if (txUiModeState.value == TxUiMode.FILE) {

                                        stopAllTx()

                                    } else {

                                        if (lampConfigState.value.mode != 5) {

                                            configStatusState.value = "请先切换到模式 5（频谱）"

                                        } else {

                                            // Switch to FILE: stop mic first, then start file engine

                                            stopTx400HZ()

                                            stopMicSpectrum()

                                            stopFileSpectrum()

                                            sendLampConfig(lampConfigState.value)

                                            startFileSpectrum()

                                            startTx400HZ(TxSource.FILE_400HZ)

                                            txUiModeState.value = TxUiMode.FILE

                                        }

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
                Row(verticalAlignment = Alignment.CenterVertically) {
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

    @OptIn(ExperimentalMaterial3Api::class)
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
        detailTab: DetailTab,
        lampConfig: LampConfig,
        configStatus: String,
        characters: List<CharacterColor>,
        fileName: String,
        fileProgress: Float,
        fileProgressText: String,
        fileDurationMs: Int,
        fileSeeking: Boolean,
        onDebugModeChange: (Boolean) -> Unit,
        onDetailTabChange: (DetailTab) -> Unit,
        onLampConfigChange: (LampConfig) -> Unit,
        onSendConfig: () -> Unit,
        onReadConfig: () -> Unit,
        onPickCharacterColor: (String) -> Unit,
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
        val hsv = remember(lampConfig.hue, lampConfig.sat, lampConfig.value) {
            HsvColor(lampConfig.hue, lampConfig.sat, lampConfig.value)
        }
        val currentHex = remember(hsv) { hsvToHex(hsv) }
        var hexInput by remember { mutableStateOf(currentHex) }
        LaunchedEffect(currentHex) {
            hexInput = currentHex
        }

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("进阶设置")
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = debugMode, onCheckedChange = onDebugModeChange)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                ModeBtn(
                    text = "控制台",
                    selected = detailTab == DetailTab.CONSOLE,
                    onClick = { onDetailTabChange(DetailTab.CONSOLE) },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                ModeBtn(
                    text = "角色配色",
                    selected = detailTab == DetailTab.CHARACTERS,
                    onClick = { onDetailTabChange(DetailTab.CHARACTERS) },
                    modifier = Modifier.weight(1f)
                )
            }

            if (detailTab == DetailTab.CHARACTERS) {
                ElevatedCard {
                    Column(Modifier.padding(16.dp)) {
                        CharacterPalette(characters = characters, onPickColor = onPickCharacterColor)
                    }
                }
            } else {
                ElevatedCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("模式与颜色", style = MaterialTheme.typography.titleMedium)

                        val modeOptions = listOf(
                            1 to "模式 1 · 流水",
                            2 to "模式 2 · 爆闪",
                            3 to "模式 3 · 常亮",
                            4 to "模式 4 · 呼吸",
                            5 to "模式 5 · 频谱"
                        )
                        val modeLabel = modeOptions.firstOrNull { it.first == lampConfig.mode }?.second
                            ?: "模式 ${lampConfig.mode}"
                        var expanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                            OutlinedTextField(
                                value = modeLabel,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("模式") },
                                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth()
                            )
                            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                modeOptions.forEach { (id, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            expanded = false
                                            onLampConfigChange(lampConfig.copy(mode = id))
                                        }
                                    )
                                }
                            }
                        }

                        if (lampConfig.mode != 5) {
                            Text("参数: ${lampConfig.param} (${formatParamHint(lampConfig.mode, lampConfig.param)})")
                            Slider(
                                value = lampConfig.param.toFloat(),
                                onValueChange = { onLampConfigChange(lampConfig.copy(param = it.toInt())) },
                                valueRange = 0f..255f
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                val rgb = parseHexColor(currentHex)
                                val previewColor = rgb?.let { Color(android.graphics.Color.rgb(it.first, it.second, it.third)) }
                                    ?: Color.White
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(previewColor)
                                        .border(1.dp, Color.White, CircleShape)
                                )
                                OutlinedTextField(
                                    value = hexInput,
                                    onValueChange = { hexInput = it },
                                    singleLine = true,
                                    label = { Text("色号") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                                    modifier = Modifier.weight(1f)
                                )
                                Button(onClick = {
                                    val parsed = parseHexColor(hexInput)
                                    if (parsed != null) {
                                        val newHsv = sanitizeLampHsv(rgbToHsv(parsed.first, parsed.second, parsed.third), lampConfig.hue)
                                        onLampConfigChange(
                                            lampConfig.copy(hue = newHsv.h, sat = newHsv.s, value = newHsv.v)
                                        )
                                    }
                                }) {
                                    Text("应用")
                                }
                            }

                            Text("H: ${lampConfig.hue}")
                            Slider(
                                value = lampConfig.hue.toFloat(),
                                onValueChange = { onLampConfigChange(lampConfig.copy(hue = it.toInt())) },
                                valueRange = 0f..359f
                            )
                            Text("S: ${lampConfig.sat}")
                            Slider(
                                value = lampConfig.sat.toFloat(),
                                onValueChange = { onLampConfigChange(lampConfig.copy(sat = it.toInt())) },
                                valueRange = 0f..255f
                            )
                            Text("V: ${lampConfig.value}")
                            Slider(
                                value = lampConfig.value.toFloat(),
                                onValueChange = { onLampConfigChange(lampConfig.copy(value = it.toInt())) },
                                valueRange = 0f..255f
                            )
                        } else {
                            Text("频谱模式下隐藏颜色与参数", style = MaterialTheme.typography.bodySmall)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            OutlinedButton(onClick = onReadConfig) { Text("读取配置") }
                            Spacer(Modifier.width(12.dp))
                            Button(onClick = onSendConfig) { Text("发送配置") }
                        }

                        if (configStatus.isNotBlank()) {
                            Text(configStatus, color = MaterialTheme.colorScheme.primary)
                        }

                        if (debugMode) {
                            Text("连接: $conn")
                            Text("状态: $svc")
                            Text("MAC: ${dev?.address ?: "(none)"}")
                            Text(txSt)
                            Text(micSt)
                            Text(fileSt)
                            Text("MTU: $negotiatedMtu")
                        }
                    }
                }

                if (lampConfig.mode == 5) {
                    ElevatedCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("频谱输入", style = MaterialTheme.typography.titleMedium)
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
                        }
                    }

                    ElevatedCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("文件播放", style = MaterialTheme.typography.titleMedium)
                            Text("当前: $fileName")
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
                                Text("floor=${fileFloorDbState.floatValue}  range=${fileRangeDbState.floatValue}")

                                var fileParamText by remember {
                                    mutableStateOf("${fileFloorDbState.floatValue.toInt()},${fileRangeDbState.floatValue.toInt()}")
                                }
                                var fileHint by remember { mutableStateOf("") }

                                OutlinedTextField(
                                    value = fileParamText,
                                    onValueChange = { fileParamText = it },
                                    singleLine = true,
                                    label = { Text("floor,range(例: 10,90)") },
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Button(onClick = {
                                        val parsed = parseFileParams(fileParamText)
                                        if (parsed == null) {
                                            fileHint = "格式错误: 请输入 10,90"
                                        } else {
                                            fileFloorDbState.floatValue = parsed.first
                                            fileRangeDbState.floatValue = parsed.second
                                            fileHint = "已应用: floor=${parsed.first}, range=${parsed.second}"
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
                                        fileFloorDbState.floatValue = 10f
                                        fileRangeDbState.floatValue = 90f
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
                                    label = { Text("采样率,块,floor(例: 48000,512,20)") },
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Button(onClick = {
                                        val parsed = parseAudioParamsSingleField(paramText, audioParams)
                                        if (parsed == null) {
                                            hint = "格式错误: 请输入 48000,512,20 或 20"
                                        } else {
                                            val applied = parsed.copy(blockSize = normalizeBlockSize(parsed.blockSize))
                                            onAudioParamsChange(applied)
                                            hint = "已应用: sr=${applied.sampleRate}, block=${applied.blockSize}, floor=${applied.floorDb}"
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
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun CharacterPalette(
        characters: List<CharacterColor>,
        onPickColor: (String) -> Unit
    ) {
        data class BandPalette(val label: String, val hex: String, val icon: String)

        var search by remember { mutableStateOf("") }
        var bandFilter by remember { mutableStateOf("") }
        val bandPalettes = remember {
            mapOf(
                "Poppin'Party" to BandPalette("POP PINK", "#FF69B4", "assets/band_icon/Popipa_icon.png"),
                "Afterglow" to BandPalette("SIGNAL RED", "#9B2423", "assets/band_icon/Afterglow_icon.png"),
                "Pastel*Palettes" to BandPalette("PASTEL GREEN", "#B9CEAC", "assets/band_icon/Pastel_Palettes_icon.png"),
                "Roselia" to BandPalette("DARK BLUE", "#00008B", "assets/band_icon/Roselia_icon.png"),
                "Hello, Happy World!" to BandPalette("HAPPY YELLOW", "#FFD700", "assets/band_icon/HHW_icon.png"),
                "Morfonica" to BandPalette("SKY BLUE", "#007CB0", "assets/band_icon/Morfonica_icon.png"),
                "RAISE A SUILEN" to BandPalette("YELLOW GREEN", "#61993B", "assets/band_icon/RAS_icon_HD.png"),
                "MyGO!!!!!" to BandPalette("RAINY BLUE", "#4682B4", "assets/band_icon/MyGO_icon.png"),
                "Ave Mujica" to BandPalette("DARK RED", "#8B0000", "assets/band_icon/Icon_Ave_Mujica_temp.png"),
                "梦限大MewType" to BandPalette("MEW tyPINK", "#FF1493", "assets/band_icon/Mugendai_muw_type_icon.png"),
                "一家Dumb Rock!" to BandPalette("ICON COLOR", "#F4AF48", "assets/band_icon/Icon_ikka.png"),
                "millsage" to BandPalette("ICON COLOR", "#9C25ED", "assets/band_icon/Icon_millsage.png")
            )
        }
        val bands = remember(characters) {
            characters.mapNotNull { it.band.takeIf { band -> band.isNotBlank() } }
                .distinct()
                .sorted()
        }
        val filtered = remember(search, bandFilter, characters) {
            characters.filter { item ->
                val bandOk = bandFilter.isBlank() || item.band == bandFilter
                val nameOk = search.isBlank() || item.name.contains(search, ignoreCase = true)
                bandOk && nameOk
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("角色配色", style = MaterialTheme.typography.titleMedium)
            Text("点击角色头像切换应援色", style = MaterialTheme.typography.bodySmall)

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                singleLine = true,
                label = { Text("搜索角色") },
                modifier = Modifier.fillMaxWidth()
            )

            var expanded by remember { mutableStateOf(false) }
            val bandLabel = bandFilter.ifBlank { "全部乐队" }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                OutlinedTextField(
                    value = bandLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("乐队") },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text("全部乐队") }, onClick = {
                        bandFilter = ""
                        expanded = false
                    })
                    bands.forEach { band ->
                        DropdownMenuItem(text = { Text(band) }, onClick = {
                            bandFilter = band
                            expanded = false
                        })
                    }
                }
            }

            val bandPalette = bandPalettes[bandFilter]
            if (bandFilter.isNotBlank() && bandPalette != null) {
                BandColorRow(
                    band = bandFilter,
                    label = bandPalette.label,
                    hex = bandPalette.hex,
                    icon = bandPalette.icon,
                    onPickColor = onPickColor
                )
            }

            Text("共 ${filtered.size} 名角色", style = MaterialTheme.typography.bodySmall)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                filtered.forEach { item ->
                    CharacterRow(item = item, onPickColor = onPickColor)
                }
            }
        }
    }

    @Composable
    private fun BandColorRow(
        band: String,
        label: String,
        hex: String,
        icon: String,
        onPickColor: (String) -> Unit
    ) {
        val imagePath = remember(icon) { CharacterRepository.resolveImagePath(icon) }
        val rgb = remember(hex) { parseHexColor(hex) }
        val chipColor = rgb?.let { Color(android.graphics.Color.rgb(it.first, it.second, it.third)) }
            ?: Color.LightGray
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPickColor(hex) }
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (imagePath != null) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = imagePath,
                            contentDescription = band,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(band.take(1))
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(band, style = MaterialTheme.typography.bodyLarge)
                    Text(label, style = MaterialTheme.typography.bodySmall)
                }
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(chipColor)
                        .border(1.dp, Color.White, CircleShape)
                )
            }
        }
    }

    @Composable
    private fun CharacterRow(
        item: CharacterColor,
        onPickColor: (String) -> Unit
    ) {
        val imagePath = remember(item.image) { CharacterRepository.resolveImagePath(item.image) }
        val rgb = remember(item.hex) { parseHexColor(item.hex) }
        val chipColor = rgb?.let { Color(android.graphics.Color.rgb(it.first, it.second, it.third)) }
            ?: Color.LightGray
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPickColor(item.hex) }
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (imagePath != null) {
                    AsyncImage(
                        model = imagePath,
                        contentDescription = item.name,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(item.name.take(1))
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.bodyLarge)
                    if (item.band.isNotBlank()) {
                        Text(item.band, style = MaterialTheme.typography.bodySmall)
                    }
                }

                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(chipColor)
                        .border(1.dp, Color.White, CircleShape)
                )
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

    // ---------------- Lamp config / color utils ----------------

    private fun clampInt(v: Int, lo: Int, hi: Int): Int = v.coerceIn(lo, hi)

    private fun updateLampConfig(next: LampConfig) {
        val fixed = next.copy(
            mode = clampInt(next.mode, 1, 5),
            hue = clampInt(next.hue, 0, 359),
            sat = clampInt(next.sat, 0, 255),
            value = clampInt(next.value, 0, 255),
            param = clampInt(next.param, 0, 255)
        )
        val prevMode = lampConfigState.value.mode
        lampConfigState.value = fixed
        if (prevMode == 5 && fixed.mode != 5) {
            stopAllTx()
        }
    }

    private fun sendLampConfig(config: LampConfig) {
        if (!ensureBlePermOrHint()) return
        val txColor = applyGammaToHsvForTx(HsvColor(config.hue, config.sat, config.value))
        val values = if (config.mode == 5) {
            intArrayOf(
                config.mode and 0xFFFF,
                clampInt(txColor.h, 0, 359),
                clampInt(txColor.s, 0, 255),
                clampInt(txColor.v, 0, 255)
            )
        } else {
            intArrayOf(
                config.mode and 0xFFFF,
                clampInt(txColor.h, 0, 359),
                clampInt(txColor.s, 0, 255),
                clampInt(txColor.v, 0, 255),
                clampInt(config.param, 0, 255)
            )
        }
        val frame = buildWriteMultipleRegisters(REG_MODE, values)
        val ok = writeBlePayload(frame) { msg ->
            configStatusState.value = "配置发送失败：$msg"
        }
        if (ok) {
            configStatusState.value = "配置已发送"
        }
    }

    private fun requestConfigRead() {
        if (!ensureBlePermOrHint()) return
        if (txRunning) {
            configStatusState.value = "请先停止频谱发送"
            return
        }
        if (awaitingConfigRead) {
            configStatusState.value = "读取中，请稍候"
            return
        }
        val frame = buildReadHoldingRegisters(REG_MODE, 5)
        awaitingConfigRead = true
        notifyBuffer.reset()
        val token = ++configReadToken
        configStatusState.value = "读取中…"
        val ok = writeBlePayload(frame) { msg ->
            awaitingConfigRead = false
            configStatusState.value = "读取失败：$msg"
        }
        if (!ok) return
        uiHandler.postDelayed({
            if (awaitingConfigRead && configReadToken == token) {
                awaitingConfigRead = false
                configStatusState.value = "读取超时"
            }
        }, 1200)
    }

    private fun setLampColorFromHex(hex: String) {
        val rgb = parseHexColor(hex) ?: return
        val hsv = rgbToHsv(rgb.first, rgb.second, rgb.third)
        val fixed = sanitizeLampHsv(hsv, lampConfigState.value.hue)
        updateLampConfig(
            lampConfigState.value.copy(hue = fixed.h, sat = fixed.s, value = fixed.v)
        )
        sendLampConfig(lampConfigState.value)
    }

    private fun parseHexColor(input: String): Triple<Int, Int, Int>? {
        val raw = input.trim()
        if (raw.isEmpty()) return null
        val s = if (raw.startsWith("#")) raw.substring(1) else raw
        if (!s.matches(Regex("[0-9a-fA-F]{6}"))) return null
        val r = s.take(2).toInt(16)
        val g = s.substring(2, 4).toInt(16)
        val b = s.substring(4, 6).toInt(16)
        return Triple(r, g, b)
    }

    private fun toHex2(v: Int): String = v.coerceIn(0, 255).toString(16).padStart(2, '0').uppercase()

    private fun hsvToHex(hsv: HsvColor): String {
        val rgb = hsvToRgb(hsv.h, hsv.s, hsv.v)
        return "#${toHex2(rgb.first)}${toHex2(rgb.second)}${toHex2(rgb.third)}"
    }

    private fun sanitizeLampHsv(next: HsvColor, fallbackHue: Int): HsvColor {
        var h = next.h
        val s = next.s
        val v = next.v
        if (v <= 0) {
            h = fallbackHue
        }
        return HsvColor(clampInt(h, 0, 359), clampInt(s, 0, 255), clampInt(v, 0, 255))
    }

    private fun rgbToHsv(r: Int, g: Int, b: Int): HsvColor {
        val rn = r / 255f
        val gn = g / 255f
        val bn = b / 255f
        val max = maxOf(rn, gn, bn)
        val min = minOf(rn, gn, bn)
        val d = max - min
        var h = 0f
        if (d > 1e-6f) {
            h = when (max) {
                rn -> ((gn - bn) / d) % 6f
                gn -> (bn - rn) / d + 2f
                else -> (rn - gn) / d + 4f
            }
            h *= 60f
            if (h < 0f) h += 360f
        }
        val s = if (max == 0f) 0f else d / max
        return HsvColor(h.toInt() % 360, (s * 255f).toInt(), (max * 255f).toInt())
    }

    private fun hsvToRgb(h: Int, s: Int, v: Int): Triple<Int, Int, Int> {
        val sn = clampInt(s, 0, 255) / 255f
        val vn = clampInt(v, 0, 255) / 255f
        val hh = ((h % 360) + 360) % 360
        val c = vn * sn
        val x = c * (1f - kotlin.math.abs(((hh / 60f) % 2f) - 1f))
        val m = vn - c
        val (r1, g1, b1) = when {
            hh < 60 -> Triple(c, x, 0f)
            hh < 120 -> Triple(x, c, 0f)
            hh < 180 -> Triple(0f, c, x)
            hh < 240 -> Triple(0f, x, c)
            hh < 300 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val r = ((r1 + m) * 255f).toInt()
        val g = ((g1 + m) * 255f).toInt()
        val b = ((b1 + m) * 255f).toInt()
        return Triple(r, g, b)
    }

    private fun applyGammaToHsvForTx(input: HsvColor): HsvColor {
        if (COLOR_TX_GAMMA <= 0.0 || kotlin.math.abs(COLOR_TX_GAMMA - 1.0) < 1e-6) {
            return sanitizeLampHsv(input, input.h)
        }
        val rgb = hsvToRgb(input.h, input.s, input.v)
        val corrected = Triple(
            gammaEncode8bit(rgb.first, COLOR_TX_GAMMA),
            gammaEncode8bit(rgb.second, COLOR_TX_GAMMA),
            gammaEncode8bit(rgb.third, COLOR_TX_GAMMA)
        )
        return sanitizeLampHsv(
            rgbToHsv(corrected.first, corrected.second, corrected.third),
            input.h
        )
    }

    private fun gammaEncode8bit(channel: Int, gamma: Double): Int {
        val normalized = clampInt(channel, 0, 255) / 255.0
        return (normalized.pow(gamma) * 255.0 + 0.5).toInt().coerceIn(0, 255)
    }

    private fun formatParamHint(mode: Int, param: Int): String {
        return when (mode) {
            1 -> {
                val ms = 40f + (param * (320f - 40f) / 255f)
                "${ms.toInt()} ms"
            }
            2 -> {
                val ms = 80f + (param * (2000f - 80f) / 255f)
                "${ms.toInt()} ms"
            }
            3 -> {
                val pct = (param / 255f) * 100f
                "${pct.toInt()} %"
            }
            4 -> {
                val step = 1f + ((255 - param) * 4f) / 255f
                "步进 ${"%.1f".format(step)}"
            }
            else -> param.toString()
        }
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
        } catch (_: SecurityException) {
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
        } catch (_: SecurityException) {
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
                if (!hasPerm(Manifest.permission.BLUETOOTH_CONNECT)) {
                    uiHandler.post { connState.value = "缺少 BLE 权限：请允许“附近设备”" }
                    stopAllTx()
                    safeCloseGatt()
                    return
                }
                uiHandler.post { connState.value = "已连接，发现服务中…" }
                try {
                    g.discoverServices()
                } catch (e: SecurityException) {
                    uiHandler.post { connState.value = "发现服务被拒：${e.message}" }
                    stopAllTx()
                    safeCloseGatt()
                }
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
            if (!hasPerm(Manifest.permission.BLUETOOTH_CONNECT)) {
                uiHandler.post { servicesState.value = "缺少 BLE 权限：请允许“附近设备”" }
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                uiHandler.post { servicesState.value = "服务发现失败：$status" }
                return
            }

            try {
                val svc = g.getService(UUID_FFE0)
                var wc = svc?.getCharacteristic(UUID_FFE1)
                var nc = svc?.getCharacteristic(UUID_FFE2)

                if (wc == null) {
                    for (service in g.services) {
                        val candidate = service.getCharacteristic(UUID_FFE1)
                        if (candidate != null) {
                            wc = candidate
                            if (nc == null) {
                                nc = service.getCharacteristic(UUID_FFE2)
                            }
                            break
                        }
                    }
                }

                if (wc == null) {
                    for (service in g.services) {
                        val candidate = service.characteristics.firstOrNull { ch ->
                            ch.uuid.toString().lowercase().contains("ffe1")
                        }
                        if (candidate != null) {
                            wc = candidate
                            if (nc == null) {
                                nc = service.characteristics.firstOrNull { ch ->
                                    ch.uuid.toString().lowercase().contains("ffe2")
                                }
                            }
                            break
                        }
                    }
                }

                if (wc == null) {
                    for (service in g.services) {
                        val candidate = service.characteristics.firstOrNull { ch ->
                            (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                                (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                        }
                        if (candidate != null) {
                            wc = candidate
                            if (nc == null) {
                                nc = service.characteristics.firstOrNull { ch ->
                                    (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                                }
                            }
                            break
                        }
                    }
                }

                writeChar = wc
                notifyChar = nc

                uiHandler.post {
                    servicesState.value = if (wc == null) "服务已发现（未找到FFE1）" else "服务已发现"
                }

                if (nc != null) {
                    enableNotify(g, nc)
                }
                if (wc != null) {
                    try {
                        g.requestMtu(247)
                    } catch (_: Exception) {
                    }
                }
            } catch (e: SecurityException) {
                uiHandler.post { servicesState.value = "读取服务被拒：${e.message}" }
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedMtu = mtu
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            status: Int
        ) {
            writeInFlight = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                uiHandler.post {
                    txState.value = "TX: 写入失败 $status"
                }
                txQueue.clear()
            } else {
                uiHandler.post { drainTxQueue() }
            }
        }


        @Deprecated("Deprecated in Java")


        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (!awaitingConfigRead || value.isEmpty()) return
            fun postStatus(msg: String) {
                uiHandler.post { configStatusState.value = msg }
            }
            notifyBuffer.write(value)
            val data = notifyBuffer.toByteArray()
            if (data.size < 5) return
            val addr = data[0].toInt() and 0xFF
            val func = data[1].toInt() and 0xFF
            if (addr != MODBUS_ADDR) {
                awaitingConfigRead = false
                notifyBuffer.reset()
                postStatus("读取配置失败：地址不匹配")
                return
            }
            if (func == 0x83) {
                awaitingConfigRead = false
                notifyBuffer.reset()
                val errCode = data.getOrNull(2)?.toInt()?.and(0xFF) ?: 0
                postStatus("读取配置失败：异常码 0x${errCode.toString(16)}")
                return
            }
            if (func != 0x03) return
            val byteCount = data[2].toInt() and 0xFF
            val expectedLen = 3 + byteCount + 2
            if (data.size < expectedLen) return
            val frame = data.copyOf(expectedLen)
            notifyBuffer.reset()
            if (!verifyCrc(frame)) {
                awaitingConfigRead = false
                postStatus("读取配置失败：CRC 错误")
                return
            }
            val regs = byteCount / 2
            if (regs < 4) {
                awaitingConfigRead = false
                postStatus("读取配置失败：长度不足")
                return
            }
            val values = IntArray(regs)
            for (i in 0 until regs) {
                val hi = frame[3 + i * 2].toInt() and 0xFF
                val lo = frame[4 + i * 2].toInt() and 0xFF
                values[i] = (hi shl 8) or lo
            }
            val nextMode = values[0]
            val nextHue = clampInt(values[1], 0, 359)
            val nextSat = clampInt(values[2], 0, 255)
            val nextVal = clampInt(values[3], 0, 255)
            val nextParam = if (values.size >= 5 && nextMode != 5) clampInt(values[4], 0, 255) else lampConfigState.value.param
            awaitingConfigRead = false
            val nextConfig = lampConfigState.value.copy(
                mode = nextMode,
                hue = nextHue,
                sat = nextSat,
                value = nextVal,
                param = nextParam
            )
            uiHandler.post {
                updateLampConfig(nextConfig)
                configStatusState.value = "读取配置成功"
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotify(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        if (!hasPerm(Manifest.permission.BLUETOOTH_CONNECT)) {
            uiHandler.post { servicesState.value = "缺少 BLE 权限：请允许“附近设备”" }
            return
        }
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
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        } finally {
            gatt = null
            writeChar = null
            notifyChar = null
            awaitingConfigRead = false
            notifyBuffer.reset()
        }
    }

    // ---------------- TX 400HZ ----------------

    private val txRunnable = object : Runnable {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun run() {
            if (!txRunning) return
            if (!hasPerm(Manifest.permission.BLUETOOTH_CONNECT)) {
                txState.value = "缺少 BLE 权限：请允许“附近设备”"
                stopAllTx()
                return
            }

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
            txState.value = "TX: 未连接或未找到FFE1"
            return
        }

        txPeriodMs = if (lampConfigState.value.mode == 5) 20.0 else 1000.0 / 400.0
        txSource = source

        val rateHz = (1000.0 / txPeriodMs).toInt()
        if (txRunning) {
            txState.value = "TX: RUN (${rateHz}Hz, ${source.name})"
            return
        }

        txRunning = true
        txState.value = "TX: RUN (${rateHz}Hz, ${source.name})"
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
                    applyFloorToProcessorIfPossible(it, fileFloorDbState.floatValue)
                    it.setRangeDb(fileRangeDbState.floatValue)
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
                        fileDurationMsState.intValue = (durUs / 1000L).toInt()
                    }
                    if (fileSeekingState.value) return@post
                    if (durUs > 0) {
                        val progress = (posUs.toDouble() / durUs.toDouble()).toFloat()
                        fileProgressState.floatValue = progress.coerceIn(0f, 1f)
                        val posMs = (posUs / 1000L).toInt()
                        val durMs = (durUs / 1000L).toInt()
                        fileProgressTextState.value = "${fmtMmSs(posMs)} / ${fmtMmSs(durMs)}"
                    }
                }
            }
        )

        val ok = try {
            engine.start()
        } catch (_: SecurityException) {
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
        fileProgressState.floatValue = 0f
        fileProgressTextState.value = "00:00 / 00:00"
        fileDurationMsState.intValue = 0
    }

    private fun stopAllTx() {
        stopTx400HZ()
        stopMicSpectrum()
        stopFileSpectrum()
        txUiModeState.value = TxUiMode.STOP
        bandChunkOffset = 0
        txQueue.clear()
    }

    // ---------------- Send frame ----------------

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun sendBandsFrame(bands12: IntArray) {
        val maxPayload = (negotiatedMtu - 3).coerceAtLeast(20)
        val payload = buildSpectrumFrame(bands12)
        if (payload.size <= maxPayload) {
            enqueueTxFrames(listOf(payload))
            return
        }

        val maxRegs = ((maxPayload - 9) / 2).coerceAtLeast(1)
        if (maxRegs >= REG_BAND_COUNT) return

        val frames = ArrayList<ByteArray>()
        var idx = bandChunkOffset
        var remaining = REG_BAND_COUNT
        while (remaining > 0) {
            val count = kotlin.math.min(maxRegs, remaining)
            val slice = IntArray(count) { i -> bands12[idx + i].coerceIn(0, 255) }
            frames.add(buildWriteMultipleRegisters(REG_BAND_BASE + idx, slice))
            idx += count
            remaining -= count
        }
        bandChunkOffset = 0
        enqueueTxFrames(frames)
    }

    private fun enqueueTxFrames(frames: List<ByteArray>) {
        if (frames.isEmpty()) return
        txQueue.clear()
        txQueue.addAll(frames)
        drainTxQueue()
    }

    private fun drainTxQueue() {
        if (writeInFlight || txQueue.isEmpty()) return
        val frame = txQueue.removeFirst()
        val ok = writeBlePayload(frame) { msg ->
            txState.value = "TX: $msg"
        }
        if (!ok) {
            txQueue.clear()
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeBlePayload(
        payload: ByteArray,
        onError: (String) -> Unit
    ): Boolean {
        val g = gatt ?: run {
            onError("未连接")
            return false
        }
        val wc = writeChar ?: run {
            onError("未找到FFE1")
            return false
        }
        if (!hasPerm(Manifest.permission.BLUETOOTH_CONNECT)) {
            onError("缺少 BLE 权限：请允许“附近设备”")
            return false
        }
        try {
            val supportsWrite =
                (wc.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
            val supportsWnr =
                (wc.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            val maxPayload = (negotiatedMtu - 3).coerceAtLeast(20)
            val needsLongWrite = payload.size > maxPayload
            if (needsLongWrite && !supportsWrite) {
                onError("MTU 不足且特征不支持长写（$negotiatedMtu）")
                return false
            }
            val writeType =
                if (supportsWrite) {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                } else if (supportsWnr) {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
            if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
                if (writeInFlight) {
                    onError("写入忙")
                    return false
                }
                writeInFlight = true
                val token = ++writeInFlightToken
                uiHandler.postDelayed({
                    if (writeInFlight && writeInFlightToken == token) {
                        writeInFlight = false
                    }
                }, 400)
            }

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
            onError("权限被拒绝")
            return false
        } catch (e: Exception) {
            onError("写入异常 ${e.message}")
            return false
        }
        return true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onDestroy() {
        try { stopScan() } catch (_: Exception) {}
        try { stopAllTx() } catch (_: Exception) {}
        safeCloseGatt()
        super.onDestroy()
    }

    // ---------------- Helpers ----------------

    @SuppressLint("DefaultLocale")
    private fun fmtMmSs(ms: Int): String {
        val s = max(0, ms / 1000)
        val m = s / 60
        val r = s % 60
        return String.format("%02d:%02d", m, r)
    }
}
