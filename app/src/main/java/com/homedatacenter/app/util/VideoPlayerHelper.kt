package com.homedatacenter.app.util

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.homedatacenter.app.data.model.Camera

object VideoPlayerHelper {

    private const val HLS_JS_URL = "https://cdn.jsdelivr.net/npm/hls.js@1.5.13/dist/hls.min.js"

    private enum class StreamType {
        HLS, WEBRTC_WS, WEBRTC_HTTP, NATIVE
    }

    fun showVideoDialog(
        context: Context,
        camera: Camera,
        apiBaseUrl: String? = null,
        jwtToken: String? = null
    ): Dialog? {
        val stream = camera.stream ?: return null
        val hlsUrl = stream.hlsUrl.trim()
        val webrtcUrl = stream.webrtcUrl.trim()
        if (hlsUrl.isEmpty() && webrtcUrl.isEmpty()) return null

        val resolvedHls = resolveUrl(hlsUrl, apiBaseUrl)
        val resolvedWebrtc = resolveUrl(webrtcUrl, apiBaseUrl)

        val hasWebrtcWs = resolvedWebrtc.isNotEmpty() && isWebRtcWsUrl(resolvedWebrtc)
        val hasWebrtcHttp = resolvedWebrtc.isNotEmpty() && isWebRtcHttpUrl(resolvedWebrtc)
        val hasHls = resolvedHls.isNotEmpty() && isHlsUrl(resolvedHls)

        android.util.Log.d("VideoPlayerHelper",
            "Camera '${camera.name}' stream: hls='$resolvedHls' webrtc='$resolvedWebrtc'")
        android.util.Log.d("VideoPlayerHelper",
            "hasWebrtcWs=$hasWebrtcWs, hasWebrtcHttp=$hasWebrtcHttp, hasHls=$hasHls")

        if (!jwtToken.isNullOrEmpty() && apiBaseUrl != null) {
            installAuthCookie(apiBaseUrl, jwtToken)
        }

        if (context is FragmentActivity && hasHls) {
            showExoPlayerFragment(context, camera, resolvedHls, resolvedWebrtc,
                hasWebrtcWs, hasWebrtcHttp, hasHls, jwtToken)
            return null
        }

        return createWebViewDialog(context, camera, resolvedWebrtc, resolvedHls,
            hasWebrtcWs, hasWebrtcHttp, hasHls, jwtToken)
    }

    private fun showExoPlayerFragment(
        activity: FragmentActivity,
        camera: Camera,
        hlsUrl: String,
        webrtcUrl: String,
        hasWebrtcWs: Boolean,
        hasWebrtcHttp: Boolean,
        hasHls: Boolean,
        jwtToken: String?
    ) {
        val fragment = ExoPlayerDialogFragment.newInstance(
            camera.name,
            hlsUrl,
            jwtToken,
            onPlaybackFailed = {
                android.util.Log.d("VideoPlayerHelper", "ExoPlayer failed, falling back to WebView")
                createWebViewDialog(activity, camera, webrtcUrl, hlsUrl,
                    hasWebrtcWs, hasWebrtcHttp, hasHls, jwtToken)
            }
        )
        fragment.show(activity.supportFragmentManager, "ExoPlayerDialog")
    }

    private fun createWebViewDialog(
        context: Context,
        camera: Camera,
        webrtcUrl: String,
        hlsUrl: String,
        hasWebrtcWs: Boolean,
        hasWebrtcHttp: Boolean,
        hasHls: Boolean,
        jwtToken: String?
    ): Dialog {
        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val container = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        val loadingBar = ProgressBar(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        container.addView(loadingBar)

        val errorText = TextView(context).apply {
            text = ""
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(32, 32, 32, 32)
            gravity = Gravity.CENTER
            visibility = android.view.View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        container.addView(errorText)

        val webView = createWebView(context)
        container.addView(webView)

        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun onReady() {
                loadingBar.post { loadingBar.visibility = android.view.View.GONE }
            }

            @android.webkit.JavascriptInterface
            fun onError(message: String) {
                loadingBar.post {
                    loadingBar.visibility = android.view.View.GONE
                    if (message == "closed") {
                        dialog.dismiss()
                    } else {
                        errorText.text = "播放失败: $message"
                        errorText.visibility = android.view.View.VISIBLE
                    }
                }
            }
        }, "AndroidBridge")

        val html = buildPlayerHtml(camera.name, webrtcUrl, hlsUrl, hasWebrtcWs, hasWebrtcHttp, hasHls, jwtToken)
        val baseUrl = getBaseUrl(webrtcUrl.takeIf { it.isNotEmpty() } ?: hlsUrl) ?: "about:blank"
        webView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)

        dialog.setContentView(container)
        dialog.setOnDismissListener {
            webView.apply {
                stopLoading()
                loadUrl("about:blank")
                removeJavascriptInterface("AndroidBridge")
                destroy()
            }
        }
        dialog.show()
        return dialog
    }

    private fun createWebView(context: Context): WebView {
        return WebView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = true
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_NO_CACHE
                allowContentAccess = true
                allowFileAccess = false
                domStorageEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                setSupportZoom(false)
                useWideViewPort = true
                loadWithOverviewMode = true
                javaScriptCanOpenWindowsAutomatically = true
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean = true

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    val url = request?.url?.toString() ?: "?"
                    android.util.Log.w("VideoPlayerHelper",
                        "WebView resource error: $url -> ${error?.description}")
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: android.webkit.WebResourceResponse?
                ) {
                    val url = request?.url?.toString() ?: "?"
                    val status = errorResponse?.statusCode ?: -1
                    android.util.Log.w("VideoPlayerHelper",
                        "HTTP $status on $url -> ${errorResponse?.reasonPhrase}")
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                    android.util.Log.d("VideoPlayerJS",
                        "[${consoleMessage?.messageLevel()}] ${consoleMessage?.message()} " +
                            "at ${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()}")
                    return true
                }

                override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                    request?.let {
                        android.util.Log.d("VideoPlayerHelper",
                            "Granting WebView permissions: ${it.resources.joinToString()}")
                        it.grant(it.resources)
                    }
                }
            }
            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        }
    }

    private fun isHlsUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains("/live/") || lower.contains("/hls/") ||
            lower.contains("/stream.m3u8")
    }

    private fun isWebRtcWsUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("ws://") || lower.startsWith("wss://")
    }

    private fun isWebRtcHttpUrl(url: String): Boolean {
        val lower = url.lowercase()
        return (lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("/")) &&
            (lower.contains("/api/webrtc") || lower.contains("/webrtc"))
    }

    private fun installAuthCookie(apiBaseUrl: String, jwtToken: String) {
        try {
            val baseUri = android.net.Uri.parse(apiBaseUrl)
            val scheme = baseUri.scheme ?: "https"
            val host = baseUri.host ?: return
            val cookieUrl = "$scheme://$host"
            val cookie = "home_token=$jwtToken; path=/; Max-Age=31536000"
            CookieManager.getInstance().setCookie(cookieUrl, cookie)
            CookieManager.getInstance().flush()
            android.util.Log.d("VideoPlayerHelper",
                "Installed home_token cookie for $cookieUrl (length=${jwtToken.length})")
        } catch (e: Exception) {
            android.util.Log.w("VideoPlayerHelper", "Failed to install auth cookie: ${e.message}")
        }
    }

    private fun resolveUrl(url: String, baseUrl: String?): String {
        if (url.isEmpty()) return url
        if (url.startsWith("http://") || url.startsWith("https://") ||
            url.startsWith("ws://") || url.startsWith("wss://")) {
            return url
        }
        if (baseUrl.isNullOrBlank()) return url
        return try {
            val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            val baseUri = android.net.Uri.parse(base)
            val scheme = when (baseUri.scheme) {
                "wss" -> "https"
                "ws" -> "http"
                else -> baseUri.scheme
            } ?: "https"
            val port = baseUri.port
            val host = baseUri.host ?: return url
            val origin = if (port > 0 && port != defaultPort(scheme)) {
                "$scheme://$host:$port"
            } else {
                "$scheme://$host"
            }
            if (url.startsWith("/")) origin + url else "$origin/$url"
        } catch (_: Exception) {
            url
        }
    }

    private fun getBaseUrl(url: String): String? {
        return try {
            val uri = android.net.Uri.parse(url)
            val scheme = uri.scheme ?: return null
            val host = uri.host ?: return null
            val port = uri.port
            if (port > 0 && port != defaultPort(scheme)) {
                "$scheme://$host:$port/"
            } else {
                "$scheme://$host/"
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun defaultPort(scheme: String): Int = when (scheme) {
        "https", "wss" -> 443
        "http", "ws" -> 80
        else -> -1
    }

    private fun buildPlayerHtml(
        cameraName: String,
        webrtcUrl: String,
        hlsUrl: String,
        hasWebrtcWs: Boolean,
        hasWebrtcHttp: Boolean,
        hasHls: Boolean,
        jwtToken: String? = null
    ): String {
        val escapedName = escapeHtml(cameraName)
        val escapedWebrtcUrl = escapeJs(webrtcUrl)
        val escapedHlsUrl = escapeJs(hlsUrl)
        val escapedToken = if (jwtToken != null) escapeJs(jwtToken) else ""

        return """
<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<style>
  html, body { margin: 0; padding: 0; width: 100%; height: 100%; background: #000; overflow: hidden; font-family: -apple-system, "Helvetica Neue", Arial, sans-serif; }
  #title { position: absolute; top: 12px; left: 16px; z-index: 10; color: #fff; font-size: 14px; background: rgba(0,0,0,0.4); padding: 6px 12px; border-radius: 16px; pointer-events: none; }
  #video { width: 100%; height: 100%; object-fit: contain; background: #000; display: block; }
  #close { position: absolute; top: 12px; right: 16px; z-index: 10; color: #fff; font-size: 22px; background: rgba(0,0,0,0.4); width: 36px; height: 36px; border-radius: 50%; display: flex; align-items: center; justify-content: center; cursor: pointer; user-select: none; }
  #status { position: absolute; bottom: 16px; left: 50%; transform: translateX(-50%); z-index: 10; color: #fff; font-size: 12px; background: rgba(0,0,0,0.4); padding: 4px 10px; border-radius: 12px; pointer-events: none; }
</style>
</head>
<body>
  <div id="title">$escapedName</div>
  <div id="close" onclick="window.closeVideoDialog();">×</div>
  <video id="video" autoplay playsinline muted></video>
  <div id="status">连接中…</div>

  <script>
    (function() {
      const video = document.getElementById('video');
      const status = document.getElementById('status');
      const webrtcUrl = "$escapedWebrtcUrl";
      const hlsUrl = "$escapedHlsUrl";
      const authToken = "$escapedToken";
      const hasWebrtcWs = $hasWebrtcWs;
      const hasWebrtcHttp = $hasWebrtcHttp;
      const hasHls = $hasHls;
      const Bridge = window.AndroidBridge;

      var teardownFn = null;

      function setStatus(msg) { status.textContent = msg; }
      function notifyReady() { if (Bridge) Bridge.onReady(); setStatus(''); }
      function notifyError(msg) { if (Bridge) Bridge.onError(msg); else setStatus('错误: ' + msg); }

      function teardown() { if (teardownFn) teardownFn(); teardownFn = null; }

      window.closeVideoDialog = function() {
        cancelWatchdog();
        teardown();
        if (Bridge) Bridge.onError('closed');
      };

      if (authToken) {
        document.cookie = 'home_token=' + authToken + '; path=/; Max-Age=31536000';
      }

      const originalFetch = window.fetch.bind(window);
      window.fetch = function(input, init) {
        init = init || {};
        init.credentials = 'include';
        if (authToken) {
          init.headers = new Headers(init.headers || {});
          init.headers.set('Authorization', 'Bearer ' + authToken);
        }
        return originalFetch(input, init);
      };

      let played = false;
      let watchdogTimer = null;
      video.addEventListener('playing', function() {
        played = true;
        if (watchdogTimer) { clearTimeout(watchdogTimer); watchdogTimer = null; }
        notifyReady();
      });
      video.addEventListener('error', function(e) {
        notifyError('视频错误 (code=' + (video.error ? video.error.code : 'unknown') + ')');
      });

      function startWatchdog(timeoutMs, onTimeout) {
        if (watchdogTimer) clearTimeout(watchdogTimer);
        watchdogTimer = setTimeout(function() {
          watchdogTimer = null;
          if (!played) onTimeout();
        }, timeoutMs);
      }
      function cancelWatchdog() {
        if (watchdogTimer) { clearTimeout(watchdogTimer); watchdogTimer = null; }
      }

      ${hlsJsPlayerSnippet()}
      ${webrtcWsPlayerSnippet()}
      ${webrtcHttpPlayerSnippet()}
      ${nativePlayerSnippet()}

      function tryPlayback() {
        if (hasHls) {
          setStatus('加载 HLS…');
          startWatchdog(20000, function() {
            console.log('Watchdog: HLS did not start in 20s, trying WebRTC');
            teardown();
            if (hasWebrtcHttp) {
              setStatus('HLS 超时，尝试 WebRTC…');
              teardownFn = startWebRtcHttp(function(error) {
                cancelWatchdog();
                notifyError('所有流协议均失败: ' + error);
              });
            } else {
              notifyError('HLS 加载超时');
            }
          });
          loadWithHlsJs();
        } else if (hasWebrtcHttp) {
          setStatus('尝试 WebRTC…');
          startWatchdog(15000, function() {
            console.log('Watchdog: WebRTC no playing event after 15s');
            teardown();
            notifyError('WebRTC 连接超时');
          });
          teardownFn = startWebRtcHttp(function(error) {
            cancelWatchdog();
            notifyError('WebRTC 失败: ' + error);
          });
        } else {
          notifyError('无可用流');
        }
      }

      tryPlayback();
      window.addEventListener('beforeunload', function() { cancelWatchdog(); teardown(); });
    })();
  </script>
</body>
</html>
        """.trimIndent()
    }

    private fun hlsJsPlayerSnippet(): String = """
      function loadWithHlsJs() {
        teardownFn = function() {
          try { if (window.hlsPlayer) { window.hlsPlayer.destroy(); window.hlsPlayer = null; } } catch (e) {}
        };
        if (video.canPlayType('application/vnd.apple.mpegurl')) {
          video.src = hlsUrl;
          setStatus('加载中…');
          video.play().catch(function(e) { notifyError('播放失败: ' + e.message); });
          return;
        }
        const script = document.createElement('script');
        script.src = "$HLS_JS_URL";
        script.onerror = function() { notifyError('无法加载 hls.js 库'); };
        script.onload = function() {
          if (!window.Hls) { notifyError('hls.js 加载失败'); return; }
          if (!Hls.isSupported()) { notifyError('浏览器不支持 MSE'); return; }
          window.hlsPlayer = new Hls({
            enableWorker: true,
            lowLatencyMode: true,
            backBufferLength: 30,
            maxBufferLength: 30,
            maxMaxBufferLength: 60,
            liveSyncDuration: 3,
            liveMaxLatencyDuration: 10,
            manifestLoadingTimeOut: 10000,
            manifestLoadingMaxRetry: 4,
            levelLoadingTimeOut: 10000,
            fragLoadingTimeOut: 20000,
            withCredentials: true,
            xhrSetup: function(xhr, xhrUrl) {}
          });
          window.hlsPlayer.loadSource(hlsUrl);
          window.hlsPlayer.attachMedia(video);
          window.hlsPlayer.on(Hls.Events.MANIFEST_PARSED, function() {
            setStatus('缓冲中…');
            video.play().catch(function(e) {
              notifyError('播放失败: ' + e.message);
            });
          });
          window.hlsPlayer.on(Hls.Events.ERROR, function(event, data) {
            if (data.fatal) {
              switch (data.type) {
                case Hls.ErrorTypes.NETWORK_ERROR:
                  setStatus('网络错误，重试…');
                  window.hlsPlayer.startLoad();
                  break;
                case Hls.ErrorTypes.MEDIA_ERROR:
                  setStatus('媒体错误，恢复…');
                  window.hlsPlayer.recoverMediaError();
                  break;
                default:
                  window.hlsPlayer.destroy();
                  window.hlsPlayer = null;
                  notifyError('HLS 致命错误: ' + data.details);
                  break;
              }
            }
          });
        };
        document.head.appendChild(script);
      }
    """.trimIndent()

    private fun webrtcWsPlayerSnippet(): String = """
      function startWebRtcWs(onError) {
        let pc = null;
        let ws = null;
        let localTeardown = function() {
          try { if (pc) pc.close(); } catch (e) {}
          try { if (ws) ws.close(); } catch (e) {}
          pc = null; ws = null;
        };
        teardownFn = localTeardown;

        try {
          pc = new RTCPeerConnection({
            iceServers: [
              { urls: 'stun:stun.l.google.com:19302' },
              { urls: 'stun:stun.cloudflare.com:3478' },
              { urls: 'stun:stunserver.stunprotocol.org:3478' }
            ],
            bundlePolicy: 'max-bundle',
            iceTransportPolicy: 'all'
          });
        } catch (e) {
          if (onError) onError(e.message); else notifyError('WebRTC 不可用: ' + e.message);
          return;
        }

        pc.ontrack = function(event) {
          setStatus('接收流…');
          video.srcObject = event.streams[0];
          video.play().catch(function(e) { notifyError('播放失败: ' + e.message); });
        };
        pc.onicecandidate = function(event) {
          if (event.candidate && ws && ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify({ type: 'candidate', candidate: event.candidate }));
          }
        };
        pc.oniceconnectionstatechange = function() {
          if (pc.iceConnectionState === 'failed') {
            if (onError) onError('ICE 连接失败'); else notifyError('ICE 连接失败');
          }
        };

        try {
          ws = new WebSocket(webrtcUrl);
        } catch (e) {
          if (onError) onError('WebSocket 创建失败: ' + e.message); else notifyError('WebSocket 创建失败: ' + e.message);
          return;
        }

        ws.onopen = function() {
          setStatus('协商中…');
          ws.send(JSON.stringify({
            type: 'request',
            transceiver: 'recvonly',
            constraints: { offerToReceiveAudio: false, offerToReceiveVideo: true }
          }));
        };

        ws.onmessage = function(evt) {
          let msg;
          try { msg = JSON.parse(evt.data); } catch (e) { return; }

          if (msg.type === 'answer' && msg.sdp) {
            pc.setRemoteDescription({ type: 'answer', sdp: msg.sdp })
              .catch(function(e) { if (onError) onError('协商失败: ' + e.message); else notifyError('协商失败: ' + e.message); });
          } else if (msg.type === 'offer' && msg.sdp) {
            pc.setRemoteDescription({ type: 'offer', sdp: msg.sdp })
              .then(function() { return pc.createAnswer(); })
              .then(function(answer) { return pc.setLocalDescription(answer); })
              .then(function() {
                ws.send(JSON.stringify({ type: 'answer', sdp: pc.localDescription.sdp }));
              })
              .catch(function(e) { if (onError) onError('协商失败: ' + e.message); else notifyError('协商失败: ' + e.message); });
          } else if (msg.type === 'candidate' && msg.candidate) {
            pc.addIceCandidate(msg.candidate).catch(function() {});
          }
        };

        ws.onerror = function() { if (onError) onError('WebSocket 连接错误'); else notifyError('WebSocket 连接错误'); };
        ws.onclose = function() { if (onError && status.textContent === '协商中…') onError('WebSocket 已关闭'); };
      }
    """.trimIndent()

    private fun webrtcHttpPlayerSnippet(): String = """
      function startWebRtcHttp(onError) {
        let pc = null;
        let abortController = null;
        let localTeardown = function() {
          try { if (abortController) abortController.abort(); } catch (e) {}
          abortController = null;
          try { if (pc) pc.close(); } catch (e) {}
          pc = null;
        };
        teardownFn = localTeardown;

        try {
          pc = new RTCPeerConnection({
            iceServers: [
              { urls: 'stun:stun.l.google.com:19302' },
              { urls: 'stun:stun.cloudflare.com:3478' },
              { urls: 'stun:stunserver.stunprotocol.org:3478' }
            ],
            bundlePolicy: 'max-bundle',
            iceTransportPolicy: 'all'
          });
        } catch (e) {
          if (onError) onError(e.message); else notifyError('WebRTC 不可用: ' + e.message);
          return;
        }

        pc.addTransceiver('video', { direction: 'recvonly' });
        pc.addTransceiver('audio', { direction: 'recvonly' });

        pc.ontrack = function(event) {
          setStatus('接收流…');
          video.srcObject = event.streams[0];
          video.play().catch(function(e) { notifyError('播放失败: ' + e.message); });
        };
        pc.oniceconnectionstatechange = function() {
          if (pc.iceConnectionState === 'failed') {
            if (onError) onError('ICE 连接失败'); else notifyError('ICE 连接失败');
          } else if (pc.iceConnectionState === 'disconnected') {
            setStatus('连接断开');
          }
        };

        setStatus('协商中…');
        abortController = new AbortController();

        pc.createOffer({ offerToReceiveAudio: true, offerToReceiveVideo: true })
          .then(function(offer) { return pc.setLocalDescription(offer); })
          .then(function() {
            return new Promise(function(resolve) {
              if (pc.iceGatheringState === 'complete') {
                resolve(pc.localDescription.sdp);
              } else {
                var timeout = setTimeout(function() {
                  pc.removeEventListener('icegatheringstatechange', checkState);
                  resolve(pc.localDescription.sdp);
                }, 3000);
                function checkState() {
                  if (pc.iceGatheringState === 'complete') {
                    clearTimeout(timeout);
                    pc.removeEventListener('icegatheringstatechange', checkState);
                    resolve(pc.localDescription.sdp);
                  }
                }
                pc.addEventListener('icegatheringstatechange', checkState);
              }
            });
          })
          .then(function(sdp) {
            setStatus('发送 SDP…');
            return Promise.race([
              fetch(webrtcUrl, {
                method: 'POST',
                headers: { 'Content-Type': 'application/sdp' },
                body: sdp,
                signal: abortController.signal
              }),
              new Promise(function(_, reject) {
                setTimeout(function() { reject(new Error('请求超时')); }, 10000);
              })
            ]);
          })
          .then(function(resp) {
            if (!resp.ok) {
              if (resp.status === 401) {
                throw new Error('认证失败，请重新登录');
              } else if (resp.status === 404) {
                throw new Error('流不存在');
              }
              throw new Error('HTTP ' + resp.status + ' ' + resp.statusText);
            }
            setStatus('接收应答…');
            return resp.text();
          })
          .then(function(answerSdp) {
            if (!answerSdp || answerSdp.trim() === '') {
              throw new Error('空的 SDP 应答');
            }
            return pc.setRemoteDescription({ type: 'answer', sdp: answerSdp });
          })
          .then(function() {
            setStatus('连接中…');
          })
          .catch(function(e) {
            localTeardown();
            if (onError) onError(e.message); else notifyError('WebRTC 失败: ' + e.message);
          });
      }
    """.trimIndent()

    private fun nativePlayerSnippet(): String = """
      function tryNative() {
        teardownFn = function() {};
        const source = document.createElement('source');
        const m = hlsUrl.match(/\.(mp4|webm|ogg|m3u8)(\?|$)/i);
        if (m) {
          const ext = m[1].toLowerCase();
          const map = { mp4: 'video/mp4', webm: 'video/webm', ogg: 'video/ogg', m3u8: 'application/x-mpegURL' };
          source.type = map[ext] || 'video/mp4';
        } else {
          source.type = 'video/mp4';
        }
        source.src = hlsUrl;
        video.appendChild(source);
        setStatus('加载中…');
        video.load();
        video.play().catch(function(e) {
          notifyError('播放失败: ' + e.message);
          video.muted = true;
          video.play().catch(function() {});
        });
      }
    """.trimIndent()

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    private fun escapeJs(text: String): String =
        text.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
            .replace("</", "<\\/")
}
