package com.decibelevidence.app.capture

import android.graphics.Bitmap
import android.util.Log
import com.decibelevidence.app.BuildConfig
import android.graphics.SurfaceTexture
import android.opengl.*
import android.view.Surface
import com.decibelevidence.app.model.OverlayState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Single EGL context on the capture thread. One composed FBO feeds all three output paths. */
internal class EglRenderer(val width: Int, val height: Int) {
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private lateinit var config: EGLConfig
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var initialized = false
    private var closed = false
    private var preview: EGLSurface? = null
    private var previewWidth = width
    private var previewHeight = height
    private var encoder: EGLSurface? = null
    private val vertices = floats(-1f,-1f,0f,0f, 1f,-1f,1f,0f, -1f,1f,0f,1f, 1f,1f,1f,1f)
    private val textureMatrix = FloatArray(16)
    private val rotationMatrix = FloatArray(16)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private var oesProgram: Int = 0
    private var bitmapProgram: Int = 0
    private var cameraTexture: Int = 0
    private var outputTexture: Int = 0
    private var overlayTexture: Int = 0
    private var framebuffer: Int = 0
    private val overlay = OverlayPainter(width, height)
    lateinit var cameraInput: SurfaceTexture
        private set
    lateinit var cameraSurface: Surface
        private set
    var hasFrame = false
        private set

    init {
        try {
        check(display != EGL14.EGL_NO_DISPLAY) { "无法连接图形显示" }
        val versions = IntArray(2)
        check(EGL14.eglInitialize(display, versions, 0, versions, 1)) { "无法初始化EGL" }
        initialized = true
        val configs = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
        val attributes = intArrayOf(EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,0x3142,1,EGL14.EGL_NONE)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "设备没有可录像的EGL配置" }
        config = configs[0]!!
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "无法创建OpenGL上下文" }
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE),0)
        check(pbuffer != EGL14.EGL_NO_SURFACE) { "无法创建离屏画布" }
        current(pbuffer)
        oesProgram = program("#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES uTexture; varying vec2 vUv; void main(){gl_FragColor=texture2D(uTexture,vUv);}")
        bitmapProgram = program("precision mediump float; uniform sampler2D uTexture; varying vec2 vUv; void main(){gl_FragColor=texture2D(uTexture,vUv);}")
        cameraTexture = texture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        outputTexture = texture(GLES20.GL_TEXTURE_2D)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,width,height,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null)
        overlayTexture = texture(GLES20.GL_TEXTURE_2D)
        val names = IntArray(1); GLES20.glGenFramebuffers(1,names,0); framebuffer = names[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,framebuffer)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,GLES20.GL_TEXTURE_2D,outputTexture,0)
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) { "设备不支持合成画布" }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0)
        cameraInput = SurfaceTexture(cameraTexture)
        cameraSurface = Surface(cameraInput)
        } catch (error: Throwable) { close(); throw error }
    }

    fun attachPreview(surface: Surface, width: Int, height: Int) {
        detachPreview()
        previewWidth = width; previewHeight = height
        preview = window(surface)
    }
    fun detachPreview() {
        current(pbuffer)
        preview?.let { EGL14.eglDestroySurface(display,it) }; preview = null
    }
    fun attachEncoder(surface: Surface) { detachEncoder(); encoder = window(surface) }
    fun detachEncoder() {
        current(pbuffer)
        encoder?.let { EGL14.eglDestroySurface(display,it) }; encoder = null
    }

    fun updateFrame(): Long {
        current(pbuffer)
        cameraInput.updateTexImage(); cameraInput.getTransformMatrix(textureMatrix)
        if (!hasFrame && BuildConfig.DEBUG) Log.i("EvidenceCamera", "textureMatrix=${textureMatrix.contentToString()}")
        hasFrame = true
        return cameraInput.timestamp
    }

    fun compose(state: OverlayState, atNs: Long, sourceWidth: Int, sourceHeight: Int, clockwiseRotation: Int) {
        current(pbuffer)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,framebuffer)
        GLES20.glViewport(0,0,width,height)
        GLES20.glClearColor(0f,0f,0f,1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        // Texture lookup uses the inverse of the desired clockwise image rotation.
        Matrix.setIdentityM(rotationMatrix,0)
        Matrix.translateM(rotationMatrix,0,.5f,.5f,0f)
        Matrix.rotateM(rotationMatrix,0,additionalTextureRotation(textureMatrix,clockwiseRotation).toFloat(),0f,0f,1f)
        Matrix.translateM(rotationMatrix,0,-.5f,-.5f,0f)
        val combined = FloatArray(16)
        Matrix.multiplyMM(combined,0,textureMatrix,0,rotationMatrix,0)
        val swap = clockwiseRotation % 180 != 0
        viewportFit(if (swap) sourceHeight else sourceWidth,if (swap) sourceWidth else sourceHeight,width,height)
        draw(oesProgram,cameraTexture,GLES11Ext.GL_TEXTURE_EXTERNAL_OES,combined)
        if (overlay.update(state,atNs)) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,overlayTexture)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,overlay.bitmap,0)
        }
        GLES20.glViewport(0,0,width,overlay.panelHeight)
        val flip = FloatArray(16).also { Matrix.setIdentityM(it,0); Matrix.translateM(it,0,0f,1f,0f); Matrix.scaleM(it,0,1f,-1f,1f) }
        GLES20.glEnable(GLES20.GL_BLEND)
        // Canvas bitmaps are premultiplied alpha.
        GLES20.glBlendFunc(GLES20.GL_ONE,GLES20.GL_ONE_MINUS_SRC_ALPHA)
        draw(bitmapProgram,overlayTexture,GLES20.GL_TEXTURE_2D,flip)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0)
        checkGl("合成水印")
    }

    fun showPreview() {
        val target = preview ?: return
        try {
            current(target); clearWindow(previewWidth,previewHeight)
            viewportFit(width,height,previewWidth,previewHeight)
            draw(bitmapProgram,outputTexture,GLES20.GL_TEXTURE_2D,identity)
            check(EGL14.eglSwapBuffers(display,target)) { "预览Surface已失效" }
        } finally { current(pbuffer) }
    }

    fun encode(presentationNs: Long) {
        val target = checkNotNull(encoder)
        current(target); GLES20.glViewport(0,0,width,height)
        draw(bitmapProgram,outputTexture,GLES20.GL_TEXTURE_2D,identity)
        check(EGLExt.eglPresentationTimeANDROID(display,target,presentationNs)) { "无法设置录像时间戳" }
        check(EGL14.eglSwapBuffers(display,target)) { "录像画面提交失败" }
        current(pbuffer)
    }

    fun photo(): Bitmap {
        current(pbuffer); GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,framebuffer)
        val bytes = ByteBuffer.allocateDirect(width * height * 4)
        GLES20.glReadPixels(0,0,width,height,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,bytes)
        checkGl("读取照片"); GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0)
        val pixels = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val offset = ((height - 1 - y) * width + x) * 4
            val r = bytes.get(offset).toInt() and 255; val g = bytes.get(offset+1).toInt() and 255
            val b = bytes.get(offset+2).toInt() and 255
            pixels[y * width+x] = (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888)
    }

    fun close() {
        if (closed) return
        closed = true
        // Context loss must not prevent releasing SurfaceTexture, Surface or EGL ownership.
        if (::cameraSurface.isInitialized) runCatching { cameraSurface.release() }
        if (::cameraInput.isInitialized) runCatching { cameraInput.release() }
        runCatching { overlay.close() }
        if (initialized) {
            if (runCatching { current(pbuffer) }.isSuccess) {
                GLES20.glDeleteFramebuffers(1,intArrayOf(framebuffer),0)
                GLES20.glDeleteTextures(3,intArrayOf(cameraTexture,outputTexture,overlayTexture),0)
                GLES20.glDeleteProgram(oesProgram); GLES20.glDeleteProgram(bitmapProgram)
            }
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
            preview?.let { EGL14.eglDestroySurface(display,it) }; preview = null
            encoder?.let { EGL14.eglDestroySurface(display,it) }; encoder = null
            if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,pbuffer)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,context)
            EGL14.eglReleaseThread(); EGL14.eglTerminate(display)
        }
    }

    private fun window(surface: Surface): EGLSurface {
        val result = EGL14.eglCreateWindowSurface(display,config,surface,intArrayOf(EGL14.EGL_NONE),0)
        check(result != EGL14.EGL_NO_SURFACE) { "无法创建输出Surface: EGL ${EGL14.eglGetError()}" }; return result
    }
    private fun current(surface: EGLSurface) { check(EGL14.eglMakeCurrent(display,surface,surface,context)) { "切换EGL输出失败" } }
    private fun clearWindow(w: Int,h: Int) { GLES20.glViewport(0,0,w,h); GLES20.glClearColor(0f,0f,0f,1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT) }
    private fun viewportFit(sw: Int,sh: Int,dw: Int,dh: Int) {
        val scale = minOf(dw.toFloat()/sw,dh.toFloat()/sh)
        val w = (sw*scale).toInt(); val h = (sh*scale).toInt()
        GLES20.glViewport((dw-w)/2,(dh-h)/2,w,h)
    }
    private fun texture(target: Int): Int {
        val names = IntArray(1); GLES20.glGenTextures(1,names,0); GLES20.glBindTexture(target,names[0])
        GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
        return names[0]
    }
    private fun draw(program: Int,texture: Int,target: Int,matrix: FloatArray) {
        GLES20.glUseProgram(program)
        val position = GLES20.glGetAttribLocation(program,"aPosition"); val uv = GLES20.glGetAttribLocation(program,"aUv")
        vertices.position(0); GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,16,vertices); GLES20.glEnableVertexAttribArray(position)
        vertices.position(2); GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,16,vertices); GLES20.glEnableVertexAttribArray(uv)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"uMatrix"),1,false,matrix,0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(target,texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"uTexture"),0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
        GLES20.glDisableVertexAttribArray(position); GLES20.glDisableVertexAttribArray(uv)
    }
    private fun program(fragment: String): Int {
        fun shader(type: Int,source: String): Int {
            val id = GLES20.glCreateShader(type); GLES20.glShaderSource(id,source); GLES20.glCompileShader(id)
            val status = IntArray(1); GLES20.glGetShaderiv(id,GLES20.GL_COMPILE_STATUS,status,0)
            check(status[0] != 0) { "GL着色器编译失败: ${GLES20.glGetShaderInfoLog(id)}" }; return id
        }
        val vertex = shader(GLES20.GL_VERTEX_SHADER,"attribute vec2 aPosition; attribute vec2 aUv; uniform mat4 uMatrix; varying vec2 vUv; void main(){gl_Position=vec4(aPosition,0.,1.); vUv=(uMatrix*vec4(aUv,0.,1.)).xy;}")
        val pixel = shader(GLES20.GL_FRAGMENT_SHADER,fragment)
        val id = GLES20.glCreateProgram(); GLES20.glAttachShader(id,vertex); GLES20.glAttachShader(id,pixel); GLES20.glLinkProgram(id)
        val status = IntArray(1); GLES20.glGetProgramiv(id,GLES20.GL_LINK_STATUS,status,0)
        check(status[0] != 0) { "GL程序链接失败: ${GLES20.glGetProgramInfoLog(id)}" }
        GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(pixel); return id
    }
    private fun checkGl(action: String) { val error = GLES20.glGetError(); check(error == GLES20.GL_NO_ERROR) { "$action 失败: GL $error" } }
    private fun floats(vararg value: Float): FloatBuffer = ByteBuffer.allocateDirect(value.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(value); position(0) }
}
