# 架构设计

本文件补充 [README.md](../README.md) 没展开的架构与设计决策细节。

---

## 1. 分层总览

```
┌─────────────────────────────────────────────────────────────────┐
│                    UI Layer (androidx.fragment)                 │
│                                                                 │
│   LoginActivity          MainActivity                          │
│       │                      │                                  │
│       │           ┌──────────┼────────────┐                     │
│       │           ▼          ▼            ▼                     │
│       │      Dashboard  Cameras       Alerts                   │
│       │       Fragment  Fragment       Fragment                │
│       │           │          │            │                     │
│       │      Devices   Settings                                │
│       │       Fragment  Fragment                                │
│       │           │          │                                  │
│       ▼           ▼          ▼                                  │
└─────────────────────────────────────────────────────────────────┘
                          │ container.getRepository()
                          ▼
┌─────────────────────────────────────────────────────────────────┐
│              Repository Layer (HomeCenterRepository)           │
│                                                                 │
│      suspend fun getCameras(): Result<List<Camera>>             │
│      suspend fun getAlerts(limit): Result<List<Alert>>          │
│      suspend fun getRecordings(id): Result<List<Recording>>     │
│      ...                                                        │
└─────────────────────────────────────────────────────────────────┘
                          │ Retrofit suspend API
                          ▼
┌─────────────────────────────────────────────────────────────────┐
│                  Data Layer (api + ws)                          │
│                                                                 │
│   HomeCenterApi (Retrofit)   HomeCenterWebSocket (OkHttp WS)   │
│           │                          │                         │
│           ▼                          ▼                         │
│      OkHttpClient                 OkHttpClient                  │
│      (shared, HTTP/1.1)          (shared, HTTP/1.1)            │
└─────────────────────────────────────────────────────────────────┘
                          │ HTTPS / WSS
                          ▼
            ┌──────────────────────────────┐
            │   home-datacenter backend    │
            │   (Go + Gin + SQLite +        │
            │    MQTT + Frigate + go2rtc)   │
            └──────────────────────────────┘
```

---

## 2. DI 容器：`AppContainer`

### 设计动机

项目体量不大（约 30 个 Kotlin 文件），引入 Hilt / Dagger 学习成本高、构建慢。手写一个 50 行的容器足够：

```kotlin
class AppContainer(private val context: Context) {
    val prefsManager: PrefsManager by lazy { PrefsManager(context) }
    val okHttpClient: OkHttpClient by lazy {
        NetworkFactory.okHttpClient(enableLogging = true)
    }
    private var currentBaseUrl: String = ""
    private var currentApi: HomeCenterApi? = null
    private var currentRepository: HomeCenterRepository? = null

    fun getApi(): HomeCenterApi { ... }
    fun getApiBaseUrl(): String = prefsManager.baseUrl ?: DEFAULT_BASE_URL
    fun getRepository(): HomeCenterRepository { ... }
    fun getWsUrl(): String { ... }
    fun resetApi() { ... }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.feiyemomo.top/"
    }
}
```

### 生命周期

- `HomeCenterApp`（Application）创建唯一实例
- `LoginActivity` 通过 `application as HomeCenterApp` 拿到
- `MainActivity` 通过 `application as HomeCenterApp` 拿到并传给 Fragment
- Fragment 通过 `requireActivity().application as HomeCenterApp` 或 `Activity.getContainer()` 拿到

### baseUrl 切换

虽然当前固定到 `DEFAULT_BASE_URL`，但保留了切换逻辑：`getApi()` 检查 baseUrl 是否变化，变化时重建 `Retrofit` 与 `Repository`。未来如果重新开放用户自定义服务器地址，只需在 `PrefsManager.baseUrl` 写入新值，下次 `getApi()` 自动重建。

---

## 3. 网络层：`NetworkFactory` 与 `HomeCenterApi`

### OkHttp 配置

```kotlin
OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .callTimeout(90, TimeUnit.SECONDS)
    .pingInterval(30, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
```

| 参数 | 选择 | 理由 |
|---|---|---|
| connectTimeout | 30s | 移动网络 + Cloudflare Tunnel TLS 握手慢，15s 偶尔不够 |
| readTimeout | 60s | HLS 直播保持长连接 |
| callTimeout | 90s | 整体调用上限 |
| pingInterval | 30s | 保活心跳，防止移动网络 NAT 超时断流 |
| retryOnConnectionFailure | true | 移动网络偶发 TLS / TCP 失败重试 |
| protocols | HTTP/1.1 only | Cloudflare Tunnel 在移动网络上偶发关闭 HTTP/2 stream，HTTP/1.1 稳定 |

### Retrofit + kotlinx.serialization

```kotlin
val json = Json {
    ignoreUnknownKeys = true       // 后端新增字段不会让旧客户端崩
    encodeDefaults = true          // 序列化时写出默认值
    explicitNulls = false         // null 字段不写
}

Retrofit.Builder()
    .baseUrl(baseUrl)
    .client(client)
    .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
    .build()
    .create(HomeCenterApi::class.java)
```

### 标准响应包络

后端所有 `/api/v1/*` 都返回：

```json
{ "code": 0, "message": "success", "data": <T> }
```

`ApiResponse.decodeData<T>()` 负责解包：

```kotlin
inline fun <reified T> ApiResponse.decodeData(): T? {
    return if (code == 0) data?.toString()?.let { json.decodeFromString(it) } else null
}
```

---

## 4. 鉴权设计

### 登录流程

```
[LoginActivity]
    │
    │  POST /api/v1/auth/bind
    │  Body: { "user_id": 1, "access_key": "..." }
    │
    ▼
home-api 验证 (user_id, access_key) → 签发 JWT
    │
    │  Response:
    │  { "code": 0, "data": { "token": "<jwt>" } }
    │  Set-Cookie: home_token=<jwt>; SameSite=Lax; Max-Age=31536000
    │
    ▼
[PrefsManager.saveAuth(token, userId)]
    │  EncryptedSharedPreferences 写入
    │
    ▼
startActivity(MainActivity)
```

### JWT 的两条传输路径

| 路径 | 用途 | 在客户端如何发送 |
|---|---|---|
| `Authorization: Bearer <jwt>` | REST API 调用（`/api/v1/*`） | `HomeCenterApi` 接口每个方法 `@Header("Authorization")` |
| `Cookie: home_token=<jwt>` | 同源代理路径（`/go2rtc/`, `/frigate/`） | ExoPlayer `DefaultHttpDataSource.Factory().setDefaultRequestProperties(...)` 同时加两个头 |

后端 `/api/v1/auth/verify` 同时支持两种来源（先看 `Authorization`，再看 Cookie），nginx `auth_request` 子请求会调用它来 gate `/go2rtc/` 路径。

### JWT 持久化

`PrefsManager` 用 `androidx.security.crypto.EncryptedSharedPreferences`（AES-GCM 256），master key 由 Android Keystore 管理：

```kotlin
val masterKey = MasterKey.Builder(context)
    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
    .build()

val prefs = EncryptedSharedPreferences.create(
    context, "auth_prefs", masterKey,
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
)
```

### 登出

`SettingsFragment` 调 `container.prefsManager.clearAuth()` → `container.resetApi()` → 跳回 `LoginActivity`。

### 角色化 UI（v1.5.0）

后端故意**不把 `is_admin` 写入 JWT**，每次需要管理员权限的请求都重新查数据库（`middleware.RequireAdmin`），因此降级立即生效。客户端用 `RoleManager` 缓存 `/api/v1/user/me` 的结果到 `PrefsManager`，让 UI 在 `/me` 解析前可以同步判断是否隐藏管理员入口（FAB、UsersActivity 跳转按钮、CameraDetailActivity 的修改控件等）：

```kotlin
class RoleManager(
    private val prefsManager: PrefsManager,
    private val repository: HomeCenterRepository,
) {
    fun isAdminCached(): Boolean = prefsManager.isAdmin
    suspend fun refresh(token: String) = withContext(Dispatchers.IO) {
        try {
            val user = repository.getMe(token)
            prefsManager.saveUserInfo(user.name, user.isAdmin)
            prefsManager.userId = user.id
            user
        } catch (_: Exception) { null }   // 网络失败时保留旧角色，服务端仍会兜底 403
    }
}
```

`MainActivity.onCreate` 与 `onResume` 都会触发 `refreshRole()`，确保用户从其他会话被降级后回到本应用时立刻刷新底部导航与设置页的可见项。

服务端始终是权威来源：即使客户端缓存过期，把管理员按钮暴露给非管理员用户，下一次 API 调用也会返回 `403 admin only`，仅是 UX 小问题而非安全边界。

---

## 4.5. Base URL 自动探测与切换

### 设计目标

App 在两种网络环境下都要能用：

- **家庭局域网**：直连 NAS `http://192.168.31.234:8088/`，TTFB ~10ms
- **外网**：走 Cloudflare Tunnel `https://api.feiyemomo.top/`，TTFB 1.4s+ 且丢包多

两者速度差 70 倍以上。在局域网里用 Tunnel 会让视频流卡顿，在外网里访问局域网 IP 则超时。需要在运行时**自动选择**最快的可达 URL，并在网络切换（如用户走出 WiFi 覆盖）时**立即**切换。

### 核心组件

```
┌──────────────────────────────────────────────────────────────┐
│                  BaseUrlResolver                             │
│                                                              │
│  @Volatile resolved: String    (当前选中的 URL)               │
│  @Volatile lastProbedAt: Long  (上次探测时间)                │
│  AtomicBoolean probing         (防止并发探测)                │
│  var onUrlChanged: (String) -> Unit?  (URL 变化回调)         │
│                                                              │
│  fun current(): String          (TTL 过期则触发异步重探)     │
│  fun forceProbe()               (网络切换时立即重探)         │
│  fun probeLanOnStartup()        (启动时同步重试 LAN)         │
└──────────────────────────────────────────────────────────────┘
              ▲
              │ forceProbe()
┌─────────────┴────────────────────────────────────────────────┐
│              NetworkChangeMonitor                            │
│                                                              │
│  ConnectivityManager.NetworkCallback:                       │
│    onAvailable     → forceProbe()  (新默认网络上线)          │
│    onLost          → forceProbe()  (默认网络掉线)            │
│    onCapabilitiesChanged (VALIDATED) → forceProbe()          │
│      (关键：onAvailable 在 validation 之前 fire)             │
└──────────────────────────────────────────────────────────────┘
```

### 启动流程（`probeLanOnStartup`）

```
HomeCenterApp.onCreate()
    │
    │ prefsManager.baseUrl 为空（用户没手动指定）？
    │
    ▼
probeLanOnStartup()  (后台守护线程异步 — 不阻塞主线程)
    │
    ├── T=0s   立即 forceProbe() (后台)
    │   ├── HTTP GET http://192.168.31.234:8088/api/v1/system/status
    │   │     timeout 1500ms → 成功？resolved = LAN_URL, return
    │   └── HTTP 失败 → TCP Socket connect 192.168.31.234:8088
    │         timeout 1500ms → 成功？resolved = LAN_URL, return
    │
    ├── T=1.5s forceProbe()  (覆盖真机 WiFi 验证窗口)
    ├── T=4s   forceProbe()  (覆盖慢 ROM 的 captive portal 验证)
    ├── T=9s   forceProbe()  (覆盖 DNS resolver 慢初始化)
    └── T=16s  forceProbe()  (last-ditch 兜底)
```

**v1.4.4 关键改动**：

1. **不再阻塞主线程**：旧版 `probeLanOnStartup()` 是 `Application.onCreate` 里的同步 2.4s 阻塞调用，在真机上甚至可能触发 ANR。新版改为通过 `forceProbe()` 异步执行 — 第一个 API 请求可能落到默认的远程 URL，但 LAN 探测成功后会触发 `onUrlChanged` 重建 Retrofit，后续请求自动切到 LAN。

2. **指数退避调度**：1.5s → 4s → 9s → 16s，总等待 16s 覆盖真机 WiFi 验证窗口（实测可达 5-10s）。每次调度都检查 `resolved == LAN_URL`，已切到 LAN 则跳过剩余调度（电池/流量友好）。

3. **TCP socket 直连兜底**：部分 ROM（MIUI / ColorOS）即使 `usesCleartextTraffic=true` + `networkSecurityConfig` 配置正确，也会通过 vendor 注入的「network policy」拦截 OkHttp 的明文 HTTP 请求。但 raw socket 直连不受影响 — 加上 TCP 探测作为 HTTP 失败后的兜底，避免误判为「局域网不可达」。

4. **MainActivity.onCreate / onResume 触发**：进入主页时 WiFi 几乎一定已验证，是再次探测的最佳时机。`forceProbe()` 是 no-op 如果已有探测在飞行中。

**为什么模拟器一次成功但真机要重试？**

模拟器网络通过宿主 PC 桥接，PC 的 WiFi 在用户点 App 图标之前就已经 validated。真机上 `Application.onCreate` 在用户点击图标的瞬间执行，此时 Android 的 WiFi stack 可能还在做 validation（captive portal detection / DNS probe），路由还不可用。`onAvailable` 在 validation 完成之前就 fire，但此时 LAN probe 会失败。`onCapabilitiesChanged` 带 `NET_CAPABILITY_VALIDATED` 才是真正的「路由可用」信号。

### 探测端点

`GET /api/v1/system/status`（JWT 保护）：

- HTTP 200 / 401 → API 存活（401 是 JWT 缺失，但说明 API 进程在跑）
- HTTP 502 / 503 → nginx 起着但 API 容器挂了 → 视为不可达
- HTTP 404 → nginx 路由错误 → 视为不可达
- 网络异常（IOException）→ 不可达

**不用 `/health`**：nginx 对未匹配路径走 `try_files /index.html`，会返回 200 + SPA HTML，即使 API 容器挂了也是 200，造成假阳性。`/api/v1/*` 一定走 `proxy_pass http://api:8080`，所以是真实的 API 健康指示。

**不用 `HEAD`**：Gin 默认对 GET 路由的 HEAD 请求返回 404。已实测：`HEAD /api/v1/system/status` → 404，`GET` → 401。

### 运行时切换

- `current()`：每次调用检查 `now - lastProbedAt > TTL_MS`（5 分钟），若是则异步重探
- `forceProbe()`：网络切换时立即重探（异步线程，不阻塞 caller）
- `onUrlChanged` 回调：URL 变化时调 `AppContainer.resetApi()` 清掉缓存的 Retrofit / Repository

### Dashboard 显示当前路径

```
fragment_dashboard.xml
└── network quality card
    └── pathChip (LinearLayout)
        ├── pathDot (View, 8dp 圆点)
        │   ├── 局域网 → bg circle_online (绿色)
        │   └── 远程   → bg circle_warning (琥珀)
        └── tvPath (TextView)
            ├── "局域网"
            └── "远程"
```

`DashboardFragment.updateBackendPath()` 在 `refreshAll()` 与 5s 状态轮询里调用，从 `container.baseUrlResolver.current()` 推断当前路径并更新 UI。

---

## 5. WebSocket 推送

### 连接

`HomeCenterWebSocket` 在 `MainActivity.onCreate` 中连接：

```kotlin
class HomeCenterWebSocket(
    private val url: String,
    private val token: String,
) {
    private val _events = MutableSharedFlow<WsMessage>(extraBufferCapacity = 64)
    val events: SharedFlow<WsMessage> = _events

    fun connect() { ... }
    fun disconnect() { ... }
}
```

URL 由 `AppContainer.getWsUrl()` 推导：`https://...` → `wss://.../api/v1/ws`。

### 事件类型

| type | 来源 | 含义 |
|---|---|---|
| `device.status` | home-api | 设备上下线 |
| `camera.alert` | home-api (Frigate webhook) | 新报警 |
| `camera.offline` | home-api (health check) | 摄像头离线 |
| `automation.fired` | home-api (Automation Engine) | 自动化规则触发审计 |
| `user.notification` | home-api (notify action) | 用户通知 |

### 订阅模式

```kotlin
// MainActivity
lifecycleScope.launch {
    container.webSocket.events.collect { msg ->
        when (msg.type) {
            "camera.alert" -> {
                // 通过 Activity Result API 通知 CamerasFragment / AlertsFragment
                alertEvents.tryEmit(msg)
            }
            ...
        }
    }
}
```

Fragment 在 `onViewCreated` 收集 `alertEvents`：

```kotlin
container.alertEvents
    .onEach { refreshAlerts() }
    .launchIn(viewLifecycleOwner.lifecycleScope)
```

### 断线重连

`HomeCenterWebSocket` 内部维护 `okhttp3.WebSocket` 引用，`onFailure` 回调里 5 秒后重连。重连时重新发送订阅请求。

---

## 6. 视频播放架构

### 直播（inline 播放）

[CameraAdapter.kt](../app/src/main/java/com/homedatacenter/app/ui/cameras/CameraAdapter.kt) 的 `CameraViewHolder` 持有：

- `thumbnail: Bitmap?` — 缩略图（Compose State）
- `player: ExoPlayer?` — 当前播放器（可为空）
- `triedMp4 / triedHls: Boolean` — 标记已尝试过的协议，避免重复 fallback

### 播放状态机

```
[点击播放按钮]
    │
    ▼
[startPlayback] (v1.5.3：WebRTC 优先 → MP4 → HLS)
    │
    ├── 摄像头在线 + WebRtcClient 可用
    │       │
    │       ▼
    │   [startWebRtcStream]
    │       │  ensureWebRtcClient → init SurfaceViewRenderer
    │       │  fetchIceConfig → POST /api/v1/cameras/{id}/webrtc
    │       │
    │       ├── onConnected → 显示 SurfaceViewRenderer (sub-second 延迟)
    │       │
    │       └── onError → startMp4Playback (fallback)
    │
    └── 摄像头离线 / WebRTC 不可用
            │
            ▼
        [startMp4Playback]
            │  resolveMp4Url, resolveHlsUrl
            │
            ├── MP4 URL 存在 → preparePlayback(useMp4 = true)
            │       │
            │       ▼
            │   [ProgressiveMediaSource + DefaultLoadControl]
            │       │
            │       ├── onPlaybackStateChanged(READY) → 显示画面
            │       │
            │       └── onPlayerError → fallback
            │               │
            │               ▼
            │           preparePlayback(useMp4 = false)  (HLS fallback)
            │               │
            │               └── onPlayerError → 显示错误占位图
            │
            └── MP4 不存在但 HLS 存在 → preparePlayback(useMp4 = false)
                    │
                    └── onPlayerError → 显示错误占位图
```

**为什么 v1.5.3 引入 WebRTC 作为主路径**：

用户反馈 MP4 / HLS 在 LAN 上仍能感知到 ~3s 的端到端延迟（ExoPlayer 缓冲 + go2rtc 转码 + HTTP 报文分片）。WebRTC 直接走 RTP，无 HTTP 包装，理论延迟可压到 100-300ms（host candidate LAN）。go2rtc 同时暴露 WHEP 信令端点 `POST /api/v1/cameras/{id}/webrtc`，后端转发 SDP offer 给 go2rtc 的 `/api/v1/webrtc` 并原样返回 SDP answer。客户端用 `io.getstream:stream-webrtc-android:1.1.0`（Google `org.webrtc:google-webrtc` 的维护后继）创建 recvonly PeerConnection + UNIFIED_PLAN + GATHER_ONCE，等待 ICE gathering 完成后 POST 完整 SDP（non-trickle ICE）。失败场景（远程、UDP 被防火墙阻断）会自动 fallback 到 MP4 / HLS。

**为什么保留 MP4 作为 ExoPlayer 主路径**（早期版本曾用 HLS 优先，后来倒回来）：

go2rtc 的 HLS `Init()` helper（`internal/hls/session.go`）只等 3 秒（60 × 50ms）让 consumer 产出第二个 packet，否则返回 nil → `handlerInit` 响应 404。冷流（go2rtc 刚启动 RTSP 取流）或 HEVC→H.264 转码路径下 3 秒可能不够，ExoPlayer 又不会重试 init.mp4 请求，会把 404 直接上报为 Source error。hls.js 会自动重试，但 ExoPlayer 不会。MP4 stream 是连续 fMP4，不需要 init.mp4，ExoPlayer 的 `ProgressiveMediaSource` 能稳定处理。

### Surface 时序（关键）

`StyledPlayerView` 必须在 `item_camera.xml` 中**静态声明**（默认 `visibility=gone`），不能通过 Compose `AndroidView` 异步创建。`binding.playerView.player = newPlayer` 必须在 `newPlayer.prepare()` **之前**调用。

如果违反：ExoPlayer 在 `prepare()` 时检测到无 surface，仍会创建 MediaCodec 实例，但 codec 在 `configure()` 阶段拿不到 surface，进入「无表面渲染」模式，导致：

```
I  MediaCodec: setOutputSurface -- failed to set consumer usage (BAD_INDEX)
W  ACodec  ...  98% buffers unfetched
```

播放一直卡在 BUFFERING 状态。修复方式是把 Compose 的 `AndroidView { StyledPlayerView(...) }` 改成 XML 静态 `StyledPlayerView`，并在 `prepare()` 之前 `binding.playerView.player = player` + `binding.playerView.visibility = View.VISIBLE`。

详见 [ERRORS.md §20](ERRORS.md#20-exoplayer-prepare-时无-surface-导致-mediacodec-bad_index)。

### 音频路由

```kotlin
newPlayer.setAudioAttributes(
    com.google.android.exoplayer2.audio.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
        .build(),
    /* handleAudioFocus = */ true,
)
```

- `USAGE_MEDIA`：走媒体音量（不是通话 / 铃声 / 闹钟）
- `CONTENT_TYPE_MOVIE`：让系统判断为视频内容，可能影响空间音频等
- `handleAudioFocus=true`：来电时自动暂停，挂断后自动恢复

**类型陷阱**：必须用 `com.google.android.exoplayer2.audio.AudioAttributes`，不能用 `android.media.AudioAttributes`。ExoPlayer 2.x 的 `setAudioAttributes` 方法签名要求前者，编译器会报「Argument type mismatch」。`USAGE_MEDIA` / `CONTENT_TYPE_MOVIE` 常量仍从 `android.media.AudioAttributes` 取。

**后端配合**：后端 `internal/camera/registry.go` 的 `rtspURL()` 在 `cam.Capabilities["audio"]==true` 时给 go2rtc 流 URL 追加 `#audio=aac`，让 go2rtc 通过 ffmpeg 把摄像头的 PCMA 音频转码为 AAC。修改后通过 `UpdateAudio()` 重新调用 `AddStream` 让 go2rtc reload 流定义。HTTP 端点 `PUT /api/v1/cameras/:id/audio {enabled: bool}` 触发上述流程。

### URL 解析

```kotlin
private fun resolveMp4Url(camera: Camera): String {
    if (baseUrl.isNullOrBlank()) return ""
    return "${baseUrl.trimEnd('/')}/api/v1/cameras/${camera.id}/stream.mp4"
}

private fun resolveHlsUrl(camera: Camera): String {
    // 优先用后端返回的绝对 URL
    val hlsUrl = camera.stream?.hlsUrl?.trim().orEmpty()
    if (hlsUrl.isNotEmpty()) return resolveAbsoluteUrl(hlsUrl)

    // 否则自己拼一个 go2rtc 的 HLS URL
    val streamName = camera.stream?.streamName?.trim().orEmpty()
    if (streamName.isEmpty() || baseUrl.isNullOrBlank()) return ""
    return "${baseUrl.trimEnd('/')}/api/stream.m3u8?src=${Uri.encode(streamName)}&mp4="
}
```

### 资源释放

| 触发 | 动作 |
|---|---|
| Fragment `onPause` | `adapter.releaseAllPlayers()` |
| Fragment `onHide` (Nav) | `adapter.releaseAllPlayers()` |
| ViewHolder `onViewDetachedFromWindow` | `holder.releasePlayer()` |
| ViewHolder `onViewRecycled` | `holder.recycle()`（同时 cancel thumbnail job） |
| 用户点击停止 | `stopInlinePlayback()` |

所有 ExoPlayer 实例加入 `activePlayers: MutableSet<ExoPlayer>`，方便统一释放。

### 录像回放

`RecordingsDialog`（BottomSheetDialogFragment）：

- ExoPlayer + `PlayerControlView`
- `ProgressiveMediaSource`（Frigate 录像是有限的 MP4 文件）
- 拖动进度条用 `Player.Listener.onPositionDiscontinuity` 同步
- 关闭对话框时 `player.release()`

#### v1.6.0 / v1.6.1：报警标红 + 进度条吸附 + 报警精确跳转

**报警时段标红（motion-ranges）**：`RecordingsDialog.loadAlertRangesForDay` 改用后端新增的 `GET /api/v1/cameras/{id}/motion-ranges?after&before` 端点替代旧的 `/api/v1/cameras/alerts`。**根本原因**：alerts 端点仅在 Frigate AI 检测到 person/car 等目标时生成事件；对于普通家庭摄像头，AI 阈值常常未被跨过，导致 alerts 列表为空，SeekBar 上无任何红标——即使录像中明显有大量 motion。motion-ranges 端点直接查询 Frigate 录制段的 `motion` 字段（每段 10s，含 motion>0 即视为「有动静」），返回所有像素级活动时间窗口。响应 JSON 为 `{"ranges": [[startUnix, endUnix], ...], "total": N}` 裸数组，客户端用 `kotlinx.serialization.json.JsonArray` 手动解析（`List<Pair<Long,Long>>` 无法用 `@Serializable` 自动解码裸数组）。红标渲染仍由 [AlertRangeOverlay.kt](../app/src/main/java/com/homedatacenter/app/ui/cameras/AlertRangeOverlay.kt) 完成。

**v1.6.1 修复 1：标红太宽**。v1.6.0 后端 `ListMotionRanges` 用 `mergeGapSeconds=10` 合并相邻 motion 段，导致一次持续几分钟的 motion 在 24h 时间线上显示成一条粗红条，用户报告"标红太宽了"。v1.6.1 把 `mergeGapSeconds` 改为 0（不合并），每个 10s Frigate 段独立显示——24h 内约 750 段独立细红线（v1.6.0 仅 77 段合并块），用户能看到 motion 的精确起止时刻。客户端同步把 `AlertRangeOverlay` 的 `minW` 从 4px 改为 2px，避免大量独立段挤在一起看起来仍像一片红墙。

**v1.6.1 修复 2：吸附无感**。v1.6.0 的 `motionSnapRadiusMs = 30_000ms`（30秒）在 24h/720px 屏幕上仅 0.25 像素——用户拖动 SeekBar 释放时手指位置几乎不可能恰好在 0.25px 内，因此完全感受不到吸附效果。v1.6.1 把半径改为 `120_000ms`（120秒，约 1px），匹配用户实际的手指释放精度（thumb ~12px，释放精度 ~2-3px = 240-360s）。120s 让用户在拖动到 motion 附近时能明显感受到"咬住"边缘的效果，但不会过度吸附到远处的 range。

**进度条吸附（snap-to-range）**：`RecordingsDialog.onStopTrackingTouch` 在用户释放 SeekBar 时调用 `snapProgressToRangeEdge(progress)`，检查 `motionRangesRelative` 缓存中最近的 motion range 边缘；若距离 < `motionSnapRadiusMs`（v1.6.1：120s）则自动吸附到该边缘。吸附后的 `progress` 同步更新 SeekBar 视觉与位置标签，并作为 seek 目标传给 ExoPlayer。`motionRangesRelative` 在 `loadAlertRangesForDay` 完成时缓存，避免每次释放都重新拉取。

**v1.6.1 修复 3：报警跳转时序竞态**。v1.6.0 在 `RecordingsDialog.init` 中用 `binding.root.post { openDayForTimestamp(initialTimestamp) }` 试图让 `loadRecordings()` 先跑——但 `binding.root.post` 只在下一帧执行（~16ms 后），而 `loadRecordings()` 是网络请求（100-500ms）。post **总是**比网络请求先完成，此时 `allRecordings` 还是空，`playDayAsPlaylist` 过滤出空列表并显示"该日期无录像"。v1.6.1 引入 `pendingInitialTimestamp` 和 `pendingAutoOpenDayPicker` 两个 flag，在 `loadRecordings` 的 `withContext(Dispatchers.Main)` 完成回调里检查 flag 并触发对应动作，从根本上消除竞态。

**v1.6.1 修复 4：默认进入整天模式**。按用户要求"以整天查看为先"，v1.6.1 在 `loadRecordings` 完成后，如果录像非空且没有 `initialTimestamp`，自动调用 `showDayPicker()` 弹出日期选择器。v1.6.0 默认显示分条录像列表、整天播放是 toolbar 右上的次要入口；v1.6.1 调换位置——默认进入整天模式，分条列表可通过取消日期选择器或点击"返回列表"按钮进入。

**v1.6.2 重构：按天列表 UI**。v1.6.1 的 `showDayPicker()` 是系统 `DatePickerDialog`——用户要先选日期、然后开始播放，看不到哪天有录像、哪天没有。v1.6.2 把默认列表从「60s-bucket 分条列表」彻底改成「按 LOCAL 日期分组的现代化卡片列表」：

- 新 layout `item_day_recording.xml`：左侧大日期数字（28sp）+ 月份/年（11sp），中间星期 + "N 段 · HH:mm:ss 总时长"，右侧绿色 "N 段" chip + 右箭头。整体 `bg_card_rounded` 圆角卡片，14dp 内边距，6dp 外边距。
- 新 adapter `DayRecordingAdapter`：每行一张 `DayRecording` 卡片，点击行触发 `onPlayDay(day)` → `playDayAsPlaylist(day.dayStartCalendar)` 直接进入当天 24h 播放。
- 新分组函数 `groupRecordingsByDay(recordings)`：用 `SimpleDateFormat` UTC 解析每条 `Recording.startAt`，转 `Asia/Shanghai` Calendar，截到 LOCAL 00:00，按 `yyyy-MM-dd` key 分组聚合成 `DayRecording(dayStartCalendar, recordingCount, totalDurationSeconds, totalSizeBytes)`。结果按日期 DESC 排序（最新在前）。
- 新 drawable `bg_chip_green.xml`：绿色圆角矩形（`@color/online` #66BB6A），用于状态 chip 背景。

删除的代码：
- `RecordingAdapter` 内部类（v1.5.x 的 60s-bucket 列表 adapter，已被 `DayRecordingAdapter` 替代）
- `playRecording(recording)` 方法（单段播放路径，已被 `playDayAsPlaylist` 取代为唯一播放入口）
- `loadMoreRecordings()` + `visibleRecordings` + `pageBatchSize` + `isLoadingMore`（分页逻辑，按天列表通常只有 ~7 项不需要分页）
- `showDayPicker()` 方法 + `pendingAutoOpenDayPicker` flag（不再需要弹日期选择器，列表本身就是按天选）
- `binding.btnPlayDay.setOnClickListener` 监听（按钮永久 GONE，保留只为不动 binding call site）
- import `DatePickerDialog` + `ItemRecordingBinding`

保留的代码：
- `pendingInitialTimestamp` + `openDayForTimestamp` + `pendingAlertSeekMs`：报警跳转路径仍生效——点击报警后自动跳到对应日期的整天播放并 seek 到精确时刻
- `playDayAsPlaylist` + `loadAlertRangesForDay` + `snapProgressToRangeEdge`：整天播放的所有逻辑（motion-ranges 标红、SeekBar 吸附、binarySearch 寻找目标 window）保持不变
- `item_recording.xml` layout 文件：留在仓库作为历史参考，不再被任何代码引用

时序：
1. `init` 设置 `pendingInitialTimestamp`（仅当 `initialTimestamp > 0`，即报警跳转时）
2. `setupRecyclerView` 创建 `DayRecordingAdapter`，点击行 → `playDayAsPlaylist(day.dayStartCalendar)`
3. `loadRecordings` 完成后调 `groupRecordingsByDay(recordings)` → `dayAdapter.submitList(dayList)`
4. 如果有 `pendingInitialTimestamp`，调 `openDayForTimestamp(ts)` 直接进入整天播放（绕过列表）
5. 用户在播放中点"返回列表"按钮 → 回到按天列表（`videoContainer.GONE` + `recyclerView.VISIBLE`）

#### v1.6.3：Motion chip 列表 + 后端预聚合

**问题诊断**：用户报告 v1.6.0-v1.6.2 的 SeekBar 红条方案"范围太大不精准"——根本问题是 24h/720px 时间轴上每像素 = 120s，红条永远只能显示成"宽 1-2px 的线段"，无法承载精确信息。再细的吸附半径也只是像素级修补。

**新方案：报警事件 chip 列表**。在 SeekBar 上方新增 `HorizontalScrollView`，每个 motion range 渲染成一个可点击 chip，点击直接 seek ExoPlayer 到该 motion 起始时刻。

UI 组件：
- `motionChipScroller`（HorizontalScrollView）+ `motionChipContainer`（LinearLayout horizontal）添加到 `dialog_recordings.xml` 的 SeekBar 上方
- 每个 chip 是 `item_motion_chip.xml`（TextView，`monospace` 字体，`selectableItemBackground` 涟漪）
- chip 背景色编码 motion 强度（来自后端预聚合 `motion_score` 字段）：
  - 低 motion_score（< max/4）：teal `#4DB6AC`（轻微 motion，如风吹树叶）
  - 中（max/4 ~ max*3/4）：amber `#FFB300`
  - 高（≥ max*3/4）：bright orange `#FF7043`（大量 motion，可能用户自己走过）
  - AI 检测（`peak_objects > 0`）：red `#EF5350`（最优先级，覆盖 motion_score 分级）
- chip 文本："HH:mm:ss · Ns"（UTC 转 LOCAL 时间 + 持续秒数）
- chip 数量 > 200 时按 `motion_score` 排序保留 top 200，再按时间排序展示（避免繁忙日压垮 UI）

数据流：
1. `loadAlertRangesForDay` 调 `listMotionRanges` API 拿到 v1.6.3 富结构响应 `{"ranges": [{start, end, duration, motion_score, segment_count, peak_objects}, ...], "total": N}`
2. 手动用 `kotlinx.serialization.json.JsonObject` 解析（同 v1.6.0 原因——裸数组无法用 @Serializable 自动解码）
3. 每个 range 同时加入 `dayRanges`（供 AlertRangeOverlay 绘制）和 `chipData`（供 chip 列表渲染）
4. `rangeIsAi` 集合记录 `peak_objects > 0` 的索引，传给 `setAlertRanges(ranges, aiIndices)` 让 overlay 用更亮的红色绘制 AI 段
5. `populateMotionChips(chipData, dayStartLocalMillis)` 在主线程生成 chips，每个 chip 设置 `setOnClickListener { seekToMotionStart(chip) }`

Seek 逻辑（`seekToMotionStart`）：
- 复用 `clipStartOffsets.binarySearch(progress)` 找到目标 window
- `player.seekTo(targetWindow, targetPosition)` 跳转
- 同步更新 SeekBar 视觉 + 位置标签
- 与 `onStopTrackingTouch` 共用同一 seek 路径

AlertRangeOverlay 优化（辅助 chip 列表）：
- `minW` 从 2px 降到 1px（chip 承载精确信息，overlay 只做视觉锚点）
- 每个 range 起点画白色 tick dot（直径 0.8px）让用户看清"motion 在此开始"
- AI 检测段用更亮的 `#FF5252`（Material Red A200）区分

后端 v1.6.3：
- `MotionRange` 结构体替代 `[][2]int64`，含预聚合字段 `start/end/duration/motion_score/segment_count/peak_objects`——客户端零现场计算
- `mergeGapSeconds` 从 v1.6.1 的 0s 改为 2s（人眼"同一动作"感知阈值；0s 产生 ~750 段过密，2s 产生 ~109 段正好适合 chip 列表）
- 60s TTL 缓存避免重复请求 Frigate 的 1-2s 慢查询；按 `<camera>:<after>:<before>` key 隔离；超过 32 条自动淘汰最旧的一半

性能：
- 后端：首次请求 1-2s（Frigate 慢），命中缓存 < 5ms
- 客户端：chip 解析 + 渲染 200 个 < 50ms，seek 命中 binarySearch O(log n)
- 内存：每个 chip ~1KB（TextView），200 个 chip 总计 ~200KB

#### v1.6.4 rev4：chip 稀疏化 + 全屏按钮放大 + app 图标放大

**问题诊断**：v1.6.4 rev3 用户反馈三点：
1. "chip 还是太多了，靠边的就不用带字了吧，一直压缩为细线"——rev3 的边缘 chip 仍显示文字，60+ chip 时仍然拥挤。
2. "全屏图标在靠近右下一些，并放大一点"——rev3 的 6dp margin + 24dp iconSize 不够。
3. "app 图标放大一些（源文件在 D:\Projects\Android\home.png）"——图标视觉占比偏小。

**方案 1：chip 数量减半 + 边缘压缩为细线**
- `RecordingsDialog.populateMotionChips`：cap 从 200 → 60（按 `motion_score` 取 top 60）
- `FisheyeChipScroller` 增加 `textThreshold = 0.7f`：
  - 当 `scale < 0.7` 时，`tv.text = ""`（清空文字）+ `cw = thinBarWidthPx`（宽度强制为 3dp）
  - chip 的 label 通过 `tv.tag = label` 保存，在 `scale >= 0.7` 时还原 `tv.text = label`
  - alpha 保持 1.0 不淡化（符合"而不是隐藏"）
  - 边缘 chip 变成无文字的彩色细条（仅显示背景色），中心 chip 仍显示"HH:mm"标签

**方案 2：全屏按钮靠右下 + 放大**
- `dialog_recordings.xml` 中 `btnFullscreen`：
  - `marginEnd/marginBottom`：6dp → 2dp（更靠右下角）
  - `iconSize`：默认 24dp → 36dp（显式指定放大）
  - `padding`：8dp → 12dp（点击区域也增大）

**方案 3：app 图标放大**
- `scripts/generate_app_icon.ps1` 自动检测 `home.png` 的内容边界（实际内容 477×434 在 1024×1024 画布中，原本有 ~50% 透明边距）
- 裁剪到内容 bbox，然后放到 8% padding 的目标画布上（v.s. 之前直接缩放保留 ~15% padding）
- 视觉放大约 15%
- 生成 5 密度 × 2 形状 = 10 个 PNG，分别覆盖 mipmap-mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi

**保留 v1.6.4 rev3 的功能**：
- chip 变窄（padding 4dp/2dp、margin 1dp、textSize 9sp）
- chip label "HH:mm"（去掉秒和时长）
- 倍速菜单 `ListPopupWindow` 下拉样式
- `FisheyeChipScroller` 不滚动 ViewGroup + fit-to-screen + minScale=0.6
- `PlayerFullscreenHelper.controllerSyncViews` + tap-to-toggle
- `playerHostFrame` 作为 `secondaryPlayerView` 全屏时 MATCH_PARENT
- `formatSpeed` 修复 1.5f → "1.5x"

**报警推送精确跳转**：`AlertsFragment.onJumpCamera` / `DashboardFragment.jumpToCamerasWithAlert` 现在直接 `startActivity(CameraDetailActivity)`，Intent extra `EXTRA_INITIAL_TIMESTAMP = alert.startTime.toLong()`。流程：
1. `CameraDetailActivity.onCreate` 检测到 `EXTRA_INITIAL_TIMESTAMP > 0`，`binding.root.post { showRecordings(initialTs) }`（post 是为了让 `startPlayback()` 的直播流先初始化完成，避免 surface 冲突）。
2. `showRecordings(initialTs)` 构造 `RecordingsDialog(context, camera, container, initialTimestamp = initialTs)`。
3. `RecordingsDialog.init` 设置 `pendingInitialTimestamp = initialTimestamp`（**v1.6.1：不再用 `binding.root.post`，改在 `loadRecordings` 完成回调中触发**）。
4. `loadRecordings` 的主线程回调检查 `pendingInitialTimestamp > 0L`，调用 `openDayForTimestamp(initialTimestamp)` 并清零 flag。
5. `openDayForTimestamp` 计算报警所在 LOCAL 日期（00:00），调用 `playDayAsPlaylist(dayCal, seekToMs = initialTimestamp * 1000)`。
6. `playDayAsPlaylist` 构建 24h 播放列表，注册 `Player.Listener`，`if (seekToMs > 0L) pendingAlertSeekMs = seekToMs`。
7. ExoPlayer 首次 `STATE_READY` 时 listener 触发：`progress = (seekMs - dayStartLocalMillis).coerceIn(0, dayTotalMs)`，用 `clipStartOffsets.binarySearch(progress)` 找到目标 window，`seekTo(window, position)`。
8. 同步 SeekBar 视觉 + 位置标签到报警时刻。

失败回退：找不到匹配 camera、网络异常时显示 toast 并回退到旧的「切换到 cameras tab」行为。DashboardFragment 的实时报警横幅也注册了点击监听，复用 `jumpToCamerasWithAlert` 跳转逻辑，并通过 `lastLiveAlert` 缓存最近一次报警以避免横幅自动消失后点击跳到过期时间戳。

---

## 7. 主题与夜间模式

### 主题层级

```
Theme.Material3.DayNight.NoActionBar
    │
    └── Theme.HomeDatacenter
            ├── colorPrimary = @color/primary
            ├── colorSecondary = @color/secondary
            ├── colorOnPrimary = @color/on_primary
            └── ...
```

### 夜间模式

`values-night/themes.xml` 与 `values-night/colors.xml` 覆盖颜色，由 `AppCompatDelegate.setDefaultNightMode` 切换：

```kotlin
object ThemeManager {
    fun apply(mode: Int) {
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
```

### 切换流程

```kotlin
// SettingsFragment
binding.switchTheme.setOnCheckedChangeListener { _, checked ->
    val mode = if (checked) MODE_NIGHT_YES else MODE_NIGHT_NO
    ThemeManager.apply(mode)
    prefsManager.themeMode = mode

    // 延后到下一帧，避免在事件回调里 recreate Activity 卡顿
    binding.root.post {
        requireActivity().recreate()
    }
}
```

### Material 3 组件

- `BottomNavigationView` + `ActiveIndicator` 胶囊样式（`Widget.Material3.BottomNavigationView.ActiveIndicator`）
- `TextInputLayout` + `OutlinedBox` 风格
- `MaterialButton` + `cornerRadius`
- `Chip`（报警行的剪辑 / 快照按钮）

---

## 8. Compose 与 View 互操作

`CameraCard`（Compose）通过 `ComposeView` 嵌入到 `item_camera.xml`（View 体系 RecyclerView）：

```kotlin
binding.composeView.setContent {
    MaterialTheme {
        CameraCard(
            camera = camera,
            thumbnail = thumbnail,
            ...
            onPlay = { startInlinePlayback(camera) },
            onStop = ::stopInlinePlayback,
        )
    }
}
```

`ComposeView` 用 `DisposeOnDetachedFromWindowOrReleasedFromPool` 策略，RecyclerView 复用时 Compose 树会被释放。

### 为什么不全用 Compose？

- 项目从 View 体系起步，已有大量 `fragment_*.xml` / `item_*.xml` 布局
- Compose 的 ExoPlayer 集成（`PlayerView`）需要额外的 wrapper
- Compose 列表（LazyColumn）在大列表 + 视频播放场景下性能不及 RecyclerView + `DiffUtil`

策略：新卡片式 UI（`CameraCard`、`StatCard`）用 Compose；老 Fragment / Dialog 用 View。

---

## 9. 错误处理与重试

### 网络层

- OkHttp `retryOnConnectionFailure(true)` 自动重试 TCP / TLS 失败
- `pingInterval(30s)` 心跳防止 NAT 超时
- 各 Repository 方法返回 `Result<T>`，调用方处理 `onFailure`

### UI 层

- 每个 Fragment 有 `loading` / `error` / `data` 三态
- `error` 显示重试按钮，点击重新拉取
- 视频 / 缩略图加载失败显示错误占位图

### 日志

- `okHttpClient` 启用 `HttpLoggingInterceptor.Level.BASIC`（debug 构建）
- 关键路径加 `android.util.Log.w/e`（如 `CameraAdapter.TAG`、`HomeCenterWebSocket.TAG`）
- crash log 通过 `Thread.setDefaultUncaughtExceptionHandler` 上报到 `PrefsManager`（本地存储，未来可加远程上报）

---

## 10. 后端兼容性

### 版本协商

后端 `home-datacenter` 项目与 App 同步演进。当前没有显式版本号协商，依赖：

- `ApiResponse.code == 0` 判成功
- 后端新增字段不破坏旧客户端（OkHttp + kotlinx.serialization 的 `ignoreUnknownKeys = true`）
- 后端废弃字段先保留一段时间（deprecation period），等所有客户端更新后再删

### 接口契约

| 接口 | 契约 |
|---|---|
| `POST /api/v1/auth/bind` | 必须返回 `{ token: string }`，同时 `Set-Cookie: home_token` |
| `GET /api/v1/cameras` | 必须返回 `[{ id, name, vendor, stream: { hls_url, webrtc_url, stream_name }, ... }]` |
| `GET /api/v1/cameras/{id}/frame` | 返回 JPEG 图片（200）或 404 |
| `GET /api/v1/cameras/{id}/stream.mp4` | 返回 fMP4 流（infinite） |
| `GET /api/v1/weather` | 返回 `{ code, message, data: { temp, humidity, ... } }` |

如后端破坏上述契约，必须 bump major 版本号并通知所有客户端。

---

## 11. 性能优化

### 启动

- `HomeCenterApp` 不做重活，仅创建 `AppContainer`
- `MainActivity.onCreate` 异步连 WebSocket（`lifecycleScope.launch`）
- Fragment 用 `viewBinding` 而非 `findViewById`
- `RecyclerView` 用 `ListAdapter` + `DiffUtil`，避免全量 `notifyDataSetChanged`

### 列表

- 摄像头列表 `DiffCallback` 按 `id` 比较，内容变化才重 bind
- 缩略图加载用 `Dispatchers.IO`，不阻塞主线程
- ExoPlayer 在 ViewHolder detach 时立即释放

### 网络

- OkHttp 连接池复用
- `pingInterval(30s)` 保活，避免重建 TCP
- HLS segment 缓存走 OkHttp 内部缓存（默认 10MB）

### 实时设备状态（v1.5.1）

`DeviceAdapter` 显示三态（已吊销 / 在线 / 离线），在线状态来自 `SystemStatus.onlineDeviceIds`：

```kotlin
// DeviceAdapter.kt — Adapter 持有 immutable snapshot
var onlineDeviceIds: Set<Long> = emptySet()
    set(value) {
        field = value
        notifyItemRangeChanged(0, itemCount)
    }

// DevicesFragment.kt — 从 PrefsManager.cachedSystemStatus 解码后推送
private fun applyOnlineSnapshot() {
    val raw = container.prefsManager.cachedSystemStatus ?: return
    val ids = container.getRepository().decodeSystemStatus(raw).onlineDeviceIds ?: emptyList()
    adapter.onlineDeviceIds = ids.toSet()
}
```

`DashboardFragment` 通过 5s 轮询 + WS `online_list` / `device.status` 事件维护 `onlineDeviceIds` 缓存到 `PrefsManager.cachedSystemStatus`（JSON 字符串）。`DevicesFragment.onResume` 与 `revokeDevice` 之后强制 `getSystemStatus(refreshCache=true)` 刷新缓存并 `applyOnlineSnapshot()` 推送到 Adapter。**没有把 WS 客户端提升到 AppContainer** —— 共享缓存而非共享连接，避免 DashboardFragment 已有的订阅/重连逻辑被破坏。

---

## 12. 网络详情页（v1.5.1）

`NetworkDetailActivity` 是 Dashboard 网络质量卡片的二级页面，展示三类信息：

1. **NetworkStatus**（来自 `GET /api/v1/network/status`，支持 `refresh=true` 强制刷新）：
   - IPv6（启用 / 可达 / 地址）
   - NAT（类型 / 公网 IP / 端口）
   - P2P（支持 / 原因）
   - Relay（可用 / 类型）
   - 初始策略 vs 实际策略 + 5 颗星质量评分 + 检测时间
2. **ServerEndpoint**（来自 `GET /api/v1/network/p2p/server-endpoint`）：服务端公网 IP / 端口 / IPv6 / NAT 类型 / 实际策略。**注意**：该端点仅在 LAN 可用（WebRTC 探测），远程路径下静默降级显示 "-"。
3. **SystemStatus**（来自 `GET /api/v1/system/status`）：MQTT 在线状态 + WebSocket 在线客户端数 + 服务运行时长。

### MQTT 调试入口

后端目前未暴露 `/mqtt/*` 端点，MQTT 调试入口直接合并到网络详情页的"MQTT / WebSocket"分区，仅展示 `mqtt_connected` + `ws_clients` + `uptime_seconds` 三个字段。如果后续后端新增 `/mqtt/subscribe` 或 `/mqtt/status` 端点，可直接在 NetworkDetailActivity 增加新分区，无需改动其他模块。

### 进入方式

Dashboard 网络质量卡片（`fragment_dashboard.xml` 中 `cardNetwork`）点击跳转：

```kotlin
// DashboardFragment.kt
binding.cardNetwork.setOnClickListener {
    startActivity(Intent(requireContext(), NetworkDetailActivity::class.java))
}
```

不新增底部 Tab —— 当前 5 个 Tab（主页 / 摄像头 / 报警 / 设备 / 设置）已满 Material `BottomNavigationView` 推荐上限。二级页面方案更符合 Material 设计规范。

---

## 13. 安全考量

### 本地存储

- JWT 持久化在 `EncryptedSharedPreferences`（AES-GCM 256，master key 在 Android Keystore）
- 不存储 access_key（绑定后即用 JWT，access_key 一次性）
- 日志不打印 token / access_key

### 传输

- 全程 HTTPS / WSS
- `network_security_config.xml` 仅允许 LAN 直连 cleartext，公网强制 HTTPS

### WebView

- `onPermissionRequest` 仅 grant 当前请求的资源（不让 WebView 拿多余权限）
- 不允许 `addJavascriptInterface`（防 JS 反射攻击）

### JWT 过期与吊销

- JWT 有效期 365 天
- 后端可吊销单个 device（`devices.revoked_at`），客户端下次调用会 401，跳回登录
- 客户端不主动检查 JWT 过期，靠后端 401 触发登出

详见后端 [`docs/security.md`](https://github.com/feiyemomo/home-datacenter/blob/main/docs/security.md)。

---

## 14. 摄像头列表与详情页重构（v1.5.2）

### 列表层：紧凑缩略图卡片

`CameraAdapter` 不再持有 ExoPlayer 实例——之前的内联直播模式让滚动每行都要分配 MediaCodec、attach surface、prepare media source，在 Cloudflare Tunnel 1.4s+ TTFB 下表现灾难性（首帧 5-10s 出不来，且容易触发 BAD_INDEX）。v1.5.2 改为：

- `CameraCard` Compose 卡片仅渲染 128×72 缩略图 + 名称 / 厂商 / codec 徽章
- 缩略图通过 `GET /api/v1/cameras/:id/frame` 异步加载，LRU 16 条缓存避免回滚重复请求
- 点击整卡进入 `CameraDetailActivity`（构造函数简化为 `onClick: (Camera) -> Unit` + 可选的 `baseUrl / token / okHttpClient` 用于缩略图加载）

`releaseAllPlayers()` 保留为 no-op 以维持 `CamerasFragment.onPause / onDestroyView` 调用兼容。

### 详情页：单一 ExoPlayer 实例

`CameraDetailActivity` 顶部插入 `StyledPlayerView`（200dp 高、`fixed_width` 模式、`show_buffering=when_playing`），下方三按钮动作区：

- `重新加载`（`btnReloadStream`）：释放旧 player 并重新 `startPlayback()`
- `查看录像`（`btnRecordings`）：打开 `RecordingsDialog`
- `报警记录`（`btnAlerts`）：打开 `AlertsDialog`

直播流播放逻辑从旧 `CameraAdapter` 迁移至 `CameraDetailActivity.preparePlayback()`：

- **MP4 优先**：`/api/v1/cameras/:id/stream.mp4`（home-api 代理 go2rtc fMP4），ExoPlayer `ProgressiveMediaSource` 直接消费无限 fMP4 流
- **HLS 容错**：MP4 失败时自动切到 `/api/stream.m3u8?src=<stream_name>&mp4=`（`HlsMediaSource` + low-latency `MediaItem.LiveConfiguration` 3s 目标偏移）
- **Surface-before-prepare 模式**：`binding.playerView.player = newPlayer` 必须在 `prepare()` 之前执行，否则 MediaCodec 进入 no-surface 模式，后续 `setOutputSurface` 失败（BAD_INDEX），98% buffer unfetched
- **DefaultLoadControl**：minBuffer 2s / maxBuffer 5s / forPlayback 1s / forRebuffer 1.5s，首帧 ~1-2s 出现
- **ExoPlayerRendererFactory**：模拟器过滤 c2.goldfish.h264.decoder（初始化成功但每帧报错），真机启用 software-decoder fallback
- **认证**：`Authorization: Bearer <token>` + `Cookie: home_token=<token>` 双 header（同时满足 home-api JWT 校验和 go2rtc cookie 透传）
- **生命周期**：`onPause` 释放 player（避免后台占用 MediaCodec），用户回到详情页时点击"重新加载"恢复播放——而非自动恢复，避免通知栏下拉等短暂遮挡触发 buffering 循环

### v1.5.3：WebRTC 直播主路径

`CameraDetailActivity.setupVideo()` 中新增 `WebRtcClient`（[util/WebRtcClient.kt](../app/src/main/java/com/homedatacenter/app/util/WebRtcClient.kt)）：

- **PeerConnectionFactory 初始化**：使用 `io.getstream:stream-webrtc-android:1.1.0`（Google `org.webrtc:google-webrtc` 的维护后继），创建 `EglBase` + `JavaAudioDeviceModule` + H264 硬件编解码器
- **PeerConnection 配置**：`UNIFIED_PLAN` + `GATHER_ONCE`（non-trickle ICE，等待所有 candidate 收集完成再 POST）
- **SDP 信令**：`POST /api/v1/cameras/{id}/webrtc`，Content-Type: `application/sdp`（原始 SDP 文本，非 JSON），后端返回 SDP answer
- **ICE 配置**：`GET /api/v1/cameras/ice` 返回 `{ice_servers, webrtc_base}`，缓存复用
- **recvonly 方向**：`RtpTransceiverDirection.RECV_ONLY`，音频 + 视频各一
- **认证**：`Authorization: Bearer <token>` + `Cookie: home_token=<token>` 双 header
- **SurfaceViewRenderer**：与 ExoPlayer `StyledPlayerView` 并列于 `videoContainer`，初始 `visibility=gone`，WebRTC 模式下显示并隐藏 `StyledPlayerView`
- **Fallback 链路**：WebRTC → MP4 → HLS → 错误占位图，每一步失败自动切到下一步
- **生命周期**：`onCreate` 中 `webRtcClient.init()`，`onDestroy` 中先 `surfaceRenderer.release()` 再 `webRtcClient.shutdown()`（EGL 释放顺序）

### v1.5.3：播放器全屏 + 播放速度独立按钮

- `PlayerFullscreenHelper`（[util/PlayerFullscreenHelper.kt](../app/src/main/java/com/homedatacenter/app/util/PlayerFullscreenHelper.kt)）统一处理三个播放器宿主（`CameraDetailActivity`、`RecordingsDialog`、`AlertsDialog`）的横屏全屏：
  - 调用 `setFullscreenButtonClickListener` 让 ExoPlayer 2.x `StyledPlayerView` 的内置全屏按钮显示
  - `secondaryPlayerView` 参数：可选的次播放器视图（WebRTC 的 `SurfaceViewRenderer`），在全屏时同样展开到 `MATCH_PARENT`
  - `fullscreenButton` 参数：独立的 overlay 全屏按钮，用于 WebRTC 模式（`StyledPlayerView` GONE 时内置按钮不可达）以及录像/报警 dialog（避免重写 controller 布局）
  - `toggleFullscreen()`：外部按钮调用，切换 `isFullscreen` 并 `applyFullscreenState()`
  - `configChanges=orientation|screenSize|keyboardHidden|screenLayout` 已在 `CameraDetailActivity` 的 manifest 中声明，转屏不重建 Activity，ExoPlayer 不被销毁
- 录像 / 报警 dialog 新增 `btnPlaybackSpeed` overlay（`PopupMenu` 0.5x / 1x / 1.5x / 2x），从播放器齿轮设置菜单中移出
- `resize_mode="fit"`（信箱模式），避免之前 `resize_mode="zoom"` 裁切画面导致"图像大小与手机屏幕不适应"

### v1.5.3：状态栏间距 + Dashboard 卡片等高 + Frigate 录像状态

- `activity_camera_detail.xml` 根 `ScrollView` 加 `android:fitsSystemWindows="true"`，整个布局推到状态栏下方，避免 MaterialToolbar 被时间/电池/icon 遮挡
- `item_stat_card.xml` 重写：`FrameLayout` 固定 120dp 高度，`tvValue` 用 `weight=1 + gravity=bottom|start` 强制 4 张状态卡片（设备 / MQTT / 摄像头 / 运行时长）等高对齐
- `DashboardFragment.formatUptime()` 改为紧凑格式：`30d 5h` / `5h 23m` / `12m 30s`（替代中文 "30天 5小时"），避免运行时间显示不全
- `Camera.isRecordingEnabled` 扩展属性：从 `camera.meta["recording"]` 解析 `{enabled, retention_days, segment_seconds}`，不再硬编码 `false`（Frigate 实际已开启持续录像）
- `DashboardFragment.updateNetworkStatus()`：`tvNetworkStrategy` 改为读 `BaseUrlResolver.current()` 显示"局域网/远程"，与之前的 `status.strategy`（P2P/Relay）冲突；同时隐藏 `pathChip` 冗余显示

### v1.5.4：状态栏间距全局修复 + 16KB page size 兼容性

v1.5.3 只给 `CameraDetailActivity` 加了 `fitsSystemWindows`，但 `MainActivity` 的根 `ConstraintLayout` 没加，导致 DashboardFragment / CamerasFragment / AlertsFragment 等所有 Fragment 顶部仍贴着状态栏；同时 v1.5.3 升级 stream-webrtc-android 后真机启动时弹出 Android 15+ "16 KB page size not compatible" 警告。

- `activity_main.xml` 根 `ConstraintLayout` 加 `android:fitsSystemWindows="true"`，所有 Fragment 自动从状态栏下方开始；`BottomNavContainer` 仍约束在底部不受影响
- `fragment_dashboard.xml` 中 4 个 `<include>` 的 `layout_height` 从 `wrap_content` 改为 `120dp` — Android include 机制会用外部 `layout_height` 覆盖被 include 的根 View 的高度，v1.5.3 写的 `120dp` 在 include 处被 `wrap_content` 覆盖，固定高度失效，导致 4 卡片仍不等高、运行时间字符串被挤压显示不全
- `io.getstream:stream-webrtc-android` 从 `1.1.0`（2024-06，PT_LOAD p_align=0x1000 即 4KB）升级到 `1.3.10`（2025-09，p_align=0x4000 即 16KB），消除 `libjingle_peerconnection_so.so` 的 LOAD segment 不对齐警告
- Compose BOM 从 `2024.09.00` 升级到 `2025.06.00`，传递升级 `androidx.graphics.path` 到正式版（p_align=0x4000），消除 `libandroidx.graphics.path.so` 警告
- `app/build.gradle.kts` 新增 `packaging { jniLibs { useLegacyPackaging = false } }`，让 AGP 在 APK 打包时将 `.so` 文件以未压缩 + 16KB 对齐方式存储（与上述 .so 内部 16KB LOAD 对齐配合，完全消除 Android 15+ 启动警告）
- 版本号 `1.5.3 → 1.5.4`，`versionCode 23 → 24`

**Android 16KB page size 兼容性原理**：

- Android 15+ 设备的页面大小可配置为 16KB（之前固定 4KB），设备启动时若加载的 `.so` 文件 PT_LOAD segment `p_align < 16384`，OS 会以 "page size compatible mode" 运行该应用（功能正常但内存映射有性能损耗），并弹出兼容性警告
- 修复需要 .so 文件本身用 `-Wl,-z,max-page-size=16384` 链接选项重新编译（这是 stream-webrtc-android 1.3.x 已做的事），APK 内的 zip 对齐由 AGP `useLegacyPackaging=false` 处理
- 验证：用 Python `struct.unpack_from` 读取 APK 内 `.so` 文件的 ELF header，检查所有 PT_LOAD segment 的 `p_align` 是否等于 0x4000

### PTZ 控制图标统一

5 个 PTZ 图标（`ic_ptz_up / down / left / right / stop`）替换为 Material chevron 系列（`expand_less / expand_more / chevron_left / chevron_right`）+ 环形 `cancel` 风格的 stop。之前的 up/down 是全宽 arrow_upward/downward 而 left/right 是小三角，视觉不一致；统一 chevron 后 D-pad 更现代、更符合 PTZ 控件惯例。

### 报警项布局精简

`item_alert.xml` + `AlertListAdapter` 重写——删除：

- "截图"按钮（后端无对应端点）
- 可展开详情区（`expandableDetails`）
- 大图预览模态（`ivLargePreview` + `progressLarge` + `tvLargeError`）
- 元数据行（事件 ID / 检测区域 / 抓拍时间）
- 跳转摄像头按钮（与"查看录像"重复）

保留：72×48 缩略图 + 标签 + 摄像头名 + 时间 + "查看录像" Chip（点击跳转摄像头 tab）。修复了原布局因 metadata 行过多导致"前门"等长摄像头名截断、Chip 文字溢出问题。

