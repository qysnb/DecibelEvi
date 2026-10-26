package com.decibelevidence.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun PrivacyNoticeScreen(onAccept: () -> Unit, onDecline: () -> Unit) {
    var fullNotice by remember { mutableStateOf(false) }
    if (fullNotice) AssetTextDialog("隐私与本地数据说明", "PRIVACY.txt", "返回采集说明") { fullNotice = false }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("拍摄前，请了解本地数据使用", style = MaterialTheme.typography.headlineSmall)
            Text("同意并授予权限后，进入拍摄页即开启相机预览和麦克风实时采样；位置权限可不授予。退出拍摄页或进入后台会停止采集。")
            Text("麦克风以 48 kHz 单声道采样，计算 A 计权声级估算值。手机估算不能代替专业声级计。预览不创建已保存记录；只有拍照或录像才保存记录，录像包含声音。")
            Text("照片、录像、测量数据及可选经纬度保存在本机。本应用不联网，不上传数据，也不含广告或统计 SDK。系统定位服务由 Android 管理。")
            Text("主动导出、分享或保存到系统相册会产生副本，由接收应用、接收方或相册管理；删除应用内记录不会删除这些副本。可在应用信息中查看完整说明或撤回同意。")
            Text("不同意也可查看、管理已有本地记录。", style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = { fullNotice = true }) { Text("阅读完整隐私说明") }
        Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) { Text("同意并继续") }
        OutlinedButton(onClick = onDecline, modifier = Modifier.fillMaxWidth()) { Text("暂不同意，查看本地记录") }
    }
}