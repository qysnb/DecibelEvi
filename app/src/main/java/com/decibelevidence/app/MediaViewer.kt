package com.decibelevidence.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.decibelevidence.app.data.MediaArtifact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** A local, read-only preview; sharing and the original evidence files are unaffected. */
@Composable
fun MediaViewer(media: MediaArtifact, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false
        )
    ) {
        Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (media.kind == "photo") "水印照片" else "水印录像", color = Color.White,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭预览", tint = Color.White)
                }
            }
            key(media.id, media.path, media.kind) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    when (media.kind) {
                        "photo" -> PhotoPreview(media.path)
                        "video" -> VideoPreview(media.path)
                        else -> PreviewError("暂不支持此媒体类型")
                    }
                }
            }
        }
    }
}

private data class PhotoPreviewState(val bitmap: Bitmap? = null, val error: String? = null)

@Composable
private fun PhotoPreview(path: String) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current.density
    // Bound memory even on very large displays; decoding never runs on the UI thread.
    val maxEdge = (maxOf(configuration.screenWidthDp, configuration.screenHeightDp) * density)
        .toInt().coerceIn(1, 2048)
    val preview by produceState(PhotoPreviewState(), path, maxEdge) {
        value = PhotoPreviewState()
        var unpublished: Bitmap? = null
        try {
            withContext(Dispatchers.IO) {
                val file = File(path)
                check(file.isFile && file.length() > 0L) { "照片文件不存在或为空" }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                check(bounds.outWidth > 0 && bounds.outHeight > 0) { "照片无法解码" }
                var sample = 1
                while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) {
                    sample *= 2
                }
                ensureActive()
                unpublished = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }) ?: error("照片无法解码")
            }
            ensureActive()
            value = PhotoPreviewState(bitmap = unpublished)
            // Published bitmaps belong to Compose/the render thread. Let GC reclaim them:
            // recycling on composition disposal can invalidate a still-submitted frame.
            unpublished = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OutOfMemoryError) {
            value = PhotoPreviewState(error = "内存不足，暂时无法加载照片")
        } catch (_: Exception) {
            value = PhotoPreviewState(error = "无法读取照片，文件可能已被删除或损坏")
        } finally {
            // Cancellation during blocking decode must not leak an unclaimed bitmap.
            unpublished?.recycle()
        }
    }
    val bitmap = preview.bitmap
    when {
        bitmap != null -> Image(bitmap.asImageBitmap(), "水印取证照片",
            Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        preview.error != null -> PreviewError(preview.error!!)
        else -> CircularProgressIndicator(color = Color.White)
    }
}

@Composable
private fun VideoPreview(path: String) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val videoView = remember(context, path) { VideoView(context) }
    val controller = remember(videoView) { MediaController(context) }
    var loading by remember(path) { mutableStateOf(true) }
    var error by remember(path) { mutableStateOf<String?>(null) }

    DisposableEffect(videoView, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                videoView.pause()
                controller.hide()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            controller.hide()
            videoView.setOnPreparedListener(null)
            videoView.setOnErrorListener(null)
            videoView.setOnCompletionListener(null)
            videoView.stopPlayback()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            FrameLayout(context).apply {
                addView(videoView, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                ))
                videoView.setMediaController(controller)
                controller.setAnchorView(videoView)
                videoView.setOnPreparedListener {
                    loading = false
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        videoView.start()
                        controller.show()
                    }
                }
                videoView.setOnCompletionListener { controller.show(0) }
                videoView.setOnErrorListener { _, _, _ ->
                    loading = false
                    error = "无法播放录像，文件可能已损坏或设备不支持此格式"
                    controller.hide()
                    true // Suppress the platform's separate error dialog.
                }
                try {
                    val file = File(path)
                    check(file.isFile && file.length() > 0L)
                    videoView.setVideoURI(FileProvider.getUriForFile(context,
                        "${context.packageName}.files", file))
                } catch (_: Exception) {
                    loading = false
                    error = "无法读取录像，文件可能已被删除或损坏"
                }
            }
        }
    )
    if (loading) CircularProgressIndicator(color = Color.White)
    error?.let { PreviewError(it) }
}

@Composable
private fun PreviewError(message: String) {
    Text(message, color = Color.White, modifier = Modifier
        .background(Color.Black.copy(alpha = 0.85f)).padding(24.dp))
}
