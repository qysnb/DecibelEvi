package com.decibelevidence.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.decibelevidence.app.data.EvidenceSession
import com.decibelevidence.app.data.MediaArtifact
import com.decibelevidence.app.data.LevelReferenceJson
import com.decibelevidence.app.model.LocationSnapshot
import com.decibelevidence.core.*
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Ink = Color(0xFF19332E)
private val Pine = Color(0xFF12695B)
private val Muted = Color(0xFF64736E)
private val Paper = Color(0xFFF5F6F1)
private val RecordingRed = Color(0xFFAE3D35)
private val Theme = lightColorScheme(primary = Pine, onPrimary = Color(0xFFF5FAF6),
    background = Paper, surface = Color(0xFFFAFBF6), onBackground = Ink, onSurface = Ink,
    secondaryContainer = Color(0xFFE2EAE1), onSecondaryContainer = Ink)

class MainActivity : ComponentActivity() {
    private val vm by viewModels<EvidenceViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = Theme) { EvidenceApp(vm) } }
    }
    override fun onStop() {
        vm.leaveCapturePage("应用进入后台或屏幕锁定")
        super.onStop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EvidenceApp(vm: EvidenceViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val rows by vm.sessions.collectAsStateWithLifecycle()
    var page by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<String?>(null) }
    var showAppInfo by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val consentStore = remember(context) { PrivacyConsentStore(context.getSharedPreferences("privacy_consent", android.content.Context.MODE_PRIVATE)) }
    var consentAccepted by remember { mutableStateOf(consentStore.hasConsent()) }
    if (showAppInfo) ApplicationInfoDialog(consentAccepted, onRevokeConsent = {
        consentStore.revoke(); consentAccepted = false
        vm.leaveCapturePage("用户撤回采集隐私同意")
        page = 1; selected = null; showAppInfo = false
    }) { showAppInfo = false }
    BackHandler(enabled = page == 0 && !consentAccepted && selected == null) { page = 1 }
    val activity = context as ComponentActivity
    BackHandler(enabled = selected != null && ui.session == null && !ui.busy) { selected = null }
    BackHandler(enabled = ui.session != null || ui.calibrating || ui.busy) {
        if (!ui.busy) vm.endSession("用户返回结束取证")
    }
    LaunchedEffect(ui.message) { ui.message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }
    DisposableEffect(ui.session != null || ui.calibrating) {
        if (ui.session != null || ui.calibrating) activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    DisposableEffect(ui.videoStartNs) {
        if (ui.videoStartNs != null) activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        onDispose { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    Scaffold(containerColor = Paper, snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(title = {
                Column {
                    Text(if (selected != null) "取证详情" else listOf("声迹分贝测量", "本地记录")[page], fontWeight = FontWeight.Bold, fontSize = 21.sp)
                    Text(if (ui.session != null) "取证中 · 数据仅保存在本机" else "DECIBELEVI  /  现场噪声记录", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
                }
            }, navigationIcon = { if (selected != null) IconButton(onClick = { selected = null }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回记录") } },
                actions = { IconButton(onClick = { showAppInfo = true }) { Icon(Icons.Outlined.Info, "应用信息") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper))
        }, bottomBar = {
            NavigationBar(containerColor = Color(0xFFEBEFE7)) {
                listOf("拍摄", "记录").forEachIndexed { index, title ->
                    NavigationBarItem(selected = page == index, enabled = (ui.session == null && !ui.calibrating && !ui.busy) || page == index,
                        onClick = { page = index; selected = null }, label = { Text(title) },
                        icon = { Icon(listOf(Icons.Outlined.CameraAlt, Icons.Outlined.FolderOpen)[index], title) })
                }
            }
        }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                selected != null -> DetailScreen(vm, selected!!, onDeleted = { selected = null })
                page == 0 && !consentAccepted -> PrivacyNoticeScreen(
                    onAccept = { consentStore.accept(); consentAccepted = true }, onDecline = { page = 1 })
                page == 0 -> CaptureScreen(vm, ui)
                else -> RecordsScreen(rows) { selected = it }
            }
        }
    }
}

@Composable
private fun CaptureScreen(vm: EvidenceViewModel, ui: CaptureUiState) {
    val gallery by vm.galleryUi.collectAsState()
    val context = LocalContext.current
    var permissionVersion by remember { mutableIntStateOf(0) }
    val allowed = remember(permissionVersion) {
        listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionVersion++ }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, allowed) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) { permissionVersion++; if (allowed) vm.resumeCamera() } }
        lifecycle.addObserver(observer)
        if (allowed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) vm.resumeCamera()
        onDispose { lifecycle.removeObserver(observer); vm.leaveCapturePage() }
    }
    if (!allowed) {
        Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
            Icon(Icons.Outlined.Mic, null, tint = Pine, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(24.dp))
            Text("让声音留下现场记录", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("开启相机和麦克风后，可以拍摄带实时测量水印的照片与有声视频。位置权限用于记录经纬度，可选择不授予。", color = Muted, lineHeight = 24.sp)
            Spacer(Modifier.height(28.dp))
            Button(onClick = { launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text("开启采集权限") }
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("在系统设置中管理权限") }
        }
        return
    }
    if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        LandscapeCapture(vm, ui)
        return
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (ui.session != null) "●  正在取证  ${duration(ui.nowNs - ui.session.startNs)}" else if (ui.mode == CaptureMode.PREVIEW) "实时预览 · 未保存" else "准备就绪", color = if (ui.session != null) Pine else Muted, fontSize = 13.sp)
            Text(ui.levelReference?.unitLabel ?: "估算 dB(A)", color = Muted, fontSize = 12.sp)
        }
        Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF182622), RoundedCornerShape(12.dp))) {
            CameraPreview(vm, Modifier.fillMaxSize())
            if (ui.videoStartNs != null) Text("● REC  ${duration(ui.nowNs - ui.videoStartNs)}", color = Color(0xFFFFD9CE), fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp).background(Color(0xCB5B231E), RoundedCornerShape(6.dp)).padding(8.dp))
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("实时", levelText(ui.measurement?.currentDb, ui.levelReference ?: LevelReference.digital()), "Fast 125ms")
            Stat("平均", levelText(ui.measurement?.averageDb, ui.levelReference ?: LevelReference.digital()), if (ui.session == null) "近60秒声能平均" else "本次声能平均")
            Stat("最大", levelText(ui.measurement?.maximumDb, ui.levelReference ?: LevelReference.digital()), if (ui.session == null) "近60秒 Fast 最大" else "本次 Fast 最大")
        }
        Spacer(Modifier.height(8.dp))
        Text(locationText(ui.location, ui.nowNs), fontSize = 11.sp, color = Muted, maxLines = 2)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { vm.photo() }, enabled = !ui.busy && !gallery.running,
                modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(10.dp)) {
                Icon(Icons.Outlined.CameraAlt, null); Spacer(Modifier.width(8.dp)); Text("拍照", fontSize = 16.sp)
            }
            Button(onClick = { vm.toggleVideo() }, enabled = !ui.busy && !gallery.running,
                modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (ui.videoStartNs != null) RecordingRed else Pine)) {
                Icon(if (ui.videoStartNs != null) Icons.Outlined.Stop else Icons.Outlined.Videocam, null)
                Spacer(Modifier.width(8.dp)); Text(if (ui.videoStartNs != null) "停止录像" else "录像", fontSize = 16.sp)
            }
        }
        Text(if (ui.busy) "正在处理，请稍候" else if (gallery.running) "正在保存相册，请完成后再拍摄" else "每张照片、每段录像独立保存；照片 AVG/MAX 取最近60秒。", fontSize = 10.sp, color = Muted, modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
    }
}

@Composable
private fun LandscapeCapture(vm: EvidenceViewModel, ui: CaptureUiState) {
    val gallery by vm.galleryUi.collectAsState()
    Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CameraPreview(vm, Modifier.weight(1f).fillMaxHeight().background(Color(0xFF182622)))
        Column(Modifier.width(260.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
            Text(if (ui.session != null) "● 取证中  ${duration(ui.nowNs - ui.session.startNs)}" else "实时预览 · 未保存", color = Pine, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            Text("实时 ${levelText(ui.measurement?.currentDb, ui.levelReference ?: LevelReference.digital())}  ${ui.levelReference?.unitLabel ?: "估算 dB(A)"}", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("平均 ${levelText(ui.measurement?.averageDb, ui.levelReference ?: LevelReference.digital())} · 最大 ${levelText(ui.measurement?.maximumDb, ui.levelReference ?: LevelReference.digital())}", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.photo() }, enabled = !ui.busy && !gallery.running, modifier = Modifier.weight(1f)) { Text("拍照") }
                Button(onClick = { vm.toggleVideo() }, enabled = !ui.busy && !gallery.running, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = if (ui.videoStartNs != null) RecordingRed else Pine)) { Text(if (ui.videoStartNs == null) "录像" else "停止录像") }
            }
            Text(if (gallery.running) "正在保存相册…" else "每张照片、每段录像独立保存", color = Muted, fontSize = 11.sp)
            if (ui.videoStartNs != null) Text("REC ${duration(ui.nowNs - ui.videoStartNs)}", color = RecordingRed, fontSize = 12.sp)
            Text(locationText(ui.location, ui.nowNs), color = Muted, fontSize = 11.sp, maxLines = 2)
        }
    }
}

@Composable
private fun CameraPreview(vm: EvidenceViewModel, modifier: Modifier) {
    AndroidView(factory = { context ->
        SurfaceView(context).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {}
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    vm.attachPreview(holder.surface, width, height, display?.rotation ?: 0)
                }
                override fun surfaceDestroyed(holder: SurfaceHolder) { vm.detachPreview() }
            })
        }
    }, modifier = modifier)
}

@Composable
private fun Stat(label: String, value: String, hint: String) {
    Column {
        Text(label, color = Muted, fontSize = 11.sp)
        Text(value, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Text(hint, color = Muted, fontSize = 9.sp)
    }
}

@Composable
private fun RecordsScreen(rows: List<EvidenceSession>, select: (String) -> Unit) {
    if (rows.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
            Icon(Icons.Outlined.FolderOpen, null, Modifier.size(40.dp), tint = Pine)
            Spacer(Modifier.height(20.dp)); Text("还没有取证记录", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp)); Text("完成一次现场取证后，照片、录像与测量数据会保存在这里。", color = Muted, lineHeight = 24.sp)
        }
    } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp)) {
        item { Text("${rows.size} 次本地取证", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 16.dp)) }
        items(rows, key = { it.id }) { row ->
            Column(Modifier.fillMaxWidth().clickable { select(row.id) }.padding(vertical = 18.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(row.displayName ?: date(row.startedAtMs), fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f).padding(end = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(row.status, color = if (row.status == "完成") Pine else RecordingRed, fontSize = 12.sp)
                }
                Spacer(Modifier.height(10.dp))
                Text("平均 ${levelText(row.averageDb, recordReference(row))}  ·  最大 ${levelText(row.maximumDb, recordReference(row))}  ${recordReference(row).unitLabel}", fontSize = 13.sp)
                Spacer(Modifier.height(6.dp)); Text("${duration(row.durationMs * 1_000_000)}  /  ${row.id.takeLast(6).uppercase()}", color = Muted, fontSize = 11.sp)
            }
            HorizontalDivider(color = Color(0xFFDCE3D9))
        }
    }
}

@Composable
private fun DetailScreen(vm: EvidenceViewModel, id: String, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var row by remember(id) { mutableStateOf<EvidenceSession?>(null) }
    var media by remember(id) { mutableStateOf(emptyList<MediaArtifact>()) }
    var exporting by remember { mutableStateOf(false) }
    val gallery by vm.galleryUi.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember(id) { mutableStateOf(false) }
    var nameText by remember(id) { mutableStateOf("") }
    var renameBusy by remember(id) { mutableStateOf(false) }
    var previewMedia by remember(id) { mutableStateOf<MediaArtifact?>(null) }
    LaunchedEffect(id, gallery.running) { row = vm.repository.dao.session(id); media = vm.repository.dao.media(id) }
    val value = row ?: return
    previewMedia?.let { MediaViewer(it) { previewMedia = null } }
    if (renaming) AlertDialog(onDismissRequest = { if (!renameBusy) renaming = false },
        title = { Text("重命名记录") },
        text = { Column {
            OutlinedTextField(nameText, { if (it.length <= 80) nameText = it }, singleLine = true,
                label = { Text("记录名称") }, enabled = !renameBusy,
                trailingIcon = { if (nameText.isNotEmpty()) IconButton(onClick = { nameText = "" }, enabled = !renameBusy) { Icon(Icons.Outlined.Clear, "清空名称") } })
            Text("清空名称并保存，即恢复日期时间命名。", color = Muted, fontSize = 12.sp)
        } },
        confirmButton = { TextButton(enabled = !renameBusy, onClick = {
            renameBusy = true
            scope.launch {
                try { vm.repository.rename(id, nameText); row = vm.repository.dao.session(id); renaming = false }
                catch (error: Exception) { vm.show("重命名失败：${error.message}") }
                finally { renameBusy = false }
            }
        }) { Text(if (renameBusy) "正在保存…" else "保存") } },
        dismissButton = { TextButton(enabled = !renameBusy, onClick = { renaming = false }) { Text("取消") } })
    fun share(file: File, mime: String, view: Boolean = false) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(if (view) Intent.ACTION_VIEW else Intent.ACTION_SEND).apply {
            if (view) setDataAndType(uri, mime) else { type = mime; putExtra(Intent.EXTRA_STREAM, uri) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri(file.name, uri)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, if (view) "查看取证媒体" else "分享取证资料")) }
            .onFailure { vm.show("未找到可打开此文件的应用") }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value.displayName ?: date(value.startedAtMs), fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(enabled = !gallery.running && !exporting && value.status != "进行中", onClick = { nameText = value.displayName.orEmpty(); renaming = true }) {
                    Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp)); Text("重命名")
                }
            }
            if (value.displayName != null) Text(date(value.startedAtMs), color = Muted, fontSize = 12.sp)
            Text("${value.status} · ${duration(value.durationMs * 1_000_000)} · ${referenceDescription(recordReference(value))}", color = Muted, modifier = Modifier.padding(top = 8.dp))
        }
        item {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("声能平均", levelText(value.averageDb, recordReference(value)), recordReference(value).unitLabel)
                Stat("Fast 最大", levelText(value.maximumDb, recordReference(value)), "有效采样时段")
            }
            Text("记录编号  ${value.id}", fontSize = 11.sp, color = Muted)
        }
        item { Text("现场媒体  ${media.count { it.status == "完成" }}", fontWeight = FontWeight.SemiBold) }
        if (media.isEmpty()) item { Text("本次仅记录了测量数据，没有拍摄媒体。", color = Muted) }
        items(media, key = { it.id }) { item ->
            val file = File(item.path)
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (item.kind == "photo") Icons.Outlined.Image else Icons.Outlined.Videocam, null, tint = Pine)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (item.kind == "photo") "水印照片" else "水印录像", fontWeight = FontWeight.Medium)
                        Text("${date(item.startedAtMs)} · ${item.status}", fontSize = 11.sp, color = Muted)
                    }
                    if (item.status == "完成") {
                        TextButton(onClick = { previewMedia = item }) { Text("查看") }
                        IconButton(onClick = { share(file, if (item.kind == "photo") "image/jpeg" else "video/mp4") }) { Icon(Icons.Outlined.Share, "分享媒体", Modifier.size(20.dp)) }
                    }
                }
                if (item.status == "完成") TextButton(
                    onClick = { vm.saveGallery(id, item.id) }, enabled = !gallery.running && !exporting && value.status != "进行中",
                    modifier = Modifier.align(Alignment.End)) {
                    Icon(Icons.Outlined.SaveAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(if (item.galleryUri == null) "保存到相册" else "已存相册 · 检查或重存")
                }
                HorizontalDivider(Modifier.padding(top = 8.dp))
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
            Button(onClick = { vm.saveGallery(id) },
                enabled = !gallery.running && !exporting && value.status != "进行中" && media.any { it.status == "完成" },
                modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(10.dp)) {
                Icon(Icons.Outlined.SaveAlt, null); Spacer(Modifier.width(8.dp))
                Text(if (gallery.running && gallery.sessionId == id) "正在保存 ${gallery.completed}/${gallery.total}" else "全部保存到相册")
            }
            Text("视频与照片原样保存到系统相册，不需要解压。", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(vertical = 8.dp))
            OutlinedButton(onClick = {
                exporting = true
                scope.launch {
                    try { share(vm.repository.export(id), "application/zip") }
                    catch (error: Exception) { vm.show("导出失败：${error.message}") }
                    finally { exporting = false }
                }
            }, enabled = !exporting && !gallery.running && value.status != "进行中", modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(10.dp)) {
                Icon(Icons.Outlined.FileDownload, null); Spacer(Modifier.width(8.dp)); Text(if (exporting) "正在打包与校验…" else "导出完整取证包")
            }
            Text("包含水印媒体、逐秒CSV、时间位置与校准资料，以及SHA-256校验清单。", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(vertical = 12.dp))
            if (!confirmDelete) TextButton(onClick = { confirmDelete = true }, enabled = !exporting && !gallery.running && value.status != "进行中") { Text("删除这次取证", color = RecordingRed) }
            else Column(Modifier.background(Color(0xFFF7E9E2), RoundedCornerShape(8.dp)).padding(14.dp)) {
                Text("将删除应用内本次测量和媒体；已经保存到系统相册的副本会保留。", color = Ink, fontSize = 13.sp)
                Row {
                    TextButton(onClick = { confirmDelete = false }) { Text("保留") }
                    TextButton(enabled = !gallery.running && !exporting, onClick = { scope.launch { runCatching { vm.repository.delete(id) }.onSuccess { onDeleted() }.onFailure { vm.show("删除失败：${it.message}") } } }) { Text("确认删除", color = RecordingRed) }
                }
            }
        }
    }
}

private fun recordReference(row: EvidenceSession): LevelReference = LevelReferenceJson.decode(row.levelReferenceJson)
    ?: if (row.calibrated) LevelReference(LevelReferenceMode.USER_REFERENCE) else LevelReference.digital()
private fun referenceDescription(reference: LevelReference) = when (reference.mode) {
    LevelReferenceMode.DIGITAL -> "历史数字电平 dBFS"
    LevelReferenceMode.ANDROID_NOMINAL -> "Android 标称估算"
    LevelReferenceMode.USER_REFERENCE -> "参考设备校准"
}
private fun locationText(location: LocationSnapshot?, nowNs: Long): String {
    if (location?.hasCoordinates != true) return "未定位 · 经纬度不可用"
    return "${location.status(nowNs)} · " + String.format(Locale.US, "%.6f, %.6f", location.latitude, location.longitude) +
        (location.accuracyMeters?.let { " · ±${it.toInt()}m" } ?: "")
}

private fun number(value: Double?) = value?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
private fun date(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(ms))
private fun duration(ns: Long): String {
    val seconds = (ns.coerceAtLeast(0) / 1_000_000_000)
    return String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}
