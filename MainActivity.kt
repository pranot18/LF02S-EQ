package com.lf02s.eqcontroller

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var usbManager: UsbManager
    private var usbDevice: UsbDevice? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var outEndpoint: UsbEndpoint? = null

    private val ACTION_USB_PERMISSION = "com.lf02s.eqcontroller.USB_PERMISSION"

    // VID: 0x8888 (34952), PID: 0x171E (5918)
    private val TARGET_VID = 34952
    private val TARGET_PID = 5918

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (ACTION_USB_PERMISSION == action) {
                synchronized(this) {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { openUsbDevice(it) }
                    } else {
                        notifyWeb("Permission denied for USB device")
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED == action) {
                findAndConnectDevice()
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED == action) {
                closeUsbConnection()
                notifyWebDisconnected()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        // Setup WebView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                findAndConnectDevice()
            }
        }

        webView.addJavascriptInterface(AndroidUSBInterface(), "AndroidUSB")
        webView.loadUrl("file:///android_asset/index.html")

        val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        findAndConnectDevice()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        closeUsbConnection()
    }

    private fun findAndConnectDevice() {
        val deviceList = usbManager.deviceList
        for ((_, device) in deviceList) {
            if (device.vendorId == TARGET_VID && (device.productId == TARGET_PID || TARGET_PID == 0)) {
                usbDevice = device
                if (usbManager.hasPermission(device)) {
                    openUsbDevice(device)
                } else {
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_MUTABLE
                    } else {
                        0
                    }
                    val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
                    usbManager.requestPermission(device, permissionIntent)
                }
                return
            }
        }
    }

    private fun openUsbDevice(device: UsbDevice) {
        val connection = usbManager.openDevice(device) ?: return
        usbConnection = connection

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (connection.claimInterface(intf, true)) {
                usbInterface = intf
                for (j in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(j)
                    if (ep.direction == UsbConstants.USB_DIR_OUT) {
                        outEndpoint = ep
                        break
                    }
                }
                break
            }
        }

        notifyWebConnected(device.productName ?: "LF02S (BP1032A2)")

        // Send handshake
        sendBytes(byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0xFC.toByte(), 0x07.toByte(), 0x4D.toByte(), 0x75.toByte(), 0x73.toByte(), 0x69.toByte(), 0x63.toByte(), 0x5F.toByte(), 0x46.toByte(), 0x16.toByte()))
    }

    private fun closeUsbConnection() {
        try {
            usbInterface?.let { usbConnection?.releaseInterface(it) }
            usbConnection?.close()
        } catch (_: Exception) {}
        usbConnection = null
        usbInterface = null
        outEndpoint = null
    }

    private fun sendBytes(data: ByteArray): Boolean {
        val conn = usbConnection ?: return false
        var success = false

        outEndpoint?.let { ep ->
            val res = conn.bulkTransfer(ep, data, data.size, 1000)
            if (res >= 0) success = true
        }

        if (!success) {
            // Fallback to HID Set_Report control transfer (requestType: 0x21, request: 0x09, value: 0x0200)
            val intfId = usbInterface?.id ?: 0
            val res = conn.controlTransfer(0x21, 0x09, 0x0200, intfId, data, data.size, 1000)
            if (res >= 0) success = true
        }

        return success
    }

    private fun notifyWebConnected(name: String) {
        runOnUiThread {
            webView.evaluateJavascript("if(window.onUsbConnected) window.onUsbConnected('$name');", null)
        }
    }

    private fun notifyWebDisconnected() {
        runOnUiThread {
            webView.evaluateJavascript("if(window.onUsbDisconnected) window.onUsbDisconnected();", null)
        }
    }

    private fun notifyWeb(msg: String) {
        runOnUiThread {
            webView.evaluateJavascript("if(window.logMessage) window.logMessage('$msg');", null)
        }
    }

    inner class AndroidUSBInterface {
        @JavascriptInterface
        fun connect() {
            runOnUiThread { findAndConnectDevice() }
        }

        @JavascriptInterface
        fun sendHex(hexStr: String): Boolean {
            val cleanHex = hexStr.replace(" ", "").trim()
            val len = cleanHex.length
            val data = ByteArray(len / 2)
            for (i in 0 until len step 2) {
                data[i / 2] = ((Character.digit(cleanHex[i], 16) shl 4) + Character.digit(cleanHex[i + 1], 16)).toByte()
            }
            return sendBytes(data)
        }
    }
}
