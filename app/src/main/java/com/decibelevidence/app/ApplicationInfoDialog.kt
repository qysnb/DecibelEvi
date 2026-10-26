package com.decibelevidence.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ApplicationInfoDialog(consentAccepted: Boolean, onRevokeConsent: () -> Unit, onDismiss: () -> Unit) {
    var document by remember { mutableStateOf<Pair<String, String>?>(null) }
    if (document != null) {
        AssetTextDialog(document!!.first, document!!.second, "返回应用信息") { document = null }
    } else AlertDialog(onDismissRequest = onDismiss,
        title = { Text("声迹分贝测量") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("版本 ${BuildConfig.VERSION_NAME}")
            Text("48 kHz单声道采样 · A频率计权 · Fast 125 ms", fontSize = 13.sp)
            Text("声级为手机估算值，用于噪声测量与现场记录辅助。照片和录像保存在本机；主动分享或保存相册后，由接收方或系统相册管理。", fontSize = 13.sp)
            Text("公开开发者：qysnb", fontSize = 13.sp)
            TextButton(onClick = { document = "本项目许可 · MIT" to "PROJECT_LICENSE.txt" }) { Text("本项目许可 · MIT") }
            TextButton(onClick = { document = "隐私与本地数据说明" to "PRIVACY.txt" }) { Text("隐私与本地数据说明") }
            TextButton(onClick = { document = "第三方开源许可" to "THIRD_PARTY_NOTICES.txt" }) { Text("第三方开源许可") }
            if (consentAccepted) TextButton(onClick = onRevokeConsent) { Text("撤回采集隐私同意并停止采集") }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

@Composable
internal fun AssetTextDialog(title: String, asset: String, backLabel: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val paragraphs by produceState<List<String>?>(null, asset) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    .split("\n\n").filter { it.isNotBlank() }
            }.getOrElse { listOf("文件暂时无法读取，请重新打开应用后重试。") }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.92f).padding(12.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                val content = paragraphs
                if (content == null) CircularProgressIndicator()
                else SelectionContainer(Modifier.weight(1f)) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(content) { paragraph -> Text(paragraph, fontSize = 12.sp, lineHeight = 18.sp) }
                    }
                }
                TextButton(onClick = onDismiss) { Text(backLabel) }
            }
        }
    }
}
