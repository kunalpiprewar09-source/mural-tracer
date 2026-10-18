package com.muraltracer.app

import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

/** Fallback mode: camera + 2D overlay (works on every phone, no ARCore). */
@Composable
fun SimpleTracerScreen(ui: UiState, onAr: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val bitmap = rememberOverlayBitmap(ui.uri, ui.outline, ui.threshold.toInt())

    var scale by remember { mutableFloatStateOf(1f) }
    var rotation by remember { mutableFloatStateOf(0f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var torch by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    LaunchedEffect(torch, camera) { camera?.cameraControl?.enableTorch(torch) }

    // Release the camera so ARCore can use it when switching modes
    DisposableEffect(Unit) {
        onDispose {
            try {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            } catch (_: Exception) {
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    val provider = future.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    provider.unbindAll()
                    camera = provider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview
                    )
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            }
        )

        if (bitmap != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(ui.locked) {
                        if (!ui.locked) {
                            detectTransformGestures { _, pan, zoom, rot ->
                                scale = (scale * zoom).coerceIn(0.1f, 10f)
                                rotation += rot
                                offset += pan
                            }
                        }
                    }
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale * (if (ui.flipped) -1f else 1f)
                            scaleY = scale
                            rotationZ = rotation
                            translationX = offset.x
                            translationY = offset.y
                            alpha = ui.opacity
                        }
                )
            }
        }

        if (ui.grid) GridOverlay()

        Controls(ui) {
            FilterChip(selected = torch, onClick = { torch = !torch }, label = { Text("Torch") })
            OutlinedButton(onClick = {
                scale = 1f; rotation = 0f; offset = Offset.Zero
            }) { Text("Reset") }
            OutlinedButton(onClick = onAr) { Text("AR mode") }
        }
    }
}
