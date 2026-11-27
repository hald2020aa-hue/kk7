package com.misterdownloadmanager.android

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.WindowManager
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast

/** A bounded, native WebView browser. It does not replace MDM's downloader. */
class BrowserPanel(
    private val host: Activity,
    private val arabic: Boolean,
    private val handoff: (String, Boolean) -> Boolean,
) {
    private val bg = Color.rgb(8, 15, 25)
    private val surface = Color.rgb(20, 35, 54)
    private val cyan = Color.rgb(0, 229, 255)
    private val dialog = Dialog(host, android.R.style.Theme_Material_NoActionBar)
    private val tabs = mutableListOf<WebView>()
    private var selected = -1
    private val root = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = if (arabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        setBackgroundColor(bg)
    }
    private val strip = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
    private val frame = FrameLayout(host)
    private val address = EditText(host).apply {
        isSingleLine = true
        textDirection = View.TEXT_DIRECTION_LTR
        setTextColor(Color.WHITE)
        setHintTextColor(Color.LTGRAY)
        hint = tr("Address or search", "عنوان أو بحث")
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        imeOptions = EditorInfo.IME_ACTION_GO
        setPadding(dp(12), 0, dp(12), 0)
        background = shape(surface, cyan)
    }
    private val progress = ProgressBar(host, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
    private fun tr(en: String, ar: String) = if (arabic) ar else en
    private fun dp(n: Int): Int = (n * host.resources.displayMetrics.density).toInt()
    private fun shape(fill: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(9).toFloat(); setStroke(dp(1), stroke)
    }
    private fun button(label: String, action: () -> Unit) = Button(host).apply {
        text = label; isAllCaps = false; setTextColor(Color.WHITE)
        background = shape(surface, cyan); minHeight = dp(48); minimumHeight = dp(48)
        setOnClickListener { action() }
    }
    private fun row() = LinearLayout(host).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), dp(4), dp(4), dp(4))
    }
    private fun scrollRow(row: LinearLayout) = HorizontalScrollView(host).apply {
        isHorizontalScrollBarEnabled = false; addView(row)
    }
    private fun current() = tabs.getOrNull(selected)
    private fun allowed(url: String): Boolean {
        val uri = Uri.parse(url)
        return (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) && !uri.host.isNullOrBlank() && uri.userInfo == null
    }
    fun show() {
        val nav = row().apply {
            addView(button(tr("Back", "رجوع")) { current()?.takeIf { it.canGoBack() }?.goBack() })
            addView(button(tr("Forward", "تقدم")) { current()?.takeIf { it.canGoForward() }?.goForward() })
            addView(button(tr("Reload", "تحديث")) { current()?.reload() })
            addView(button(tr("Close", "إغلاق")) { dialog.dismiss() })
        }
        root.addView(scrollRow(nav))
        root.addView(scrollRow(strip))
        root.addView(row().apply {
            addView(address, LinearLayout.LayoutParams(0, dp(52), 1f))
            addView(button(tr("Go", "اذهب")) { navigate() })
        })
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(scrollRow(row().apply {
            addView(button(tr("Send link", "إرسال الرابط")) { current()?.url?.let { offer(it, false) } })
            addView(button(tr("Video page", "تنزيل فيديو")) { current()?.url?.let { offer(it, true) } })
            addView(button(tr("Bookmark", "حفظ مفضلة")) { bookmark() })
            addView(button(tr("Bookmarks", "المفضلة")) { bookmarks() })
            addView(button(tr("History", "السجل")) { history() })
        }))
        address.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO) { navigate(); true } else false
        }
        dialog.setContentView(root)
        dialog.setOnDismissListener { releaseTabs() }
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        addTab()
    }
    private fun navigate() {
        val input = address.text.toString().trim()
        if (input.isBlank()) return
        val url = when {
            allowed(input) -> input
            input.contains("://") || input.startsWith("javascript:", true) || input.startsWith("file:", true) || input.startsWith("data:", true) || input.startsWith("content:", true) -> { toast(tr("Only web addresses are supported", "يدعم عناوين الويب فقط")); return }
            !input.contains(' ') && input.contains('.') -> "https://$input"
            else -> "https://duckduckgo.com/?q=" + Uri.encode(input)
        }
        if (allowed(url)) current()?.loadUrl(url)
    }
    private fun addTab() {
        if (tabs.size >= 6) { toast(tr("Six tabs maximum", "الحد الأقصى ستة تبويبات")); return }
        val web = WebView(host).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.safeBrowsingEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    if (request.isForMainFrame && request.url.scheme.equals("magnet", true)) offer(url, false)
                    return !allowed(url)
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (view == current()) { address.setText(if (url == "about:blank") "" else url); rebuildTabs() }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, value: Int) {
                    if (view == current()) this@BrowserPanel.progress.progress = value
                }
            }
            setDownloadListener { url, _, _, _, _ -> offer(url, false) }
        }
        tabs += web
        selectTab(tabs.lastIndex)
        address.requestFocus()
    }
    private fun selectTab(index: Int) {
        if (index !in tabs.indices) return
        current()?.onPause()
        frame.removeAllViews()
        selected = index
        val web = tabs[index]
        frame.addView(web, FrameLayout.LayoutParams(-1, -1))
        web.onResume()
        address.setText(web.url?.takeUnless { it == "about:blank" }.orEmpty())
        rebuildTabs()
    }
    private fun rebuildTabs() {
        strip.removeAllViews()
        tabs.forEachIndexed { index, web ->
            strip.addView(button((web.title?.takeIf { it.isNotBlank() } ?: tr("Tab", "تبويب")).take(18)) { selectTab(index) }.apply {
                if (index == selected) {
                    background = shape(Color.rgb(21, 58, 76), cyan)
                    contentDescription = text.toString() + " · " + tr("Selected", "محدد")
                }
            })
        }
        strip.addView(button("+") { addTab() })
        strip.addView(button("×") {
            if (tabs.size > 1 && selected in tabs.indices) {
                frame.removeAllViews()
                val closed = tabs.removeAt(selected)
                closed.stopLoading(); closed.destroy()
                selected = -1; selectTab(0)
            } else { dialog.dismiss() }
        })
    }
    private fun offer(url: String, video: Boolean) {
        if (!allowed(url) && (video || !url.startsWith("magnet:?", ignoreCase = true))) {
            toast(tr("This link cannot be handed to MDM", "لا يمكن إرسال هذا الرابط إلى MDM")); return
        }
        AlertDialog.Builder(host)
            .setTitle(tr("Send to MDM", "إرسال إلى MDM"))
            .setMessage(tr("Open in MDM? Protected or signed-in downloads may need separate authentication.", "فتح في MDM؟ قد تحتاج الملفات المحمية أو التي تتطلب تسجيل الدخول إلى مصادقة منفصلة.") + "\n\n" + url)
            .setNegativeButton(tr("Cancel", "إلغاء"), null)
            .setPositiveButton(tr("Open", "فتح")) { _, _ ->
                if (handoff(url, video)) dialog.dismiss() else toast(tr("MDM is not ready", "MDM غير جاهز"))
            }.show()
    }
    private fun bookmark() {
        val url = current()?.url?.takeIf(::allowed) ?: return
        val prefs = host.getSharedPreferences("mdm-browser", android.content.Context.MODE_PRIVATE)
        val saved = prefs.getStringSet("bookmarks", emptySet()).orEmpty().toMutableSet()
        if (saved.size >= 200 && !saved.contains(url)) { toast(tr("Bookmark limit reached", "بلغت حد المفضلة")); return }
        saved.add(url)
        prefs.edit().putStringSet("bookmarks", saved).apply()
        toast(tr("Saved", "تم الحفظ"))
    }
    private fun bookmarks() {
        val prefs = host.getSharedPreferences("mdm-browser", android.content.Context.MODE_PRIVATE)
        val list = prefs.getStringSet("bookmarks", emptySet()).orEmpty().sorted().toTypedArray()
        AlertDialog.Builder(host).setTitle(tr("Bookmarks", "المفضلة"))
            .setItems(list) { _, index -> if (allowed(list[index])) current()?.loadUrl(list[index]) }
            .setNegativeButton(tr("Close", "إغلاق"), null).show()
    }
    private fun history() {
        val web = current() ?: return
        val history = web.copyBackForwardList()
        val urls = Array(history.size) { history.getItemAtIndex(it).url.orEmpty() }
        AlertDialog.Builder(host).setTitle(tr("History", "السجل"))
            .setItems(urls) { _, index -> web.goBackOrForward(index - history.currentIndex) }
            .setNegativeButton(tr("Close", "إغلاق"), null).show()
    }
    private fun toast(message: String) { Toast.makeText(host, message, Toast.LENGTH_SHORT).show() }
    private fun releaseTabs() {
        frame.removeAllViews()
        tabs.forEach { it.stopLoading(); it.destroy() }
        tabs.clear(); selected = -1
    }
    fun destroy() { if (dialog.isShowing) dialog.dismiss() else releaseTabs() }
}
