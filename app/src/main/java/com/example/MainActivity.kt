package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.RenderProcessGoneDetail
import androidx.activity.ComponentActivity
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

class MainActivity : ComponentActivity() {

  companion object {
    private const val TAG = "NinosDL"
    private const val CHROME_USER_AGENT =
      "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private const val TIMEOUT_MS = 180_000 // 3 minutos
    private const val MIN_FILE_SIZE = 200 * 1024L // 200 KB
  }

  data class DownloadTask(val id: String, val title: String, val addedTime: Long = System.currentTimeMillis())
  data class DownloadHistoryItem(val id: String, val title: String, val status: String, val reason: String, val timestamp: Long)

  private var webView: WebView? = null
  private lateinit var videosDir: File
  private lateinit var thumbsDir: File

  private val taskQueue = LinkedBlockingQueue<DownloadTask>()
  private val queuedIds = ConcurrentHashMap.newKeySet<String>()
  private val historyList = LinkedList<DownloadHistoryItem>()
  private val viewsMap = ConcurrentHashMap<String, Int>()

  private val downloadExecutor = Executors.newSingleThreadExecutor()

  @Volatile private var currentTask: DownloadTask? = null
  @Volatile private var currentBytes: Long = 0L
  @Volatile private var currentTotalBytes: Long = -1L
  @Volatile private var currentProgress: Int = -1

  inner class AndroidBridge(private val context: Context) {

    @JavascriptInterface
    fun queueDownload(id: String, title: String) {
      if (id.isBlank()) return
      val finalFile = File(videosDir, "$id.mp4")
      if (finalFile.exists() && finalFile.length() > MIN_FILE_SIZE) {
        Log.d(TAG, "Video ya descargado anteriormente: id=$id")
        return
      }
      if (queuedIds.contains(id) || currentTask?.id == id) {
        Log.d(TAG, "Video ya está en cola o descargándose: id=$id")
        return
      }

      val task = DownloadTask(id, title.ifEmpty { id })
      queuedIds.add(id)
      taskQueue.add(task)
      Log.d(TAG, "Encolando descarga id=$id, title=$title. Posición en cola: ${taskQueue.size}")

      downloadExecutor.execute {
        processQueue()
      }
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
      viewsMap.remove(id)
      val deleted = if (videoFile.exists()) videoFile.delete() else false
      Log.d(TAG, "Video eliminado: id=$id, éxito=$deleted")
      return deleted
    }

    @JavascriptInterface
    fun updateViews(id: String, views: Int) {
      viewsMap[id] = views
    }

    @JavascriptInterface
    fun getDownloadStatus(): String {
      val json = JSONObject()
      val cur = currentTask
      if (cur != null) {
        val curObj = JSONObject().apply {
          put("id", cur.id)
          put("title", cur.title)
          put("bytes", currentBytes)
          put("totalBytes", currentTotalBytes)
          put("progress", currentProgress)
        }
        json.put("current", curObj)
      } else {
        json.put("current", JSONObject.NULL)
      }

      val queueArr = JSONArray()
      for (t in taskQueue) {
        val o = JSONObject().apply {
          put("id", t.id)
          put("title", t.title)
        }
        queueArr.put(o)
      }
      json.put("queue", queueArr)

      val histArr = JSONArray()
      synchronized(historyList) {
        for (h in historyList) {
          val o = JSONObject().apply {
            put("id", h.id)
            put("title", h.title)
            put("status", h.status)
            put("reason", h.reason)
            put("time", h.timestamp)
          }
          histArr.put(o)
        }
      }
      json.put("history", histArr)

      return json.toString()
    }
  }

  private fun addToHistory(item: DownloadHistoryItem) {
    synchronized(historyList) {
      historyList.addFirst(item)
      while (historyList.size > 10) {
        historyList.removeLast()
      }
    }
  }

  private fun enforceLimit() {
    val files = videosDir.listFiles { f -> f.extension.lowercase() == "mp4" } ?: return
    if (files.size >= 70) {
      val sorted = files.sortedBy { f -> viewsMap[f.nameWithoutExtension] ?: 0 }
      val toRemove = sorted.firstOrNull()
      if (toRemove != null) {
        val vidId = toRemove.nameWithoutExtension
        toRemove.delete()
        File(thumbsDir, "$vidId.jpg").delete()
        viewsMap.remove(vidId)
        Log.d(TAG, "Tope de 70 alcanzado. Eliminado video con menos vistas: $vidId")
      }
    }
  }

  private fun openConnectionWithRedirects(initialUrl: String): HttpURLConnection {
    var currentUrl = initialUrl
    var redirects = 0
    while (redirects < 10) {
      val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", CHROME_USER_AGENT)
        setRequestProperty("Accept", "*/*")
      }
      conn.connect()
      val code = conn.responseCode
      if (code in 300..399) {
        val location = conn.getHeaderField("Location")
        conn.disconnect()
        if (location.isNullOrEmpty()) {
          throw IOException("Redirección sin cabecera Location (HTTP $code)")
        }
        currentUrl = URL(URL(currentUrl), location).toString()
        redirects++
        Log.d(TAG, "Redirección HTTP $code hacia: $currentUrl")
        continue
      }
      return conn
    }
    throw IOException("Demasiadas redirecciones HTTP")
  }

  private fun findUrlInJson(json: JSONObject): String? {
    val keys = listOf("downloadUrl", "download_url", "url", "link", "file")
    for (k in keys) {
      if (json.has(k) && !json.isNull(k)) {
        val v = json.optString(k, "").trim()
        if (v.startsWith("http://") || v.startsWith("https://")) return v
      }
    }
    val nestedKeys = listOf("data", "result", "video", "output")
    for (nk in nestedKeys) {
      val nested = json.optJSONObject(nk)
      if (nested != null) {
        val nestedUrl = findUrlInJson(nested)
        if (nestedUrl != null) return nestedUrl
      }
    }
    return null
  }

  private fun generateThumbnail(videoFile: File, id: String) {
    try {
      val retriever = MediaMetadataRetriever()
      retriever.setDataSource(videoFile.absolutePath)
      val frame = retriever.getFrameAtTime(3_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        ?: retriever.frameAtTime
      retriever.release()

      if (frame != null) {
        val minDim = minOf(frame.width, frame.height)
        val sx = (frame.width - minDim) / 2
        val sy = (frame.height - minDim) / 2
        val cropped = Bitmap.createBitmap(frame, sx, sy, minDim, minDim)
        val scaled = Bitmap.createScaledBitmap(cropped, 320, 320, true)
        val thumbFile = File(thumbsDir, "$id.jpg")
        FileOutputStream(thumbFile).use { out ->
          scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        }
        Log.d(TAG, "Miniatura generada para id=$id: ${thumbFile.length()} bytes")
      } else {
        Log.d(TAG, "No se pudo extraer frame para miniatura de id=$id")
      }
    } catch (e: Exception) {
      Log.d(TAG, "Error al generar miniatura para id=$id: ${e.message}")
    }
  }

  private fun processQueue() {
    while (true) {
      val task = taskQueue.poll() ?: break
      currentTask = task
      currentBytes = 0L
      currentTotalBytes = -1L
      currentProgress = -1

      downloadSingleVideo(task)

      queuedIds.remove(task.id)
      currentTask = null
      currentBytes = 0L
      currentTotalBytes = -1L
      currentProgress = -1
    }
  }

  private fun downloadSingleVideo(task: DownloadTask) {
    val id = task.id
    val title = task.title
    Log.d(TAG, "Inicio de descarga: id=$id, título=$title")

    enforceLimit()

    val partFile = File(videosDir, "$id.mp4.part")
    val finalFile = File(videosDir, "$id.mp4")
    if (partFile.exists()) partFile.delete()

    var success = false
    var failReason = "Error desconocido"
    val startTime = System.currentTimeMillis()
    var currentUrl =
      "https://apipy.ssyoutube-downloader.com/youtube/download?url=https://www.youtube.com/watch?v=$id&type=video&height=360"
    var attempts = 0
    val maxAttempts = 18

    try {
      while (attempts < maxAttempts && (System.currentTimeMillis() - startTime) < TIMEOUT_MS) {
        attempts++
        Log.d(TAG, "Conectando intento $attempts para id=$id: $currentUrl")

        val conn = openConnectionWithRedirects(currentUrl)
        val code = conn.responseCode
        val contentType = conn.contentType ?: ""
        Log.d(TAG, "Código de respuesta: $code, content-type: $contentType para id=$id")

        if (code !in 200..299) {
          conn.disconnect()
          failReason = "HTTP $code"
          Log.d(TAG, "Fallo en descarga id=$id: HTTP $code")
          break
        }

        val isJson = contentType.contains("application/json", ignoreCase = true)
        if (isJson) {
          val body = conn.inputStream.bufferedReader().use { it.readText() }
          conn.disconnect()
          Log.d(TAG, "Respuesta JSON recibida para id=$id: $body")
          val json = try { JSONObject(body) } catch (_: Exception) { null }
          if (json != null) {
            val directUrl = findUrlInJson(json)
            if (!directUrl.isNullOrEmpty()) {
              Log.d(TAG, "URL directa extraída del JSON para id=$id: $directUrl")
              currentUrl = directUrl
              continue
            }
            val statusText = (json.optString("status") + " " + json.optString("message")).lowercase()
            if (statusText.contains("process") || statusText.contains("pending") || statusText.contains("wait") || json.optBoolean("processing")) {
              Log.d(TAG, "El servicio indica que sigue procesando video id=$id. Esperando 10 segundos...")
              Thread.sleep(10000)
              continue
            }
            failReason = "JSON sin URL válida de video: $statusText"
            Log.d(TAG, "Fallo en descarga id=$id: $failReason")
            break
          } else {
            failReason = "Respuesta no es JSON válido"
            Log.d(TAG, "Fallo en descarga id=$id: $failReason")
            break
          }
        }

        val totalExpected = conn.contentLengthLong
        currentTotalBytes = totalExpected
        var totalRead = 0L

        FileOutputStream(partFile).use { outStream ->
          val buffer = ByteArray(8192)
          val inStream = conn.inputStream
          var n = inStream.read(buffer)
          var lastLog = System.currentTimeMillis()

          while (n != -1) {
            outStream.write(buffer, 0, n)
            totalRead += n
            currentBytes = totalRead
            if (totalExpected > 0) {
              currentProgress = ((totalRead * 100) / totalExpected).toInt()
            }

            val now = System.currentTimeMillis()
            if (now - lastLog >= 3000) {
              Log.d(TAG, "Bytes bajados para id=$id: $totalRead bytes")
              lastLog = now
            }

            if (now - startTime > TIMEOUT_MS) {
              throw IOException("Timeout de 3 minutos superado")
            }
            n = inStream.read(buffer)
          }
          outStream.flush()
        }
        conn.disconnect()

        Log.d(TAG, "Descarga de stream finalizada para id=$id. Bytes totales bajados: $totalRead")

        if (partFile.length() > MIN_FILE_SIZE) {
          if (finalFile.exists()) finalFile.delete()
          val renamed = partFile.renameTo(finalFile)
          if (renamed) {
            success = true
            Log.d(TAG, "Descarga completada con éxito: ${finalFile.name} (${finalFile.length()} bytes)")
            generateThumbnail(finalFile, id)
          } else {
            failReason = "Error renombrando .part a .mp4"
            Log.d(TAG, "Fallo en descarga id=$id: $failReason")
          }
        } else {
          failReason = "Archivo menor a 200 KB (${partFile.length()} bytes)"
          Log.d(TAG, "Fallo en descarga id=$id: $failReason")
          if (partFile.exists()) partFile.delete()
        }
        break
      }
    } catch (e: Exception) {
      failReason = e.message ?: e.javaClass.simpleName
      Log.d(TAG, "Fallo en descarga id=$id: motivo=$failReason", e)
      if (partFile.exists()) partFile.delete()
    }

    val status = if (success) "OK" else "Falló"
    val reasonDesc = if (success) {
      val mb = finalFile.length() / 1048576.0
      String.format(java.util.Locale.US, "%.1f MB", mb)
    } else {
      failReason
    }
    addToHistory(DownloadHistoryItem(id, title, status, reasonDesc, System.currentTimeMillis()))
  }

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    videosDir = File(getExternalFilesDir(null), "videos").apply { if (!exists()) mkdirs() }
    thumbsDir = File(getExternalFilesDir(null), "thumbs").apply { if (!exists()) mkdirs() }

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

    fun handleRequest(request: WebResourceRequest): WebResourceResponse? {
      val url = request.url
      if (url.host == "appassets.androidplatform.net") {
        val path = url.path ?: ""
        if (path.isEmpty() || path == "/" || path == "/assets" || path == "/assets/") {
          return assetLoader.shouldInterceptRequest(Uri.parse("https://appassets.androidplatform.net/assets/index.html"))
        }
        if (path.startsWith("/videos/")) {
          val filename = path.removePrefix("/videos/")
          val file = File(videosDir, filename)
          if (file.exists()) {
            val totalLength = file.length()
            val rangeHeader = request.requestHeaders?.get("Range")
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
              try {
                val rangeParts = rangeHeader.removePrefix("bytes=").split("-")
                val start = rangeParts.getOrNull(0)?.toLongOrNull() ?: 0L
                val end = rangeParts.getOrNull(1)?.toLongOrNull() ?: (totalLength - 1)
                val contentLength = end - start + 1
                val fis = file.inputStream()
                fis.skip(start)
                val headers = mutableMapOf(
                  "Content-Range" to "bytes $start-$end/$totalLength",
                  "Accept-Ranges" to "bytes",
                  "Content-Length" to contentLength.toString(),
                  "Access-Control-Allow-Origin" to "*"
                )
                return WebResourceResponse("video/mp4", null, 206, "Partial Content", headers, fis)
              } catch (_: Exception) {
                return WebResourceResponse("video/mp4", null, file.inputStream())
              }
            } else {
              val headers = mutableMapOf(
                "Accept-Ranges" to "bytes",
                "Content-Length" to totalLength.toString(),
                "Access-Control-Allow-Origin" to "*"
              )
              return WebResourceResponse("video/mp4", null, 200, "OK", headers, file.inputStream())
            }
          }
        }
        if (path.startsWith("/thumbs/")) {
          val filename = path.removePrefix("/thumbs/")
          val file = File(thumbsDir, filename)
          if (file.exists()) {
            val headers = mutableMapOf(
              "Content-Length" to file.length().toString(),
              "Access-Control-Allow-Origin" to "*"
            )
            return WebResourceResponse("image/jpeg", null, 200, "OK", headers, file.inputStream())
          }
        }
      }
      return assetLoader.shouldInterceptRequest(url)
    }

    if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) {
      try {
        ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : ServiceWorkerClientCompat() {
          override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
            return handleRequest(request)
          }
        })
      } catch (_: Exception) {
        // Ignorado si no se soporta
      }
    }

    webView = WebView(this).apply {
      layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
      )
      setBackgroundColor(android.graphics.Color.BLACK)

      try {
        val cookieManager = android.webkit.CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(this, true)
      } catch (_: Exception) {}

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
        cacheMode = WebSettings.LOAD_DEFAULT
      }

      addJavascriptInterface(AndroidBridge(this@MainActivity), "Android")

      webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(
          view: WebView,
          request: WebResourceRequest
        ): WebResourceResponse? {
          return handleRequest(request)
        }

        override fun onRenderProcessGone(
          view: WebView,
          detail: RenderProcessGoneDetail
        ): Boolean {
          Log.w(TAG, "WebView render process gone. Crashed: ${detail.didCrash()}")
          return true
        }

        override fun onReceivedError(
          view: WebView,
          request: WebResourceRequest,
          error: android.webkit.WebResourceError
        ) {
          super.onReceivedError(view, request, error)
          Log.w(TAG, "WebView resource error [${error.errorCode}]: ${error.description} on ${request.url}")
        }
      }

      webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
          consoleMessage?.let {
            Log.d("WebConsole", "[${it.messageLevel()}] ${it.message()} (${it.sourceId()}:${it.lineNumber()})")
          }
          return super.onConsoleMessage(consoleMessage)
        }
      }

      val headers = mapOf("Referer" to "https://appassets.androidplatform.net/")
      loadUrl("https://appassets.androidplatform.net/assets/index.html", headers)
    }

    setContentView(webView)
  }

  override fun onDestroy() {
    downloadExecutor.shutdownNow()
    webView?.destroy()
    webView = null
    super.onDestroy()
  }
}
