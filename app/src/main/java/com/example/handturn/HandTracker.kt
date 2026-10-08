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

/** 手勢：拳頭=下頁，剪刀=上頁，手掌=綠圈不翻（當換手勢解鎖）。Camera2 前鏡頭 + MediaPipe，幾何規則分類。 */
class HandTracker(
    private val ctx: Context,
    private val onNext: () -> Unit,
    private val onPrev: () -> Unit,
    private val onStatus: (String) -> Unit = {},
    // ponytail: 小窗圈色用——每幀推手勢＋手心概位（偵測座標系，未鏡像）；0=無手/非目標，不畫；1=拳頭紅圈下頁，2=剪刀黃圈上頁，3=手掌綠圈不翻
    private val onHand: (Int, Float, Float) -> Unit = { _, _, _ -> },
) {
    companion object {
        const val MODEL_ASSET = "hand_landmarker.task"
        const val W = 480
        const val H = 360
        private const val FRAME_GAP_MS = 100L // ~10fps，省電且夠用
        private const val NEED_FRAMES = 3 // 連續同手勢才觸發，防抖
        private const val GRACE_MS = 1500L // ponytail: 開機寬限——點球時手還在鏡頭前，不等撤手就秒翻+跑條
        // ponytail: 純距離幾何，不訓練分類器；鏡像不影響伸/屈判斷
        fun classify(lm: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>): Int {
            fun dist(a: Int, b: Int) = hypot((lm[a].x() - lm[b].x()).toDouble(), (lm[a].y() - lm[b].y()).toDouble())
            fun extended(tip: Int, pip: Int) = dist(tip, 0) > dist(pip, 0) * 1.15
            // ponytail: 手背朝鏡頭時整手透視壓扁，腕比會失效；加局部伸直（指尖-掌根 > 指節-掌根 ×1.6）當 OR，正反面皆通
            fun straight(tip: Int, pip: Int, mcp: Int) = dist(tip, mcp) > dist(pip, mcp) * 1.6
            val thumbWide = dist(4, 17) > dist(2, 17) * 1.2 // 拇指張開（手心/手背皆成立）
            val idx = extended(8, 6) || straight(8, 6, 5)
            val mid = extended(12, 10) || straight(12, 10, 9)
            val ring = extended(16, 14) || straight(16, 14, 13)
            val pinky = extended(20, 18) || straight(20, 18, 17)
            if (!thumbWide && !idx && !mid && !ring && !pinky) return 1 // 拳頭=下頁
            if (!thumbWide && idx && mid && !ring && !pinky) return 2 // 剪刀=上頁（拇指內收也算）
            if (idx && mid && ring && pinky) return 3 // 手掌=綠圈不翻（四指伸就算，拇指怎麼擺都算）
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
            // ponytail: 顯示帶鏡像時旋轉取反才正——豎屏 0 不變，橫屏 90↔270 對調（180 自反不變）；
            // sensor 在此機上掉出公式，參數保留以備他機
            val total = (360 - dispDeg) % 360
            // ponytail: 鏡像靠 HAL 自帶，不進矩陣——矩陣裡鏡像會跟旋轉打架；這裡只轉正＋填滿
            m.postRotate(total.toFloat(), viewRect.centerX(), viewRect.centerY())
            tv.setTransform(m)
        }

        /** 自檢：幾何規則在正規化座標下是否成立（無需相機/模型）。 */
        private fun lm(x: Float, y: Float) =
            com.google.mediapipe.tasks.components.containers.NormalizedLandmark.create(x, y, 0f)

        fun demo(): String {
            // 0=拳頭 1=剪刀 2=手掌 3=讚 4=手背手掌（拇指內收） 5=手背剪刀 6=拳頭拇指橫壓 7=手掌拇指屈折貼掌
            fun hand(mode: Int): List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> {
                val base = when (mode) { 4 -> 2; 5 -> 1; 6 -> 0; 7 -> 2; else -> mode }
                val idxOut = base == 1 || base == 2
                val midOut = base == 1 || base == 2
                val restOut = base == 2
                val thumbOut = base == 2 || base == 3
                val p = Array(21) { lm(0.5f, 0.6f) }.toMutableList()
                p[0] = lm(0.5f, 0.9f)
                p[2] = lm(0.45f, 0.6f); p[4] = if (thumbOut) lm(0.2f, 0.5f) else lm(0.45f, 0.62f)
                p[6] = lm(0.55f, 0.5f); p[8] = if (idxOut) lm(0.55f, 0.2f) else lm(0.55f, 0.58f)
                p[10] = lm(0.6f, 0.5f); p[12] = if (midOut) lm(0.6f, 0.2f) else lm(0.6f, 0.58f)
                p[14] = lm(0.65f, 0.5f); p[16] = if (restOut) lm(0.65f, 0.2f) else lm(0.65f, 0.58f)
                p[17] = lm(0.7f, 0.55f); p[18] = lm(0.72f, 0.5f); p[20] = if (restOut) lm(0.72f, 0.2f) else lm(0.72f, 0.58f)
                if (mode == 4) p[4] = lm(0.52f, 0.35f) // 手背：拇指內收貼食指，腕比失效、局部仍直
                if (mode == 5) p[4] = lm(0.53f, 0.38f) // 手背剪刀：拇指內收
                if (mode == 6) p[4] = lm(0.62f, 0.58f) // 拳頭拇指橫壓指上
                if (mode == 7) p[4] = lm(0.52f, 0.58f) // 手掌拇指屈折貼掌：四指伸就算
                return p
            }
            check(classify(hand(0)) == 1) { "拳頭應=1" }
            check(classify(hand(1)) == 2) { "剪刀應=2" }
            check(classify(hand(2)) == 3) { "手掌應=3" }
            check(classify(hand(3)) == 0) { "讚應=0" }
            check(classify(hand(4)) == 3) { "手背手掌應=3" }
            check(classify(hand(5)) == 2) { "手背剪刀應=2" }
            check(classify(hand(6)) == 1) { "拳頭（拇指橫壓）應=1" }
            check(classify(hand(7)) == 3) { "手掌（拇指屈折）應=3" }
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
    @Volatile var lastFireAt = 0L // ponytail: 冷卻條讀這個算進度，0=還沒翻過=就緒
    @Volatile var startAt = 0L // ponytail: 本輪開機時間，寬限內不辨識
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
            startAt = android.os.SystemClock.uptimeMillis()
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
            if (!running || busy || now - lastFrameAt < FRAME_GAP_MS || now - startAt < GRACE_MS) return
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
            if (hands.isEmpty()) { reset(); statusThrottled("手出鏡：再入鏡可翻"); hand(0, 0f, 0f); return }
            val g = classify(hands[0])
            // ponytail: 手心=21 點平均，夠畫圈用，不另算 bbox
            var sx = 0f; var sy = 0f
            for (p in hands[0]) { sx += p.x(); sy += p.y() }
            hand(g, sx / hands[0].size, sy / hands[0].size)
            // 同手勢鎖住不連翻；手掌只解鎖不翻；拳頭↔剪刀、手掌→拳頭/剪刀直切即翻；出鏡清鎖
            if (g == 0) { lastGesture = 0; streak = 0; statusThrottled("拳頭=下頁，剪刀=上頁"); return }
            if (g == 3) {
                if (g == lastGesture) streak++ else { lastGesture = g; streak = 1 }
                if (streak >= NEED_FRAMES) fired = 0 // 穩定手掌當換手勢，解掉同手勢鎖
                statusThrottled("看到：手掌（不翻頁）$streak/3")
                return
            }
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

    private fun hand(g: Int, cx: Float, cy: Float) { main.post { try { onHand(g, cx, cy) } catch (_: Exception) {} } }

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
