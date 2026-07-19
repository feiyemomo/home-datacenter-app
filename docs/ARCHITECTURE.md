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
probeLanOnStartup()  (主线程同步)
    │
    ├── Attempt 1: GET http://192.168.31.234:8088/api/v1/system/status
    │     timeout 1000ms → 成功？resolved = LAN_URL, return
    │
    ├── Thread.sleep(400ms)  (backoff)
    │
    ├── Attempt 2: 重试（覆盖真机 WiFi 验证窗口）
    │     timeout 1000ms → 成功？resolved = LAN_URL, return
    │
    └── 全部失败 → 调度 3s 后异步 probeSync() 兜底
                    (届时 onCapabilitiesChanged 也会触发，重复调用是 no-op)
```

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
[startInlinePlayback]
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
    │               └── onPlayerError → stopInlinePlayback
    │
    └── MP4 不存在但 HLS 存在 → preparePlayback(useMp4 = false)
            │
            └── onPlayerError → stopInlinePlayback
```

**为什么 MP4 优先**（早期版本曾用 HLS 优先，后来倒回来）：

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

---

## 12. 安全考量

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
