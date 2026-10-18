package com.muraltracer.app

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import kotlinx.coroutines.delay
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// ---------------------------------------------------------------- state

/** Thread-safe bridge between Compose (UI thread) and the GL renderer thread. */
class ArState {
    @Volatile var pendingBitmap: Bitmap? = null
    @Volatile var opacity = 0.6f
    @Volatile var flipped = false
    @Volatile var locked = false
    @Volatile var sizeMeters = 1.0f
    @Volatile var angleRad = 0f
    @Volatile var offsetRight = 0f
    @Volatile var offsetUp = 0f
    @Volatile var metersPerPixel = 0.002f
    @Volatile var status = ""
    @Volatile var placed = false
    @Volatile var clearRequested = false
    val taps = ConcurrentLinkedQueue<FloatArray>()

    @Synchronized
    fun takePendingBitmap(): Bitmap? {
        val b = pendingBitmap
        pendingBitmap = null
        return b
    }

    fun applyGesture(dx: Float, dy: Float, zoom: Float, rotDeg: Float) {
        sizeMeters = (sizeMeters * zoom).coerceIn(0.05f, 30f)
        angleRad -= Math.toRadians(rotDeg.toDouble()).toFloat()
        offsetRight += dx * metersPerPixel
        offsetUp -= dy * metersPerPixel
    }
}

// ---------------------------------------------------------------- math

private object V {
    fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    fun len(a: FloatArray) = sqrt(dot(a, a))
    fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]
    )
    fun scale(a: FloatArray, s: Float) = floatArrayOf(a[0] * s, a[1] * s, a[2] * s)
    fun add(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])
    fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
    fun norm(a: FloatArray): FloatArray {
        val l = len(a)
        return if (l < 1e-6f) a else scale(a, 1f / l)
    }
}

// ---------------------------------------------------------------- GL helpers

private object Gl {
    fun program(vs: String, fs: String): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        return p
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }

    fun floatBuffer(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(a); position(0)
        }
}

/** Draws the ARCore camera feed. */
private class BackgroundRenderer {
    var textureId = 0
    private var program = 0
    private var posLoc = 0
    private var texLoc = 0
    private var samplerLoc = 0
    private val quadCoords = Gl.floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val texCoords = Gl.floatBuffer(FloatArray(8))

    fun create() {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        textureId = t[0]
        val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(target, textureId)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        program = Gl.program(VS, FS)
        posLoc = GLES20.glGetAttribLocation(program, "a_Position")
        texLoc = GLES20.glGetAttribLocation(program, "a_TexCoord")
        samplerLoc = GLES20.glGetUniformLocation(program, "sTexture")
    }

    fun draw(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) {
            quadCoords.position(0)
            texCoords.position(0)
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quadCoords,
                Coordinates2d.TEXTURE_NORMALIZED, texCoords
            )
        }
        if (frame.timestamp == 0L) return
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUseProgram(program)
        GLES20.glUniform1i(samplerLoc, 0)
        quadCoords.position(0)
        texCoords.position(0)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, quadCoords)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glEnableVertexAttribArray(texLoc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(texLoc)
        GLES20.glDepthMask(true)
    }

    companion object {
        const val VS = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() { gl_Position = a_Position; v_TexCoord = a_TexCoord; }
        """
        const val FS = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES sTexture;
            void main() { gl_FragColor = texture2D(sTexture, v_TexCoord); }
        """
    }
}

/** Draws the artwork as a textured quad in world space. */
private class QuadRenderer {
    private var program = 0
    private var mvpLoc = 0
    private var alphaLoc = 0
    private var samplerLoc = 0
    private var posLoc = 0
    private var texLoc = 0
    private var textureId = 0
    private val pos = Gl.floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val tex = Gl.floatBuffer(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))

    fun create() {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        textureId = t[0]
        program = Gl.program(VS, FS)
        mvpLoc = GLES20.glGetUniformLocation(program, "u_Mvp")
        alphaLoc = GLES20.glGetUniformLocation(program, "u_Alpha")
        samplerLoc = GLES20.glGetUniformLocation(program, "u_Texture")
        posLoc = GLES20.glGetAttribLocation(program, "a_Position")
        texLoc = GLES20.glGetAttribLocation(program, "a_Tex")
    }

    fun upload(bmp: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
    }

    fun draw(mvp: FloatArray, alpha: Float) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA) // premultiplied alpha
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(samplerLoc, 0)
        GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvp, 0)
        GLES20.glUniform1f(alphaLoc, alpha)
        pos.position(0)
        tex.position(0)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, pos)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 0, tex)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glEnableVertexAttribArray(texLoc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(texLoc)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    companion object {
        const val VS = """
            uniform mat4 u_Mvp;
            attribute vec4 a_Position;
            attribute vec2 a_Tex;
            varying vec2 v_Tex;
            void main() { gl_Position = u_Mvp * a_Position; v_Tex = a_Tex; }
        """
        const val FS = """
            precision mediump float;
            uniform sampler2D u_Texture;
            uniform float u_Alpha;
            varying vec2 v_Tex;
            void main() { gl_FragColor = texture2D(u_Texture, v_Tex) * u_Alpha; }
        """
    }
}

// ---------------------------------------------------------------- renderer

class ArRenderer(private val context: Context, private val st: ArState) : GLSurfaceView.Renderer {
    @Volatile var session: Session? = null

    private val bg = BackgroundRenderer()
    private val quad = QuadRenderer()
    private var viewW = 0
    private var viewH = 0
    private var geometryDirty = true
    private var hasTexture = false
    private var aspect = 1f

    private var anchor: Anchor? = null
    private var lRight = floatArrayOf(1f, 0f, 0f)
    private var lUp = floatArrayOf(0f, 1f, 0f)
    private var lNormal = floatArrayOf(0f, 0f, 1f)

    private val viewM = FloatArray(16)
    private val projM = FloatArray(16)
    private val model = FloatArray(16)
    private val vp = FloatArray(16)
    private val mvp = FloatArray(16)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        bg.create()
        quad.create()
        hasTexture = false
        geometryDirty = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewW = width
        viewH = height
        geometryDirty = true
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int =
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (viewW == 0) return
        if (geometryDirty) {
            s.setDisplayGeometry(displayRotation(), viewW, viewH)
            geometryDirty = false
        }
        s.setCameraTextureName(bg.textureId)

        val frame = try {
            s.update()
        } catch (e: Exception) {
            return // session paused / camera unavailable
        }
        val camera = frame.camera
        bg.draw(frame)

        st.takePendingBitmap()?.let {
            quad.upload(it)
            aspect = it.width.toFloat() / it.height
            hasTexture = true
        }
        if (st.clearRequested) {
            anchor?.detach(); anchor = null
            st.placed = false
            st.clearRequested = false
        }

        val tap = st.taps.poll()
        st.taps.clear()
        if (tap != null && !st.locked && hasTexture && camera.trackingState == TrackingState.TRACKING) {
            place(s, frame, tap[0], tap[1])
        }

        // status text for the UI
        if (camera.trackingState != TrackingState.TRACKING) {
            st.status = "Phone ko dheere dheere hilao..."
        } else {
            val planes = s.getAllTrackables(Plane::class.java)
                .count { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }
            st.status = when {
                !hasTexture -> "Pehle 'Image' dabake artwork chuno"
                st.placed && st.locked -> "Locked"
                st.placed -> "Drag = move, pinch = size, twist = rotate. Tap = nayi jagah"
                planes > 0 -> "Wall mil gayi. Tap karke image lagao"
                else -> "Wall dhundh raha hai... (ya tap karke hawa me lagao)"
            }
        }

        val a = anchor
        if (!hasTexture || a == null || a.trackingState != TrackingState.TRACKING) return
        if (camera.trackingState != TrackingState.TRACKING) return

        camera.getViewMatrix(viewM, 0)
        camera.getProjectionMatrix(projM, 0, 0.05f, 100f)

        val pose = a.pose
        val r = pose.rotateVector(lRight)
        val u = pose.rotateVector(lUp)
        val n = pose.rotateVector(lNormal)
        val t = pose.translation
        val pos = V.add(t, V.add(V.scale(r, st.offsetRight), V.scale(u, st.offsetUp)))

        val c = cos(st.angleRad)
        val sn = sin(st.angleRad)
        val rx = V.add(V.scale(r, c), V.scale(u, sn))
        val uy = V.add(V.scale(r, -sn), V.scale(u, c))

        val size = st.sizeMeters
        var hw = (if (aspect >= 1f) size else size * aspect) / 2f
        val hh = (if (aspect >= 1f) size / aspect else size) / 2f
        if (st.flipped) hw = -hw

        model[0] = rx[0] * hw; model[1] = rx[1] * hw; model[2] = rx[2] * hw; model[3] = 0f
        model[4] = uy[0] * hh; model[5] = uy[1] * hh; model[6] = uy[2] * hh; model[7] = 0f
        model[8] = n[0]; model[9] = n[1]; model[10] = n[2]; model[11] = 0f
        model[12] = pos[0]; model[13] = pos[1]; model[14] = pos[2]; model[15] = 1f

        Matrix.multiplyMM(vp, 0, projM, 0, viewM, 0)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        quad.draw(mvp, st.opacity)

        // for drag gestures: how many meters is one screen pixel at the image's distance?
        val dist = V.len(V.sub(pos, camera.pose.translation))
        st.metersPerPixel = dist * 2f / (projM[0] * viewW)
    }

    /** Anchor the artwork on the tapped wall (or in mid-air if no plane is hit). */
    private fun place(s: Session, frame: Frame, x: Float, y: Float) {
        try {
            val camera = frame.camera
            val hits = frame.hitTest(x, y).filter {
                val t = it.trackable
                t is Plane && t.isPoseInPolygon(it.hitPose)
            }
            val hit = hits.firstOrNull { (it.trackable as Plane).type == Plane.Type.VERTICAL }
                ?: hits.firstOrNull()

            val newAnchor: Anchor
            if (hit != null) {
                val pose = hit.hitPose
                val camPos = camera.pose.translation
                val nW = pose.getTransformedAxis(1, 1f) // plane normal
                val toViewer = V.sub(camPos, pose.translation)
                val nWorld = if (V.dot(nW, toViewer) < 0f) V.scale(nW, -1f) else nW

                // "up" = world up projected onto the plane (keeps the art upright on walls)
                var up = V.sub(floatArrayOf(0f, 1f, 0f), V.scale(nWorld, nWorld[1]))
                if (V.len(up) < 0.2f) {
                    val camUp = camera.displayOrientedPose.getTransformedAxis(1, 1f)
                    up = V.sub(camUp, V.scale(nWorld, V.dot(camUp, nWorld)))
                }
                up = V.norm(up)

                val inv = pose.inverse()
                lUp = inv.rotateVector(up)
                lNormal = inv.rotateVector(nWorld)
                lRight = V.norm(V.cross(lUp, lNormal))
                newAnchor = hit.createAnchor()
            } else {
                val p = camera.displayOrientedPose.compose(Pose.makeTranslation(0f, 0f, -1.2f))
                lRight = floatArrayOf(1f, 0f, 0f)
                lUp = floatArrayOf(0f, 1f, 0f)
                lNormal = floatArrayOf(0f, 0f, 1f)
                newAnchor = s.createAnchor(p)
            }
            anchor?.detach()
            anchor = newAnchor
            st.offsetRight = 0f
            st.offsetUp = 0f
            st.angleRad = 0f
            st.placed = true
        } catch (e: Exception) {
            // tracking lost during tap - ignore
        }
    }
}

// ---------------------------------------------------------------- screen

@Composable
fun ArTracerScreen(ui: UiState, onUnsupported: () -> Unit, onSimple: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val lifecycleOwner = LocalLifecycleOwner.current

    val st = remember { ArState() }
    val renderer = remember { ArRenderer(context, st) }
    val glHolder = remember { arrayOfNulls<GLSurfaceView>(1) }
    val installRequested = remember { booleanArrayOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }

    val bitmap = rememberOverlayBitmap(ui.uri, ui.outline, ui.threshold.toInt())

    LaunchedEffect(Unit) {
        var a = ArCoreApk.getInstance().checkAvailability(context)
        while (a.isTransient) {
            delay(200)
            a = ArCoreApk.getInstance().checkAvailability(context)
        }
        if (a.isUnsupported) onUnsupported()
    }

    SideEffect {
        st.opacity = ui.opacity
        st.flipped = ui.flipped
        st.locked = ui.locked
    }
    LaunchedEffect(bitmap) { if (bitmap != null) st.pendingBitmap = bitmap }
    LaunchedEffect(Unit) {
        while (true) {
            status = st.status
            delay(250)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    try {
                        if (renderer.session == null) {
                            val result = ArCoreApk.getInstance()
                                .requestInstall(activity, !installRequested[0])
                            if (result == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                                installRequested[0] = true
                                return@LifecycleEventObserver
                            }
                            val session = Session(activity)
                            val config = Config(session).apply {
                                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                                focusMode = Config.FocusMode.AUTO
                                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                                lightEstimationMode = Config.LightEstimationMode.DISABLED
                            }
                            session.configure(config)
                            renderer.session = session
                        }
                        renderer.session?.resume()
                        glHolder[0]?.onResume()
                    } catch (e: Exception) {
                        error = "AR start nahi hua: ${e.message}"
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    glHolder[0]?.onPause()
                    renderer.session?.pause()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            glHolder[0]?.onPause()
            renderer.session?.close()
            renderer.session = null
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    preserveEGLContextOnPause = true
                    setEGLContextClientVersion(2)
                    setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    glHolder[0] = this
                }
            }
        )

        // Touch layer: tap = place, drag = move, pinch = size, twist = rotate
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(ui.locked) {
                    if (!ui.locked) detectTapGestures { st.taps.add(floatArrayOf(it.x, it.y)) }
                }
                .pointerInput(ui.locked) {
                    if (!ui.locked) {
                        detectTransformGestures { _, pan, zoom, rot ->
                            st.applyGesture(pan.x, pan.y, zoom, rot)
                        }
                    }
                }
        )

        if (ui.grid) GridOverlay()

        val msg = error ?: status
        if (msg.isNotEmpty()) {
            Text(
                msg,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        if (error != null) {
            Button(
                onClick = onSimple,
                modifier = Modifier.align(Alignment.Center)
            ) { Text("Simple mode use karo") }
        }

        Controls(ui) {
            OutlinedButton(onClick = { st.clearRequested = true }) { Text("Clear") }
            OutlinedButton(onClick = onSimple) { Text("Simple mode") }
        }
    }
}
