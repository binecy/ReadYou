package me.ash.reader.ui.component.webview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import me.ash.reader.infrastructure.preference.ReadingFontsPreference
import me.ash.reader.ui.page.adaptive.ArticleListReaderViewModel

object WebViewLayout {

    @SuppressLint("SetJavaScriptEnabled")
    fun get(
        context: Context,
        readingFontsPreference: ReadingFontsPreference,
        webViewClient: WebViewClient,
        onImageClick: ((imgUrl: String, altText: String) -> Unit)? = null,
        viewModel: ArticleListReaderViewModel? = null,
    ) =
        //  WebView(context).apply {
        CustomMenuWebView(context,  viewModel, webViewClient).apply {
//            CustomMenuWebView里面已经设置了webViewClient
//            this.webViewClient = webViewClient

            scrollBarSize = 0
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = true
            setBackgroundColor(Color.TRANSPARENT)
            with(this.settings) {
                standardFontFamily =
                    when (readingFontsPreference) {
                        ReadingFontsPreference.Cursive -> "cursive"
                        ReadingFontsPreference.Monospace -> "monospace"
                        ReadingFontsPreference.SansSerif -> "sans-serif"
                        ReadingFontsPreference.Serif -> "serif"
                        ReadingFontsPreference.GoogleSans -> {
                            "sans-serif"
                        }
                        ReadingFontsPreference.External -> {
                            allowFileAccess = true
                            "sans-serif"
                        }

                        else -> "sans-serif"
                    }
                domStorageEnabled = true
                javaScriptEnabled = true
                addJavascriptInterface(
                    object : JavaScriptInterface {
                        @JavascriptInterface
                        override fun onImgTagClick(imgUrl: String?, alt: String?) {
                            if (onImageClick != null && imgUrl != null) {
                                onImageClick.invoke(imgUrl, alt ?: "")
                            }
                        }
                    },
                    JavaScriptInterface.NAME,
                )
                setSupportZoom(false)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    isAlgorithmicDarkeningAllowed = true
                }
            }

            // 支持bilibili视频最大化播放
            webChromeClient = object : WebChromeClient() {
                private var fullscreenView: View? = null
                private var fullscreenContainer:  ViewGroup? = null
                private var customViewCallback: CustomViewCallback? = null
                private var originalOrientation = 0

                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    if (fullscreenView != null) {
                        callback.onCustomViewHidden()
                        return
                    }

                    val activity = context as android.app.Activity
                    fullscreenView = view
                    customViewCallback = callback
                    originalOrientation = activity.requestedOrientation

                    // 找到界面最顶层容器
                    fullscreenContainer = activity.window.decorView as ViewGroup
                    fullscreenContainer?.addView(
                        view,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )

                    // 强制横屏 + 全屏
//                    activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
                }

                override fun onHideCustomView() {
                    val activity = context as android.app.Activity

                    // 从顶层移除全屏View，不破坏原有布局
                    fullscreenContainer?.removeView(fullscreenView)
                    fullscreenView = null

                    // 恢复方向 + 退出全屏
//                    activity.requestedOrientation = originalOrientation
                    activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

                    customViewCallback?.onCustomViewHidden()
                    customViewCallback = null
                }
            }
        }
}
