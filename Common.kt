package com.muraltracer.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max

/** State shared by AR and Simple mode so switching keeps your image/settings. */
class UiState {
    var uri by mutableStateOf<Uri?>(null)
    var opacity by mutableFloatStateOf(0.6f)
    var outline by mutableStateOf(false)
    var threshold by mutableFloatStateOf(60f)
    var grid by mutableStateOf(false)
    var flipped by mutableStateOf(false)
    var locked by mutableStateOf(false)
}

object Images {
    private const val MAX = 2048

    fun load(context: Context, uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val m = max(info.size.width, info.size.height)
                if (m > MAX) {
                    val f = MAX.toFloat() / m
                    decoder.setTargetSize(
                        max(1, (info.size.width * f).toInt()),
                        max(1, (info.size.height * f).toInt())
                    )
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > MAX) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        }
    } catch (e: Exception) {
        null
    }
}

/** Loads the picked image and (optionally) converts it to edge outline. */
@Composable
fun rememberOverlayBitmap(uri: Uri?, outline: Boolean, threshold: Int): Bitmap? {
    val ctx = LocalContext.current
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var result by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(uri) {
        source = if (uri == null) null
        else withContext(Dispatchers.IO) { Images.load(ctx, uri) }
    }
    LaunchedEffect(source, outline, threshold) {
        val s = source
        if (s == null) {
            result = null
        } else if (outline) {
            delay(150) // debounce slider
            result = withContext(Dispatchers.Default) { Outline.fromBitmap(s, threshold) }
        } else {
            result = s
        }
    }
    return result
}

@Composable
fun GridOverlay() {
    Canvas(Modifier.fillMaxSize()) {
        val cell = size.width / 4f
        val c = Color.White.copy(alpha = 0.6f)
        var x = cell
        while (x < size.width) {
            drawLine(c, Offset(x, 0f), Offset(x, size.height), 2f); x += cell
        }
        var y = cell
        while (y < size.height) {
            drawLine(c, Offset(0f, y), Offset(size.width, y), 2f); y += cell
        }
    }
}

@Composable
fun BoxScope.Controls(ui: UiState, extra: @Composable RowScope.() -> Unit = {}) {
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { if (it != null) ui.uri = it }

    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.6f))
            .navigationBarsPadding()
            .padding(12.dp)
    ) {
        Text("Opacity: ${(ui.opacity * 100).toInt()}%", color = Color.White)
        Slider(value = ui.opacity, onValueChange = { ui.opacity = it })

        if (ui.outline) {
            Text("Edge detail", color = Color.White)
            Slider(
                value = 170f - ui.threshold,
                onValueChange = { ui.threshold = 170f - it },
                valueRange = 20f..150f
            )
        }

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("Image") }
            FilterChip(selected = ui.outline, onClick = { ui.outline = !ui.outline }, label = { Text("Outline") })
            FilterChip(selected = ui.locked, onClick = { ui.locked = !ui.locked }, label = { Text("Lock") })
            FilterChip(selected = ui.grid, onClick = { ui.grid = !ui.grid }, label = { Text("Grid") })
            FilterChip(selected = ui.flipped, onClick = { ui.flipped = !ui.flipped }, label = { Text("Flip") })
            extra()
        }
    }
}
