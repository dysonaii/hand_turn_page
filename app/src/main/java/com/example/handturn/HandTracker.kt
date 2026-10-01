package com.example.handturn

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker.HandLandmarkerOptions
import java.nio.ByteBuffer
import kotlin.math.hypot

/** 手勢：讚=下頁，七=上頁（不分左右手）。Camera2 前鏡頭 + MediaPipe，幾何規則分類。 */
class HandTracker(
    private val ctx: Context,
    private val onNext: () -> Unit,
    private val onPrev: () -> Unit,
    private val onStatus: (String) -> Unit = {},
) {
    companion object {
        const val MODEL_ASSET = "hand_landmarker.task"
        const val W = 480
        const val H = 360
        private const val FRAME_GAP_MS = 100L // ~10fps，省電且夠用
        private const val NEED_FRAMES = 3 // 連續同手勢才觸發，防抖
        // ponytail: 純距離幾何，不訓練分類器；鏡像不影響伸/屈判斷
        fun classify(lm: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>): Int {
            fun dist(a: Int, b: Int) = hypot((lm[a].x() - lm[b].x()).toDouble(), (lm[a].y() - lm[b].y()).toDouble())
            fun extended(tip: Int, pip: Int) = dist(tip, 0) > dist(pip, 0) * 1.15
            val thumb = dist(4, 17) > dist(2, 17) * 1.2
            val idx = extended(8, 6)
            val mid = extended(12, 10)
            val ring = extended(16, 14)
            val pinky = extended(20, 18)
            if (!thumb && !idx && !mid && !ring && !pinky) return 1 // 拳頭=下頁
            if (!thumb && idx && mid && !ring && !pinky) return 2 // 剪刀=上頁
            return 0
        }

        /** 自檢：幾何規則在正規化座標下是否成立（無需相機/模型）。 */
        fun frontSensorDeg(ctx: Context): Int {
            return try {
                val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val id = mgr.cameraIdList.firstOrNull {
                    mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_FRONT
                } ?: return 0
                mgr.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            } catch (_: Exception) { 0 }
        }

        fun displayDeg(ctx: Context): Int {
            return try {
                val rot = if (android.os.Build.VERSION.SDK_INT >= 30) ctx.display?.rotation ?: 0
                else @Suppress("DEPRECATION") (ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.rotation
                when (rot) {
                    Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180
                    Surface.ROTATION_270 -> 270; else -> 0
                }
            } catch (_: Exception) { 0 }
        }

        /**
         * 前鏡頭預覽塞滿 TextureView：轉正＋鏡像，橫豎皆通（buffer 固定 W×H，呼叫前先
         * surfaceTexture.setDefaultBufferSize(W, H)；view 量好尺寸後呼叫，resize/轉屏重調）。
         */
        fun fitPreview(tv: android.view.TextureView, sensorDeg: Int, dispDeg: Int) {
            val vw = tv.width; val vh = tv.height
            if (vw == 0 || vh == 0) return
            val swap = dispDeg == 90 || dispDeg == 270
            val bw = if (swap) H else W
            val bh = if (swap) W else H
            val viewRect = android.graphics.RectF(0f, 0f, vw.toFloat(), vh.toFloat())
            val bufRect = android.graphics.RectF(0f, 0f, bw.toFloat(), bh.toFloat())
            bufRect.offset(viewRect.centerX() - bufRect.centerX(), viewRect.centerY() - bufRect.centerY())
            val m = android.graphics.Matrix()
            m.setRectToRect(viewRect, bufRect, android.graphics.Matrix.ScaleToFit.FILL)
            val scale = maxOf(vh.toFloat() / bh, vw.toFloat() / bw)
            m.postScale(scale, scale, viewRect.centerX(), viewRect.centerY())
            // ponytail: total 三方向實測收斂——豎屏 0、橫屏兩向各由 180/90 修正到 disp 本身；
            // 即 total=dispDeg（sensor 在此機上掉出公式，參數保留以備他機）
            val total = dispDeg
            // ponytail: post 呼叫順序與像素生效順序相反——要「先轉正後鏡像」，
            // 代碼必須先 postScale(鏡像) 再 postRotate，否則鏡像把旋轉方向翻轉，豎屏差 90 度
            m.postScale(-1f, 1f, viewRect.centerX(), viewRect.centerY())
            m.postRotate(total.toFloat(), viewRect.centerX(), viewRect.centerY())
            tv.setTransform(m)
        }

        /** 自檢：幾何規則在正規化座標下是否成立（無需相機/模型）。 */
        private fun lm(x: Float, y: Float) =
            com.google.mediapipe.tasks.components.containers.NormalizedLandmark.create(x, y, 0f)

        fun demo(): String {
            // 0=拳頭 1=剪刀 2=手掌 3=讚
            fun hand(mode: Int): List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> {
                val idxOut = mode == 1 || mode == 2
                val midOut = mode == 1 || mode == 2
                val restOut = mode == 2
                val thumbOut = mode == 2 || mode == 3
                val p = Array(21) { lm(0.5f, 0.6f) }.toMutableList()
                p[0] = lm(0.5f, 0.9f)
                p[2] = lm(0.45f, 0.6f); p[4] = if (thumbOut) lm(0.2f, 0.5f) else lm(0.45f, 0.62f)
                p[6] = lm(0.55f, 0.5f); p[8] = if (idxOut) lm(0.55f, 0.2f) else lm(0.55f, 0.58f)
                p[10] = lm(0.6f, 0.5f); p[12] = if (midOut) lm(0.6f, 0.2f) else lm(0.6f, 0.58f)
                p[14] = lm(0.65f, 0.5f); p[16] = if (restOut) lm(0.65f, 0.2f) else lm(0.65f, 0.58f)
                p[17] = lm(0.7f, 0.55f); p[18] = lm(0.72f, 0.5f); p[20] = if (restOut) lm(0.72f, 0.2f) else lm(0.72f, 0.58f)
                return p
            }
            check(classify(hand(0)) == 1) { "拳頭應=1" }
            check(classify(hand(1)) == 2) { "剪刀應=2" }
            check(classify(hand(2)) == 0) { "手掌應=0" }
            check(classify(hand(3)) == 0) { "讚應=0" }
            return "HandTracker demo OK"
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var bg: Handler? = null
    private var reader: ImageReader? = null
    private var cam: android.hardware.camera2.CameraDevice? = null
    private var session: android.hardware.camera2.CameraCaptureSession? = null
    private var landmarker: HandLandmarker? = null
    private var lastFrameAt = 0L
    private var busy = false
    private var lastGesture = 0
    private var streak = 0
    private var fired = 0 // 已觸發的手勢；同手勢鎖住，換手勢或出鏡才解
    private var lastFireAt = 0L
    private var lastStatusAt = 0L
    @Volatile var cooldownMs = 1500L
    @Volatile var running = false

    /** model 缺失/權限不足回 false，呼叫方 Toast 提示，不炸。preview 有給才顯示小窗。 */
    fun start(preview: Surface? = null): Boolean {
        if (running) return true
        if (ctx.checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            status("缺相機權限"); return false
        }
        try {
            if (landmarker == null) {
                val base = BaseOptions.builder().setModelAssetPath(MODEL_ASSET).setDelegate(Delegate.CPU).build()
                val opt = HandLandmarkerOptions.builder()
                    .setBaseOptions(base).setRunningMode(RunningMode.IMAGE)
                    .setNumHands(1).setMinHandDetectionConfidence(0.5f)
                    .setMinHandPresenceConfidence(0.5f).setMinTrackingConfidence(0.5f).build()
                landmarker = HandLandmarker.createFromOptions(ctx, opt)
            }
        } catch (e: Exception) {
            status("缺模型：assets/$MODEL_ASSET 沒放（見 readme）"); return false
        }
        try {
            val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val front = mgr.cameraIdList.firstOrNull {
                mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: run { status("找不到前鏡頭"); return false }
            val rot = sensorToDisplay(mgr, front)
            thread = HandlerThread("hand").also { it.start() }
            bg = Handler(thread!!.looper)
            reader = ImageReader.newInstance(W, H, android.graphics.ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener({ r -> onFrame(r.acquireLatestImage(), rot) }, bg)
            }
            @Suppress("MissingPermission")
            mgr.openCamera(front, object : android.hardware.camera2.CameraDevice.StateCallback() {
                override fun onOpened(d: android.hardware.camera2.CameraDevice) {
                    cam = d
                    try {
                        val req = d.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW)
                        // ponytail: 有預覽才加第二路；辨識走 ImageReader 不受影響
                        val targets = mutableListOf(reader!!.surface)
                        preview?.let { targets.add(it) }
                        targets.forEach { req.addTarget(it) }
                        d.createCaptureSession(targets, object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: android.hardware.camera2.CameraCaptureSession) {
                                session = s
                                req.set(android.hardware.camera2.CaptureRequest.CONTROL_MODE, android.hardware.camera2.CameraMetadata.CONTROL_MODE_AUTO)
                                s.setRepeatingRequest(req.build(), null, bg)
                            }
                            override fun onConfigureFailed(s: android.hardware.camera2.CameraCaptureSession) { status("相機啟動失敗") }
                        }, bg)
                    } catch (e: Exception) { status("相機啟動失敗") }
                }
                override fun onDisconnected(d: android.hardware.camera2.CameraDevice) { stop() }
                override fun onError(d: android.hardware.camera2.CameraDevice, e: Int) { status("相機錯誤 $e"); stop() }
            }, bg)
            running = true
            return true
        } catch (e: SecurityException) {
            status("缺相機權限"); stop(); return false
        } catch (e: Exception) {
            status("相機開不了：${e.message}"); stop(); return false
        }
    }

    fun stop() {
        running = false
        try { session?.stopRepeating(); session?.close() } catch (_: Exception) {}
        try { cam?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        thread?.quitSafely()
        session = null; cam = null; reader = null; thread = null; bg = null
        busy = false; streak = 0; lastGesture = 0; fired = 0
    }

    fun close() { stop(); try { landmarker?.close() } catch (_: Exception) {}; landmarker = null }

    private fun onFrame(img: android.media.Image?, rot: Int) {
        if (img == null) return
        try {
            val now = android.os.SystemClock.uptimeMillis()
            if (!running || busy || now - lastFrameAt < FRAME_GAP_MS) return
            lastFrameAt = now
            busy = true
            val bmp = yuvToBitmap(img, rot)
            bg?.post { detect(bmp) }
        } finally { try { img.close() } catch (_: Exception) {} }
    }

    private fun detect(bmp: Bitmap) {
        try {
            val mk = landmarker ?: return
            val res = mk.detect(BitmapImageBuilder(bmp).build())
            val hands = res.landmarks()
            if (hands.isEmpty()) { reset(); statusThrottled("手出鏡：再入鏡可翻"); return }
            val g = classify(hands[0])
            // 同手勢鎖住不連翻；換手勢（拳頭↔剪刀）直翻；出鏡清鎖
            if (g == 0) { lastGesture = 0; streak = 0; statusThrottled("拳頭=下頁，剪刀=上頁"); return }
            if (g == fired) { statusThrottled("已翻過：換手勢或出鏡後再比"); return }
            if (g == lastGesture) streak++ else { lastGesture = g; streak = 1 }
            statusThrottled(if (g == 1) "看到：拳頭（下頁）$streak/3" else "看到：剪刀（上頁）$streak/3")
            val now = android.os.SystemClock.uptimeMillis()
            if (streak >= NEED_FRAMES && now - lastFireAt >= cooldownMs) {
                lastFireAt = now; fired = g; lastGesture = 0; streak = 0
                main.post { if (g == 1) onNext() else onPrev() }
            }
        } catch (_: Exception) {
        } finally { busy = false }
    }

    private fun reset() { lastGesture = 0; streak = 0; fired = 0 }

    private fun status(s: String) { main.post { try { onStatus(s) } catch (_: Exception) {} } }

    private fun statusThrottled(s: String) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastStatusAt < 800) return
        lastStatusAt = now; status(s)
    }

    private fun sensorToDisplay(mgr: CameraManager, id: String): Int {
        return try {
            val sensor = mgr.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val disp = if (android.os.Build.VERSION.SDK_INT >= 30) ctx.display?.rotation ?: 0
            else @Suppress("DEPRECATION") wm.defaultDisplay.rotation
            val deg = when (disp) {
                Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270 else -> 0
            }
            (sensor - deg + 360) % 360 // 前鏡頭直立通常 270
        } catch (_: Exception) { 0 }
    }

    // ponytail: 480x360 餵模型（320 在前鏡頭小手上糊掉）；逐像素轉 10fps 無壓力
    private fun yuvToBitmap(img: android.media.Image, rot: Int): Bitmap {
        val y = img.planes[0].buffer; val u = img.planes[1].buffer; val v = img.planes[2].buffer
        val ys = img.planes[0].rowStride; val us = img.planes[1].rowStride; val ups = img.planes[1].pixelStride
        val w = img.width; val h = img.height
        val out = IntArray(w * h)
        var i = 0
        for (r in 0 until h) for (c in 0 until w) {
            val yy = (y.get(r * ys + c).toInt() and 0xFF) - 16
            val ui = (u.get((r / 2) * us + (c / 2) * ups).toInt() and 0xFF) - 128
            val vi = (v.get((r / 2) * us + (c / 2) * ups).toInt() and 0xFF) - 128
            var rr = (1.164f * yy + 1.596f * vi).toInt()
            var gg = (1.164f * yy - 0.391f * ui - 0.813f * vi).toInt()
            var bb = (1.164f * yy + 2.018f * ui).toInt()
            rr = rr.coerceIn(0, 255); gg = gg.coerceIn(0, 255); bb = bb.coerceIn(0, 255)
            out[i++] = 0xFF000000.toInt() or (rr shl 16) or (gg shl 8) or bb
        }
        var bmp = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        if (rot != 0) {
            val m = Matrix().apply { postRotate(rot.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, w, h, m, false)
        }
        // 統一縮到 320x240 餵模型
        return if (bmp.width != W || bmp.height != H) Bitmap.createScaledBitmap(bmp, W, H, false) else bmp
    }
}
