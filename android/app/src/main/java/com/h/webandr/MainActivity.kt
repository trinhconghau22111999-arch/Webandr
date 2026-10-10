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
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
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
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

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
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> hideCustomView()
            qrOverlay.visibility == View.VISIBLE -> qrOverlay.visibility = View.GONE
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
        col.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))

        qrOverlay = FrameLayout(this).apply {
            setBackgroundColor(0xFF1A1030.toInt())
            visibility = View.GONE
        }
        content.addView(qrOverlay, FrameLayout.LayoutParams(MATCH, MATCH))

        rootFrame.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(rootFrame)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBar.windowToken, 0)
        urlBar.clearFocus()
    }

    // ====================== Tab ======================

    @SuppressLint("SetJavaScriptEnabled")
    private fun newTab(url: String?, select: Boolean = true): Tab {
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
                val host = request?.url?.host ?: return null
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
        qrOverlay.visibility = View.VISIBLE
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun ensureQrWeb() {
        if (qrWeb != null) return
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val u = request?.url ?: return null
                return loader.shouldInterceptRequest(u)
            }
        }
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
        val wv = active?.webView ?: return
        wv.evaluateJavascript(buildInjectionScript(clean)) {
            // Giong desktop: tu dong nhan Enter sau khi chen.
            wv.requestFocus()
            wv.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            wv.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }
}
