package com.h.webandr

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val HOME = "https://www.google.com"
private const val REQ_MEDIA = 101
private const val REQ_STORAGE = 102
private const val REQ_FILE = 103

private const val COLOR_BAR = 0xFF2B1B4A.toInt()
private const val COLOR_TAB_BG = 0xFFE9E3F5.toInt()
private const val COLOR_TAB_ON = 0xFFFFFFFF.toInt()
private const val COLOR_TAB_TEXT = 0xFF5A4A7A.toInt()

@Suppress("DEPRECATION")
class MainActivity : Activity() {

    private class Tab(val webView: WebView, val button: TextView) {
        var title: String = "Tab mới"
        var url: String = ""
        var desktop: Boolean = false
    }

    private val tabs = mutableListOf<Tab>()
    private var active: Tab? = null

    private lateinit var prefs: SharedPreferences
    private lateinit var rootFrame: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var qrOverlay: FrameLayout
    private var qrWeb: WebView? = null
    private lateinit var camOverlay: FrameLayout
    private var camWeb: WebView? = null
    private lateinit var splitRoot: LinearLayout
    private lateinit var splitPaneHolder: FrameLayout

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermRequest: PermissionRequest? = null

    private val desktopUa =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val mobileUa: String by lazy { WebSettings.getDefaultUserAgent(this) }

    private val adHosts = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "google-analytics.com", "adnxs.com", "taboola.com", "outbrain.com", "popads.net",
        "propellerads.com", "adsterra.com", "exoclick.com", "pubmatic.com", "rubiconproject.com",
        "criteo.com", "scorecardresearch.com", "moatads.com", "amazon-adsystem.com"
    )

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ====================== Vong doi ======================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("webandr", Context.MODE_PRIVATE)
        buildUi()
        // Da tung dung QR Cam -> mo ngam de tu ket noi lai va chay lich quay.
        if (prefs.getBoolean("camUsed", false)) ensureCamWeb()

        if (Build.VERSION.SDK_INT <= 28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
        }

        val startUrl = intent?.dataString
        val saved = try { JSONArray(prefs.getString("tabs", "[]")) } catch (e: Exception) { JSONArray() }
        for (i in 0 until saved.length()) newTab(saved.optString(i), false)
        if (startUrl != null) newTab(startUrl) else if (tabs.isEmpty()) newTab(HOME)
        else selectTab(tabs[prefs.getInt("activeIdx", 0).coerceIn(0, tabs.size - 1)])
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.dataString?.let { newTab(it) }
    }

    override fun onPause() {
        super.onPause()
        val arr = JSONArray()
        for (t in tabs) if (t.url.startsWith("http")) arr.put(t.url)
        prefs.edit().putString("tabs", arr.toString())
            .putInt("activeIdx", tabs.indexOf(active).coerceAtLeast(0)).apply()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        for (t in tabs) t.webView.destroy()
        qrWeb?.destroy()
        camWeb?.destroy()
        splitTab?.webView?.destroy()
        synchronized(recordings) {
            for (o in recordings.values) try { o.close() } catch (e: Exception) { }
            recordings.clear()
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> hideCustomView()
            qrOverlay.visibility == View.VISIBLE -> qrOverlay.visibility = View.GONE
            camOverlay.visibility == View.VISIBLE -> camOverlay.visibility = View.GONE
            splitService != null && splitTab?.webView?.hasFocus() == true &&
                    splitTab?.webView?.canGoBack() == true -> splitTab?.webView?.goBack()
            active?.webView?.canGoBack() == true -> active?.webView?.goBack()
            tabs.size > 1 -> active?.let { closeTab(it) }
            else -> super.onBackPressed()
        }
    }

    // ====================== Giao dien ======================

    private fun buildUi() {
        rootFrame = FrameLayout(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        // Thanh dia chi
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(COLOR_BAR)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        fun iconBtn(label: String, onClick: (View) -> Unit): TextView = TextView(this).apply {
            text = label
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener { onClick(it) }
        }
        bar.addView(iconBtn("◀") { active?.webView?.let { w -> if (w.canGoBack()) w.goBack() } })
        bar.addView(iconBtn("⟳") { active?.webView?.reload() })

        urlBar = EditText(this).apply {
            setSingleLine(true)
            textSize = 14f
            setTextColor(Color.BLACK)
            setBackgroundColor(0xFFF2EEFA.toInt())
            setPadding(dp(12), dp(6), dp(12), dp(6))
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSelectAllOnFocus(true)
            hint = "Tìm kiếm hoặc nhập địa chỉ"
            setOnEditorActionListener { _, actionId, ev ->
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)
                ) {
                    loadInput(text.toString())
                    hideKeyboard()
                    true
                } else false
            }
        }
        bar.addView(urlBar, LinearLayout.LayoutParams(0, dp(40), 1f))
        bar.addView(iconBtn("⋮") { showMenu(it) })
        col.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        col.addView(progress, LinearLayout.LayoutParams(MATCH, dp(3)))

        // Thanh tab
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(COLOR_TAB_BG)
        }
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scroll.addView(tabBar, FrameLayout.LayoutParams(WRAP, MATCH))
        tabRow.addView(scroll, LinearLayout.LayoutParams(0, MATCH, 1f))
        val plus = TextView(this).apply {
            text = "＋"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(COLOR_BAR)
            setOnClickListener { newTab(HOME) }
        }
        tabRow.addView(plus, LinearLayout.LayoutParams(dp(44), MATCH))
        col.addView(tabRow, LinearLayout.LayoutParams(MATCH, dp(38)))

        // Noi dung
        content = FrameLayout(this)
        splitPaneHolder = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            visibility = View.GONE
        }
        splitRoot = LinearLayout(this)
        splitRoot.addView(splitPaneHolder, LinearLayout.LayoutParams(MATCH, 0, 3f))
        splitRoot.addView(content, LinearLayout.LayoutParams(MATCH, 0, 7f))
        col.addView(splitRoot, LinearLayout.LayoutParams(MATCH, 0, 1f))

        qrOverlay = FrameLayout(this).apply {
            setBackgroundColor(0xFF1A1030.toInt())
            visibility = View.GONE
        }
        content.addView(qrOverlay, FrameLayout.LayoutParams(MATCH, MATCH))

        camOverlay = FrameLayout(this).apply {
            setBackgroundColor(0xFF1A1030.toInt())
            visibility = View.GONE
        }
        content.addView(camOverlay, FrameLayout.LayoutParams(MATCH, MATCH))

        rootFrame.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(rootFrame)
        applySplitLayout()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySplitLayout()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBar.windowToken, 0)
        urlBar.clearFocus()
    }

    // ====================== Tab ======================

    @SuppressLint("SetJavaScriptEnabled")
    private fun createConfiguredWebView(): WebView {
        val wv = WebView(this)
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.allowFileAccess = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.setDownloadListener { dlUrl, userAgent, contentDisposition, mimeType, _ ->
            try {
                val req = DownloadManager.Request(Uri.parse(dlUrl))
                val fileName = URLUtil.guessFileName(dlUrl, contentDisposition, mimeType)
                req.setMimeType(mimeType)
                req.addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(dlUrl)?.let { req.addRequestHeader("Cookie", it) }
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                Toast.makeText(this, "Đang tải: $fileName", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Không tải được: " + e.message, Toast.LENGTH_LONG).show()
            }
        }

        attachPasswordBridge(wv)
        return wv
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newTab(url: String?, select: Boolean = true): Tab {
        val wv = createConfiguredWebView()

        val btn = TextView(this).apply {
            text = "Tab mới"
            textSize = 13f
            maxLines = 1
            maxWidth = dp(140)
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        val tab = Tab(wv, btn)
        btn.setOnClickListener { selectTab(tab) }
        btn.setOnLongClickListener { closeTab(tab); true }
        tabBar.addView(btn, LinearLayout.LayoutParams(WRAP, MATCH))

        wv.visibility = View.GONE
        content.addView(wv, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        tabs.add(tab)
        setupClients(tab)

        if (!url.isNullOrEmpty()) wv.loadUrl(url)
        if (select) selectTab(tab)
        return tab
    }

    private fun selectTab(tab: Tab) {
        active = tab
        for (t in tabs) {
            val on = t === tab
            t.webView.visibility = if (on) View.VISIBLE else View.GONE
            t.button.setBackgroundColor(if (on) COLOR_TAB_ON else Color.TRANSPARENT)
            t.button.setTextColor(if (on) COLOR_BAR else COLOR_TAB_TEXT)
        }
        urlBar.setText(tab.url)
        progress.visibility = View.GONE
    }

    private fun closeTab(tab: Tab) {
        val idx = tabs.indexOf(tab)
        if (idx < 0) return
        tabs.removeAt(idx)
        tabBar.removeView(tab.button)
        content.removeView(tab.webView)
        tab.webView.post { tab.webView.destroy() }
        if (tabs.isEmpty()) {
            newTab(HOME)
        } else if (active === tab) {
            selectTab(tabs[idx.coerceAtMost(tabs.size - 1)])
        }
    }

    private fun isAdHost(host: String): Boolean =
        adHosts.any { host == it || host.endsWith(".$it") }

    private fun setupClients(tab: Tab) {
        tab.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return false
                val scheme = u.scheme ?: return false
                if (scheme == "webandr-back") {
                    view?.loadUrl(HOME)
                    return true
                }
                if (scheme == "webandr-allow") {
                    u.getQueryParameter("h")?.let { phishingAllow.add(it.lowercase()) }
                    u.getQueryParameter("u")?.let { view?.loadUrl(it) }
                    return true
                }
                if (scheme in listOf("http", "https", "about", "data", "blob", "file", "javascript")) return false
                try {
                    val i = if (scheme == "intent") Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME)
                    else Intent(Intent.ACTION_VIEW, u)
                    i.addCategory(Intent.CATEGORY_BROWSABLE)
                    i.component = null
                    i.selector = null
                    startActivity(i)
                } catch (e: Exception) {
                    // khong co app xu ly - bo qua
                }
                return true
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val req = request ?: return null
                val host = req.url?.host ?: return null
                if (req.isForMainFrame && isPhishingHost(host) && !phishingAllow.contains(host.lowercase())) {
                    return phishingResponse(host, req.url.toString())
                }
                if (isAdHost(host)) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                tab.url = url ?: ""
                if (tab === active) {
                    urlBar.setText(tab.url)
                    progress.visibility = View.VISIBLE
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                tab.url = url ?: ""
                if (tab === active) {
                    urlBar.setText(tab.url)
                    progress.visibility = View.GONE
                }
                if (!docStartScriptSupported && url != null && url.startsWith("http")) {
                    view?.evaluateJavascript(PW_JS, null)
                }
                if (url != null && url.startsWith("http")) addHistory(view?.title ?: url, url)
            }
        }

        tab.webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                tab.title = title ?: tab.url
                tab.button.text = tab.title
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (tab === active) {
                    progress.progress = newProgress
                    progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                }
            }

            override fun onCreateWindow(
                view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?
            ): Boolean {
                val msg = resultMsg ?: return false
                val transport = msg.obj as? WebView.WebViewTransport ?: return false
                val t = newTab(null)
                transport.webView = t.webView
                msg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView?) {
                tabs.firstOrNull { it.webView === window }?.let { closeTab(it) }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) return
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customCallback = callback
                view.setBackgroundColor(Color.BLACK)
                rootFrame.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
                window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            }

            override fun onHideCustomView() {
                hideCustomView()
            }

            override fun onShowFileChooser(
                webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                return try {
                    startActivityForResult(fileChooserParams!!.createIntent(), REQ_FILE)
                    true
                } catch (e: Exception) {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = null
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                val req = request ?: return
                runOnUiThread {
                    val needed = mutableListOf<String>()
                    for (r in req.resources) {
                        if (r == PermissionRequest.RESOURCE_VIDEO_CAPTURE) needed.add(Manifest.permission.CAMERA)
                        if (r == PermissionRequest.RESOURCE_AUDIO_CAPTURE) needed.add(Manifest.permission.RECORD_AUDIO)
                    }
                    val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                    if (missing.isEmpty()) {
                        req.grant(req.resources)
                    } else {
                        pendingPermRequest = req
                        requestPermissions(missing.toTypedArray(), REQ_MEDIA)
                    }
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?, callback: android.webkit.GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, false, false)
            }
        }
    }

    private fun hideCustomView() {
        val v = customView ?: return
        rootFrame.removeView(v)
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MEDIA) {
            val req = pendingPermRequest
            pendingPermRequest = null
            if (req != null) {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    req.grant(req.resources)
                } else {
                    req.deny()
                }
            }
        }
    }

    // ====================== Dieu huong / Menu ======================

    private fun loadInput(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") || t.startsWith("file://") ||
                    t.startsWith("about:") -> t
            !t.contains(" ") && t.contains(".") -> "https://$t"
            else -> "https://www.google.com/search?q=" + Uri.encode(t)
        }
        active?.webView?.loadUrl(url)
    }

    private fun showMenu(anchor: View) {
        val tab = active
        val popup = PopupMenu(this, anchor)
        val m = popup.menu
        val bookmarked = tab != null && readList("bookmarks").any { it[1] == tab.url }
        m.add(0, 1, 0, "Tab mới")
        m.add(0, 2, 0, if (bookmarked) "★ Bỏ dấu trang" else "☆ Thêm dấu trang")
        m.add(0, 3, 0, "Dấu trang")
        m.add(0, 4, 0, "Lịch sử")
        m.add(0, 5, 0, "QR Remote Input")
        m.add(0, 6, 0, "Chế độ máy tính").apply {
            isCheckable = true
            isChecked = tab?.desktop == true
        }
        m.add(0, 7, 0, "Xoá cache và lịch sử")
        m.add(0, 9, 0, "QR Cam (xem webcam điện thoại)")
        m.add(0, 10, 0, "Chia đôi màn hình")
        m.add(0, 11, 0, "Chặn web đen / lừa đảo")
        m.add(0, 12, 0, "Mật khẩu đã lưu")
        m.add(0, 8, 0, "Đóng tab này")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> newTab(HOME)
                2 -> toggleBookmark()
                3 -> showList("Dấu trang", "bookmarks", false)
                4 -> showList("Lịch sử", "history", true)
                5 -> toggleQr()
                6 -> toggleDesktop()
                7 -> clearData()
                8 -> active?.let { closeTab(it) }
                9 -> toggleCam()
                10 -> showSplitMenu()
                11 -> showPhishingManager()
                12 -> showPasswordList()
            }
            true
        }
        popup.show()
    }

    private fun toggleDesktop() {
        val tab = active ?: return
        tab.desktop = !tab.desktop
        tab.webView.settings.userAgentString = if (tab.desktop) desktopUa else mobileUa
        tab.webView.reload()
    }

    private fun clearData() {
        active?.webView?.clearCache(true)
        prefs.edit().remove("history").apply()
        Toast.makeText(this, "Đã xoá cache và lịch sử", Toast.LENGTH_SHORT).show()
    }

    // ====================== Dau trang / Lich su ======================

    private fun readList(key: String): MutableList<Array<String>> {
        val out = mutableListOf<Array<String>>()
        try {
            val arr = JSONArray(prefs.getString(key, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(arrayOf(o.optString("title"), o.optString("url")))
            }
        } catch (e: Exception) {
            // du lieu hong - coi nhu rong
        }
        return out
    }

    private fun writeList(key: String, list: List<Array<String>>) {
        val arr = JSONArray()
        for (e in list) arr.put(JSONObject().put("title", e[0]).put("url", e[1]))
        prefs.edit().putString(key, arr.toString()).apply()
    }

    private fun addHistory(title: String, url: String) {
        val list = readList("history")
        if (list.isNotEmpty() && list[0][1] == url) return
        list.add(0, arrayOf(title, url))
        writeList("history", if (list.size > 300) list.subList(0, 300) else list)
    }

    private fun toggleBookmark() {
        val tab = active ?: return
        if (!tab.url.startsWith("http")) return
        val list = readList("bookmarks")
        val existing = list.indexOfFirst { it[1] == tab.url }
        if (existing >= 0) {
            list.removeAt(existing)
            Toast.makeText(this, "Đã bỏ dấu trang", Toast.LENGTH_SHORT).show()
        } else {
            list.add(0, arrayOf(tab.title, tab.url))
            Toast.makeText(this, "Đã thêm dấu trang", Toast.LENGTH_SHORT).show()
        }
        writeList("bookmarks", list)
    }

    private fun showList(title: String, key: String, clearable: Boolean) {
        val list = readList(key)
        if (list.isEmpty()) {
            Toast.makeText(this, "$title: chưa có mục nào", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = list.map { if (it[0].isNotBlank()) it[0] + "\n" + it[1] else it[1] }.toTypedArray()
        val b = AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels) { _, i -> active?.webView?.loadUrl(list[i][1]) }
            .setNegativeButton("Đóng", null)
        if (clearable) {
            b.setNeutralButton("Xoá hết") { _, _ -> prefs.edit().remove(key).apply() }
        }
        b.show()
    }

    // ====================== QR Remote Input ======================

    private fun toggleQr() {
        if (qrOverlay.visibility == View.VISIBLE) {
            qrOverlay.visibility = View.GONE
            return
        }
        ensureQrWeb()
        camOverlay.visibility = View.GONE
        qrOverlay.visibility = View.VISIBLE
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun ensureQrWeb() {
        if (qrWeb != null) return
        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.webViewClient = assetClient()
        w.addJavascriptInterface(QrBridge(), "Android")
        w.loadUrl("https://appassets.androidplatform.net/assets/qrremote.html")
        qrWeb = w
        qrOverlay.addView(w, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private inner class QrBridge {
        @JavascriptInterface
        fun onText(text: String) {
            runOnUiThread { injectText(text) }
        }

        @JavascriptInterface
        fun close() {
            runOnUiThread { qrOverlay.visibility = View.GONE }
        }

        @JavascriptInterface
        fun copy(text: String) {
            runOnUiThread {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("link", text))
            }
        }
    }

    // Giong desktop: chi giu chu/so/gach duoi/khoang trang.
    private fun sanitizeText(text: String): String =
        text.replace(Regex("[^\\p{L}\\p{N}_\\s]"), "")

    private fun buildInjectionScript(text: String): String {
        val quoted = JSONObject.quote(text)
        return "(function(t){var el=document.activeElement;if(!el)return false;" +
                "try{if(el.tagName==='INPUT'||el.tagName==='TEXTAREA'){" +
                "var s=el.selectionStart!=null?el.selectionStart:el.value.length;" +
                "var e=el.selectionEnd!=null?el.selectionEnd:el.value.length;" +
                "el.setRangeText(t,s,e,'end');" +
                "el.dispatchEvent(new Event('input',{bubbles:true}));" +
                "el.dispatchEvent(new Event('change',{bubbles:true}));return true;}" +
                "if(el.isContentEditable){document.execCommand('insertText',false,t);return true;}}" +
                "catch(x){}return false;})(" + quoted + ");"
    }

    private fun injectText(raw: String) {
        val clean = sanitizeText(raw)
        if (clean.isEmpty()) return
        val wv = typingWebView() ?: return
        wv.evaluateJavascript(buildInjectionScript(clean)) {
            // Giong desktop: tu dong nhan Enter sau khi chen.
            sendEnter(wv)
        }
    }

    private fun sendEnter(wv: WebView) {
        wv.requestFocus()
        wv.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        wv.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    // WebView dang giu con tro: pane chia doi neu dang focus, nguoc lai la tab hien tai.
    private fun typingWebView(): WebView? {
        val pane = splitTab?.webView
        if (splitService != null && pane != null && pane.hasFocus()) return pane
        return active?.webView
    }

    private val assetLoader: WebViewAssetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    private fun assetClient(): WebViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val u = request?.url ?: return null
            return assetLoader.shouldInterceptRequest(u)
        }
    }

    // ====================== QR Cam ======================

    private val recordings = HashMap<String, FileOutputStream>()
    private var recSeq = 0

    private fun camDir(): File {
        val base = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        val d = File(base, "QRCam")
        d.mkdirs()
        return d
    }

    private fun toggleCam() {
        if (camOverlay.visibility == View.VISIBLE) {
            camOverlay.visibility = View.GONE
            return
        }
        prefs.edit().putBoolean("camUsed", true).apply()
        ensureCamWeb()
        qrOverlay.visibility = View.GONE
        camOverlay.visibility = View.VISIBLE
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun ensureCamWeb() {
        if (camWeb != null) return
        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.webViewClient = assetClient()
        w.webChromeClient = WebChromeClient()
        w.addJavascriptInterface(CamBridge(), "AndroidCam")
        w.loadUrl("https://appassets.androidplatform.net/assets/qrcam.html")
        camWeb = w
        camOverlay.addView(w, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private inner class CamBridge {
        @JavascriptInterface
        fun folder(): String = camDir().absolutePath

        @JavascriptInterface
        fun startFile(name: String): String {
            return try {
                val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                synchronized(recordings) {
                    val id = "rec" + (++recSeq)
                    recordings[id] = FileOutputStream(File(camDir(), safe))
                    id
                }
            } catch (e: Exception) {
                ""
            }
        }

        @JavascriptInterface
        fun appendChunk(id: String, b64: String): Boolean {
            return try {
                synchronized(recordings) {
                    val o = recordings[id]
                    if (o == null) {
                        false
                    } else {
                        o.write(Base64.decode(b64, Base64.DEFAULT))
                        true
                    }
                }
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun finishFile(id: String): Boolean {
            return try {
                synchronized(recordings) {
                    val o = recordings.remove(id)
                    if (o == null) {
                        false
                    } else {
                        o.flush()
                        o.close()
                        true
                    }
                }
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun textEdit(del: Int, ins: String) {
            runOnUiThread { applyTextEdit(del, ins) }
        }

        @JavascriptInterface
        fun keepAwake(on: Boolean) {
            runOnUiThread {
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        @JavascriptInterface
        fun close() {
            runOnUiThread { camOverlay.visibility = View.GONE }
        }
    }

    // Dien thoai gui {del, ins}: xoa `del` ky tu truoc con tro roi chen `ins`.
    private fun applyTextEdit(delRaw: Int, ins: String) {
        val wv = typingWebView() ?: return
        val del = delRaw.coerceIn(0, 1000000)
        if (del == 0 && ins.isEmpty()) return
        if (ins == "\n" && del == 0) {
            sendEnter(wv)
            return
        }
        wv.evaluateJavascript("(" + TEXT_EDIT_FN + ")(" + del + "," + JSONObject.quote(ins) + ");", null)
    }

    // ====================== Chia doi man hinh ======================

    private val splitUrls = linkedMapOf(
        "Telegram" to "https://web.telegram.org/k/",
        "Zalo" to "https://chat.zalo.me/",
        "Facebook" to "https://www.facebook.com/?locale=vi_VN",
        "YouTube" to "https://www.youtube.com/?hl=vi&gl=VN&persist_hl=1",
        "Google" to "https://www.google.com/?hl=vi"
    )
    private var splitService: String? = null
    private var splitTab: Tab? = null

    // Doc man hinh: pane o tren (3/10). Ngang man hinh: pane ben trai (2/7), giong ban may tinh.
    private fun applySplitLayout() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        splitRoot.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        splitPaneHolder.layoutParams =
            if (landscape) LinearLayout.LayoutParams(0, MATCH, 2f) else LinearLayout.LayoutParams(MATCH, 0, 3f)
        content.layoutParams =
            if (landscape) LinearLayout.LayoutParams(0, MATCH, 5f) else LinearLayout.LayoutParams(MATCH, 0, 7f)
        splitPaneHolder.visibility = if (splitService != null) View.VISIBLE else View.GONE
    }

    private fun showSplitMenu() {
        val names = splitUrls.keys.toMutableList()
        if (splitService != null) names.add("Tắt chia đôi")
        AlertDialog.Builder(this)
            .setTitle("Chia đôi màn hình")
            .setItems(names.toTypedArray()) { _, i ->
                val n = names[i]
                if (n == "Tắt chia đôi") exitSplit() else toggleSplit(n)
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun toggleSplit(service: String) {
        if (splitService == service) exitSplit() else enterSplit(service)
    }

    private fun enterSplit(service: String) {
        val url = splitUrls[service] ?: return
        val existing = splitTab
        if (existing == null) {
            val wv = createConfiguredWebView()
            val t = Tab(wv, TextView(this))
            setupClients(t)
            splitTab = t
            splitPaneHolder.addView(wv, FrameLayout.LayoutParams(MATCH, MATCH))
            wv.loadUrl(url)
        } else if (splitService != service) {
            existing.webView.loadUrl(url)
        }
        splitService = service
        applySplitLayout()
    }

    // Giu nguyen WebView cua pane (khong huy) de khong mat phien dang nhap.
    private fun exitSplit() {
        splitService = null
        applySplitLayout()
    }

    // ====================== Chan web den / lua dao ======================

    private val phishingAllow = mutableSetOf<String>()
    private var phishingCache: List<String>? = null

    private fun phishingList(): List<String> {
        phishingCache?.let { return it }
        val out = mutableListOf<String>()
        try {
            val a = JSONArray(prefs.getString("phishing", "[]"))
            for (i in 0 until a.length()) out.add(a.getString(i))
        } catch (e: Exception) {
            // du lieu hong - coi nhu rong
        }
        phishingCache = out
        return out
    }

    private fun savePhishing(list: List<String>) {
        val a = JSONArray()
        for (d in list) a.put(d)
        prefs.edit().putString("phishing", a.toString()).apply()
        phishingCache = list.toList()
    }

    private fun isPhishingHost(host: String): Boolean {
        val h = host.lowercase()
        return phishingList().any { h == it || h.endsWith(".$it") }
    }

    private fun phishingResponse(host: String, url: String): WebResourceResponse {
        val h = TextUtils.htmlEncode(host)
        val allow = TextUtils.htmlEncode("webandr-allow://go?h=" + Uri.encode(host) + "&u=" + Uri.encode(url))
        val html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Cảnh báo</title></head>" +
                "<body style=\"font-family:sans-serif;background:#3a0f0f;color:#fff;padding:24px;text-align:center\">" +
                "<div style=\"font-size:56px\">⚠️</div><h2>Cảnh báo web đen / lừa đảo</h2>" +
                "<p>\"" + h + "\" nằm trong danh sách chặn của bạn. Tiếp tục truy cập có thể khiến bạn lộ " +
                "thông tin cá nhân, tài khoản ngân hàng hoặc bị lừa đảo.</p>" +
                "<p><a href=\"webandr-back://x\" style=\"display:inline-block;padding:12px 24px;background:#fff;" +
                "color:#3a0f0f;border-radius:24px;text-decoration:none;font-weight:bold\">Quay lại an toàn</a></p>" +
                "<p><a href=\"" + allow + "\" style=\"color:#ffb3b3;font-size:13px\">Tôi hiểu rủi ro, vẫn tiếp tục</a></p>" +
                "</body></html>"
        return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
    }

    private fun showPhishingManager() {
        val list = phishingList()
        val b = AlertDialog.Builder(this).setTitle("Chặn web đen (" + list.size + ")")
        if (list.isNotEmpty()) {
            b.setItems(list.toTypedArray()) { _, i -> confirmDeletePhishing(list[i]) }
        } else {
            b.setMessage("Danh sách đang trống. Thêm tên miền (vd: example.com) — truy cập tên miền đó và các tên miền con sẽ bị cảnh báo.")
        }
        b.setPositiveButton("Thêm") { _, _ -> promptAddPhishing() }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun promptAddPhishing() {
        val input = EditText(this).apply {
            hint = "example.com"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Thêm web vào danh sách chặn")
            .setView(input)
            .setPositiveButton("Thêm") { _, _ ->
                val d = input.text.toString().trim().lowercase()
                    .removePrefix("http://").removePrefix("https://")
                    .substringBefore("/").removePrefix("www.")
                if (d.isNotEmpty()) {
                    val list = phishingList().toMutableList()
                    if (!list.contains(d)) list.add(d)
                    savePhishing(list)
                    Toast.makeText(this, "Đã thêm: $d", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun confirmDeletePhishing(domain: String) {
        AlertDialog.Builder(this)
            .setTitle("Xoá khỏi danh sách chặn?")
            .setMessage(domain)
            .setPositiveButton("Xoá") { _, _ ->
                savePhishing(phishingList().filter { it != domain })
                phishingAllow.remove(domain)
            }
            .setNegativeButton("Giữ lại", null)
            .show()
    }

    // ====================== Mat khau da luu ======================

    private val docStartScriptSupported: Boolean by lazy {
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    }

    // Luu ma hoa bang Android Keystore (EncryptedSharedPreferences).
    private val securePrefs: SharedPreferences? by lazy {
        try {
            val key = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                this, "webandr_secure", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun loadPw(): MutableList<JSONObject> {
        val out = mutableListOf<JSONObject>()
        try {
            val a = JSONArray(securePrefs?.getString("list", "[]") ?: "[]")
            for (i in 0 until a.length()) out.add(a.getJSONObject(i))
        } catch (e: Exception) {
            // du lieu hong - coi nhu rong
        }
        return out
    }

    private fun savePwList(list: List<JSONObject>) {
        val a = JSONArray()
        for (o in list) a.put(o)
        securePrefs?.edit()?.putString("list", a.toString())?.apply()
    }

    private fun attachPasswordBridge(wv: WebView) {
        if (securePrefs == null) return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(wv, "AndroidPw", setOf("*")) { _, message, sourceOrigin, isMainFrame, reply ->
                if (isMainFrame) handlePwMessage(message.data, sourceOrigin.toString(), reply)
            }
        }
        if (docStartScriptSupported) {
            WebViewCompat.addDocumentStartJavaScript(wv, PW_JS, setOf("*"))
        }
    }

    private fun handlePwMessage(data: String?, origin: String, reply: JavaScriptReplyProxy) {
        if (data == null || !origin.startsWith("http")) return
        val m = try { JSONObject(data) } catch (e: Exception) { return }
        when (m.optString("t")) {
            "save" -> {
                val u = m.optString("u")
                val p = m.optString("p")
                if (p.isNotEmpty()) runOnUiThread { onPasswordCaptured(origin, u, p) }
            }
            "ask" -> {
                val match = loadPw().firstOrNull {
                    it.optString("origin") == origin && !it.optBoolean("neverSave") &&
                            it.optString("password").isNotEmpty()
                }
                if (match != null) {
                    reply.postMessage(
                        JSONObject().put("t", "fill")
                            .put("u", match.optString("username"))
                            .put("p", match.optString("password")).toString()
                    )
                }
            }
        }
    }

    private fun onPasswordCaptured(origin: String, username: String, password: String) {
        val list = loadPw()
        if (list.any { it.optString("origin") == origin && it.optBoolean("neverSave") }) return
        if (list.any {
                it.optString("origin") == origin && it.optString("username") == username &&
                        it.optString("password") == password
            }) return
        AlertDialog.Builder(this)
            .setTitle("Lưu mật khẩu?")
            .setMessage(origin + if (username.isNotEmpty()) "\n" + username else "")
            .setPositiveButton("Lưu") { _, _ ->
                val l = loadPw().filter {
                    !(it.optString("origin") == origin && it.optString("username") == username &&
                            !it.optBoolean("neverSave"))
                }.toMutableList()
                l.add(
                    JSONObject().put("origin", origin).put("username", username)
                        .put("password", password).put("savedAt", System.currentTimeMillis())
                )
                savePwList(l)
            }
            .setNegativeButton("Không", null)
            .setNeutralButton("Không bao giờ") { _, _ ->
                val l = loadPw()
                l.add(
                    JSONObject().put("origin", origin).put("username", "")
                        .put("password", "").put("neverSave", true)
                )
                savePwList(l)
            }
            .show()
    }

    private fun showPasswordList() {
        if (securePrefs == null) {
            Toast.makeText(this, "Thiết bị không hỗ trợ lưu mật khẩu an toàn", Toast.LENGTH_LONG).show()
            return
        }
        val list = loadPw()
        if (list.isEmpty()) {
            Toast.makeText(this, "Chưa có mật khẩu nào được lưu", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = list.map {
            it.optString("origin") + if (it.optBoolean("neverSave")) "  (không bao giờ lưu)"
            else if (it.optString("username").isNotEmpty()) "  •  " + it.optString("username") else ""
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Mật khẩu đã lưu (chạm để xoá)")
            .setItems(labels) { _, i ->
                AlertDialog.Builder(this)
                    .setTitle("Xoá mục này?")
                    .setMessage(labels[i])
                    .setPositiveButton("Xoá") { _, _ ->
                        val l = loadPw()
                        if (i < l.size) l.removeAt(i)
                        savePwList(l)
                    }
                    .setNegativeButton("Giữ lại", null)
                    .show()
            }
            .setNegativeButton("Đóng", null)
            .show()
    }
}

// Script chay trong moi trang: bat form dang nhap + dien san mat khau da luu.
private val PW_JS = """
(function(){
 if(window.__wbPw||!window.AndroidPw)return; window.__wbPw=true;
 function findUser(form,pw){var c=form.querySelectorAll('input[type="text"],input[type="email"],input[autocomplete="username"],input:not([type])');for(var i=0;i<c.length;i++){if(c[i]!==pw&&c[i].value)return c[i];}return null;}
 document.addEventListener('submit',function(e){try{var f=e.target;if(!(f instanceof HTMLFormElement))return;var pw=f.querySelector('input[type="password"]');if(!pw||!pw.value)return;var u=findUser(f,pw);AndroidPw.postMessage(JSON.stringify({t:'save',u:u?u.value:'',p:pw.value}));}catch(x){}},true);
 function fill(u,p){var pw=document.querySelector('input[type="password"]');if(!pw)return;pw.value=p;pw.dispatchEvent(new Event('input',{bubbles:true}));pw.dispatchEvent(new Event('change',{bubbles:true}));
  if(u){var scope=pw.closest('form')||document;var ui=scope.querySelector('input[type="text"],input[type="email"],input[autocomplete="username"],input:not([type])');if(ui){ui.value=u;ui.dispatchEvent(new Event('input',{bubbles:true}));ui.dispatchEvent(new Event('change',{bubbles:true}));}}}
 AndroidPw.onmessage=function(ev){try{var m=JSON.parse(ev.data);if(m.t==='fill')fill(m.u,m.p);}catch(x){}};
 var asked=false;
 function check(){try{var has=!!document.querySelector('input[type="password"]');if(has&&!asked){asked=true;AndroidPw.postMessage(JSON.stringify({t:'ask'}));}else if(!has){asked=false;}}catch(x){}}
 try{var ob=new MutationObserver(check);function start(){if(document.documentElement){ob.observe(document.documentElement,{childList:true,subtree:true});check();}}
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',start);else start();}catch(x){}
})();
"""

// Xoa `del` ky tu truoc con tro roi chen `ins` (nhap chu tu dien thoai qua QR Cam).
private val TEXT_EDIT_FN = """
function(del, ins) {
  var el = document.activeElement;
  try {
    while (el && el.tagName === 'IFRAME' && el.contentDocument && el.contentDocument.activeElement) {
      el = el.contentDocument.activeElement;
    }
  } catch (e) {}
  if (!el) return false;
  var doc = el.ownerDocument;
  function stepBack(str, idx, n) {
    while (n > 0 && idx > 0) {
      idx--;
      var c = str.charCodeAt(idx);
      if (c >= 0xDC00 && c <= 0xDFFF && idx > 0) {
        var h = str.charCodeAt(idx - 1);
        if (h >= 0xD800 && h <= 0xDBFF) idx--;
      }
      n--;
    }
    return idx;
  }
  function fire(target) {
    try {
      target.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: ins ? 'insertText' : 'deleteContentBackward', data: ins || null }));
    } catch (e) {
      target.dispatchEvent(new Event('input', { bubbles: true }));
    }
  }
  if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
    var v = el.value;
    var s = null, e2 = null;
    try { s = el.selectionStart; e2 = el.selectionEnd; } catch (err) {}
    if (s === null || e2 === null) { s = e2 = v.length; }
    var start = (e2 === s) ? stepBack(v, s, del) : s;
    try {
      el.setRangeText(ins, start, e2, 'end');
    } catch (err) {
      var proto = el.tagName === 'INPUT' ? HTMLInputElement.prototype : HTMLTextAreaElement.prototype;
      var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
      setter.call(el, v.slice(0, start) + ins + v.slice(e2));
    }
    fire(el);
    return true;
  }
  if (el.isContentEditable) {
    for (var k = 0; k < del; k++) doc.execCommand('delete');
    if (ins) doc.execCommand('insertText', false, ins);
    return true;
  }
  return false;
}
"""
