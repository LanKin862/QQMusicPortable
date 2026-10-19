package com.portable.qqmusic.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.portable.qqmusic.R
import com.portable.qqmusic.core.Log
import com.portable.qqmusic.core.Task
import com.portable.qqmusic.data.api.Login
import com.portable.qqmusic.data.api.QrLogin
import com.portable.qqmusic.ui.util.Tip

/**
 * QQ 音乐二维码登录。
 *
 * 交互流程:
 *  打开 -> 申请二维码 (ptqrshow) -> 本地用 ZXing 绘制 -> 每 2 秒轮询扫码状态
 *  -> 扫码成功后在后台换取 qm_keyst 并持久化 cookie -> 关闭页面
 *
 * 二维码图片内容不需要真的被 QQ 扫描 —— 网页版 ptqrshow 返回的就是二维码图片本身,
 * 扫描它等同于扫描官方码; 这里直接使用返回的图片, 不重新编码, 保证与官方一致。
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var qrImage: ImageView
    private lateinit var overlay: View
    private lateinit var overlayText: TextView
    private lateinit var stateText: TextView
    private lateinit var refreshButton: TextView
    private lateinit var scanHint: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var session: QrLogin.QrSession? = null
    private var polling = false
    private var exchanging = false

    private val pollTask = object : Runnable {
        override fun run() {
            val s = session ?: return
            if (!polling) return
            Task.io {
                val result = QrLogin.poll(s)
                Task.main {
                    if (isFinishing || isDestroyed) return@main
                    when (result.state) {
                        QrLogin.State.WAITING -> {
                            stateText.text = getString(R.string.login_state_waiting)
                            scheduleNextPoll()
                        }
                        QrLogin.State.SCANNED -> {
                            stateText.text = getString(R.string.login_state_scanned)
                            scheduleNextPoll()
                        }
                        QrLogin.State.CONFIRMED -> {
                            polling = false
                            stateText.text = getString(R.string.login_state_confirmed)
                            exchangeTicket(result.redirectUrl)
                        }
                        QrLogin.State.EXPIRED -> {
                            polling = false
                            showOverlay(getString(R.string.login_state_expired))
                            stateText.text = getString(R.string.login_state_expired)
                        }
                        QrLogin.State.FAILED -> {
                            polling = false
                            stateText.text = getString(R.string.login_state_failed)
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        qrImage = findViewById(R.id.qr_image)
        overlay = findViewById(R.id.qr_overlay)
        overlayText = findViewById(R.id.overlay_text)
        stateText = findViewById(R.id.state_text)
        refreshButton = findViewById(R.id.btn_refresh)
        scanHint = findViewById(R.id.scan_hint)

        overlay.setOnClickListener { requestQr() }
        refreshButton.setOnClickListener { requestQr() }

        requestQr()
    }

    // ---------------- 申请二维码 ----------------

    private fun requestQr() {
        polling = false
        exchanging = false
        overlay.visibility = View.GONE
        qrImage.setImageDrawable(null)
        stateText.text = getString(R.string.loading)

        Task.io {
            val s = QrLogin.requestQr()
            Task.main {
                if (isFinishing || isDestroyed) return@main
                if (s == null || s.qrImage.isEmpty()) {
                    stateText.text = getString(R.string.network_error)
                    showOverlay(getString(R.string.action_retry))
                    return@main
                }
                session = s
                renderQr(s.qrImage)
                stateText.text = getString(R.string.login_state_waiting)
                polling = true
                handler.postDelayed(pollTask, POLL_INTERVAL_MS)
            }
        }
    }

    private fun scheduleNextPoll() {
        if (!polling) return
        handler.removeCallbacks(pollTask)
        handler.postDelayed(pollTask, POLL_INTERVAL_MS)
    }

    /**
     * 用 ptqrshow 返回的 PNG 直接显示。
     * 若图片解析失败 (服务端偶发返回非 PNG), 回退到用二维码内容本地生成一张。
     */
    private fun renderQr(bytes: ByteArray) {
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bmp != null) {
            qrImage.setImageBitmap(bmp)
            return
        }
        Log.w("LoginActivity", "qr bytes not decodable, drawing placeholder")
        qrImage.setImageBitmap(placeholderQr())
    }

    /** 兜底占位二维码 (仅提示作用, 不可扫描) */
    private fun placeholderQr(): Bitmap = buildQrBitmap("https://y.qq.com")

    private fun buildQrBitmap(content: String): Bitmap {
        return try {
            val hints = HashMap<EncodeHintType, Any>().apply {
                put(EncodeHintType.CHARACTER_SET, "UTF-8")
                put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
                put(EncodeHintType.MARGIN, 1)
            }
            val size = 600
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (t: Throwable) {
            Log.w("LoginActivity.qr", t)
            Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565)
        }
    }

    // ---------------- 票据交换 ----------------

    private fun exchangeTicket(redirectUrl: String) {
        if (exchanging) return
        exchanging = true
        stateText.text = getString(R.string.loading)

        Task.io {
            val uin = try {
                QrLogin.exchange(redirectUrl)
            } catch (t: Throwable) {
                Log.w("LoginActivity.exchange", t)
                null
            }
            Task.main {
                if (isFinishing || isDestroyed) return@main
                if (uin.isNullOrEmpty() || !Login.isLoggedIn()) {
                    stateText.text = getString(R.string.login_state_failed)
                    showOverlay(getString(R.string.action_retry))
                    exchanging = false
                    return@main
                }
                Tip.toast(this, getString(R.string.login_success, Login.nick().ifEmpty { uin }))
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    private fun showOverlay(text: String) {
        overlayText.text = text
        overlay.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        polling = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        /** 轮询间隔: 2 秒足够及时, 又不会给 ptlogin 造成压力 */
        private const val POLL_INTERVAL_MS = 2000L
    }
}
