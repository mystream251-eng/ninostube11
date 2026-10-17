package com.example

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity() {

  private var webView: WebView? = null
  private lateinit var videosDir: File
  private lateinit var thumbsDir: File

  inner class AndroidBridge(private val context: Context) {
    @JavascriptInterface
    fun queueDownload(id: String, title: String) {
      try {
        val template = "https://apipy.ssyoutube-downloader.com/youtube/download?url=https://www.youtube.com/watch?v=$id&type=video&height=360"
        val request = DownloadManager.Request(Uri.parse(template)).apply {
          setTitle(title.ifEmpty { id })
          setDescription("Descargando video con NinosTube...")
          setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
          setDestinationUri(Uri.fromFile(File(videosDir, "$id.mp4")))
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        dm?.enqueue(request)
      } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun getVideos(): String {
      val files = videosDir.listFiles { file -> file.extension.lowercase() == "mp4" } ?: emptyArray()
      val jsonArray = JSONArray()
      for (f in files) {
        val id = f.nameWithoutExtension
        val obj = JSONObject().apply {
          put("id", id)
          put("title", id)
          put("size", f.length())
        }
        jsonArray.put(obj)
      }
      return jsonArray.toString()
    }

    @JavascriptInterface
    fun deleteVideo(id: String): Boolean {
      val videoFile = File(videosDir, "$id.mp4")
      val thumbFile = File(thumbsDir, "$id.jpg")
      if (thumbFile.exists()) thumbFile.delete()
      return if (videoFile.exists()) videoFile.delete() else false
    }
  }

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    videosDir = File(filesDir, "videos").apply { if (!exists()) mkdirs() }
    thumbsDir = File(filesDir, "thumbs").apply { if (!exists()) mkdirs() }

    // Pre-crear carpetas de caché para prevenir advertencias de inicialización de Chromium
    try {
      File(cacheDir, "WebView/Default/HTTP Cache/Code Cache/js").mkdirs()
      File(cacheDir, "WebView/Default/HTTP Cache/Code Cache/wasm").mkdirs()
    } catch (_: Exception) {}

    val assetsHandler = WebViewAssetLoader.AssetsPathHandler(this)
    val safeHandler = WebViewAssetLoader.PathHandler { rawPath ->
      val path = rawPath.trimStart('/')
      val safePath = if (path.isEmpty()) "index.html" else path
      try {
        assetsHandler.handle(safePath)
      } catch (_: Exception) {
        null
      }
    }

    val assetLoader = WebViewAssetLoader.Builder()
      .setDomain("appassets.androidplatform.net")
      .addPathHandler("/assets/", safeHandler)
      .build()

    fun handleRequest(url: Uri): WebResourceResponse? {
      if (url.host == "appassets.androidplatform.net") {
        val path = url.path ?: ""
        if (path.isEmpty() || path == "/" || path == "/assets" || path == "/assets/") {
          return assetLoader.shouldInterceptRequest(Uri.parse("https://appassets.androidplatform.net/assets/index.html"))
        }
        if (path.startsWith("/videos/")) {
          val filename = path.removePrefix("/videos/")
          val file = File(videosDir, filename)
          if (file.exists()) {
            return WebResourceResponse("video/mp4", null, file.inputStream())
          }
        }
        if (path.startsWith("/thumbs/")) {
          val filename = path.removePrefix("/thumbs/")
          val file = File(thumbsDir, filename)
          if (file.exists()) {
            return WebResourceResponse("image/jpeg", null, file.inputStream())
          }
        }
      }
      return assetLoader.shouldInterceptRequest(url)
    }

    if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) {
      try {
        ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : ServiceWorkerClientCompat() {
          override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
            return handleRequest(request.url)
          }
        })
      } catch (_: Exception) {
        // Ignorado si no se soporta en este runtime
      }
    }

    webView = WebView(this).apply {
      layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
      )
      setBackgroundColor(android.graphics.Color.BLACK)

      settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        mediaPlaybackRequiresUserGesture = false
        allowFileAccess = false
        allowContentAccess = false
        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        useWideViewPort = true
        loadWithOverviewMode = true
      }

      addJavascriptInterface(AndroidBridge(this@MainActivity), "Android")

      webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(
          view: WebView,
          request: WebResourceRequest
        ): WebResourceResponse? {
          return handleRequest(request.url)
        }
      }

      webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
          return super.onConsoleMessage(consoleMessage)
        }
      }

      setDownloadListener { downloadUrl, userAgent, contentDisposition, mimetype, _ ->
        try {
          val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
            setMimeType(mimetype)
            addRequestHeader("User-Agent", userAgent)
            addRequestHeader("Cookie", CookieManager.getInstance().getCookie(downloadUrl))
            setDescription("Descargando video con NinosTube...")
            val filename = URLUtil.guessFileName(downloadUrl, contentDisposition, mimetype)
            setTitle(filename)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
          }
          val dm = getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
          dm?.enqueue(request)
          Toast.makeText(this@MainActivity, "Iniciando descarga...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
          Toast.makeText(this@MainActivity, "Error en descarga: ${e.message}", Toast.LENGTH_LONG).show()
        }
      }

      val headers = mapOf("Referer" to "https://appassets.androidplatform.net/")
      loadUrl("https://appassets.androidplatform.net/assets/index.html", headers)
    }

    setContentView(webView)
  }

  override fun onDestroy() {
    webView?.destroy()
    webView = null
    super.onDestroy()
  }
}
