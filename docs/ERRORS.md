# 错误教训（Lessons Learned）

本文件按时间倒序记录开发过程中遇到的典型问题、根因分析和修复方法。每条都包含：

- **症状**：用户看到的表象
- **根因**：技术层面的真实原因
- **修复**：代码层面的具体改动
- **预防**：以后如何避免再踩

---

## 1. 启动崩溃：`Failed to resolve attribute at index 2`

### 症状

App 安装后图标点击立即闪退。logcat：

```
java.lang.UnsupportedOperationException: Failed to resolve attribute at index 2
    at android.content.res.TypedArray.getColorStateList
    at com.google.android.material.bottomnavigation.NavigationBarView.<init>
    at com.google.android.material.bottomnavigation.BottomNavigationView.<init>
```

### 根因

`res/values/bottom_nav_styles.xml` 中 `BottomNavActiveIndicator` 样式使用 Material 3 父样式：

```xml
<style name="BottomNavActiveIndicator"
    parent="Widget.Material3.BottomNavigationView.ActiveIndicator">
```

但应用主题 `Theme.HomeDatacenter` 的 parent 是 Material 2：

```xml
<style name="Theme.HomeDatacenter"
    parent="Theme.MaterialComponents.DayNight.NoActionBar">
```

Material 2 主题不提供 `colorSecondary` / `colorPrimaryContainer` 等 Material 3 必需属性，`TypedArray.getColorStateList` 取不到值就抛 `UnsupportedOperationException`。

### 修复

将 `values/themes.xml` 与 `values-night/themes.xml` 中 `Theme.HomeDatacenter` 的 parent 改为：

```xml
parent="Theme.Material3.DayNight.NoActionBar"
```

### 预防

只要在 `styles.xml` / `bottom_nav_styles.xml` 里写了 `Widget.Material3.*` 父样式，应用主题就必须是 `Theme.Material3.*`，反之亦然。混用 M2 / M3 必崩。

---

## 2. 底部 Tab 文字与图标重叠

### 症状

底部导航栏（Material 3 ActiveIndicator 胶囊样式）的图标和文字挤在一起，文字被裁切。

### 根因

`activity_main.xml` 中 `BottomNavigationView` 高度为 56dp（Android 默认），但 Material 3 ActiveIndicator 胶囊（约 40dp） + 图标（24dp） + 文字（14sp）实际所需高度超过 56dp。

### 修复

[activity_main.xml](../app/src/main/res/layout/activity_main.xml) 中将高度从 56dp 改为 72dp：

```xml
<com.google.android.material.bottomnavigation.BottomNavigationView
    android:id="@+id/bottom_nav"
    android:layout_height="72dp"
    ... />
```

同时移除无效的 `app:itemSpacingHorizontal` 属性（该属性在 BottomNavigationView 上不存在，会导致编译失败）。

### 预防

使用 Material 3 ActiveIndicator 时，BottomNavigationView 高度至少 72dp，否则胶囊 + 图标 + 文字会重叠。

---

## 3. 「安装包无效」/ Package Invalid

### 症状

用户反馈从微信群发的 APK 安装时提示「安装包无效」。

### 根因

两层原因叠加：

1. **签名冲突**：用户手机已装旧版本，旧版本的 debug 签名来自另一台机器的 `~/.android/debug.keystore`，与新构建的 debug APK 签名不一致。Android 包安装器检测到 SHA-1 不匹配就拒绝覆盖。
2. **传输损坏**：微信 / QQ 可能压缩或重命名 APK，少数情况下会破坏 APK ZIP 结构。

### 修复

创建项目级 debug keystore，仓库内置：

```bash
keytool -genkeypair -v \
  -keystore app/keystore/home-debug.jks \
  -storepass home123 -keypass home123 \
  -alias home-debug \
  -keyalg RSA -keysize 2048 \
  -validity 36500 \
  -dname "CN=HomeDatacenter Debug"
```

`build.gradle.kts` 配置 debug 构建固定使用此 keystore（v1 + v2 + v3 全签名）：

```kotlin
signingConfigs {
    create("projectDebug") {
        storeFile = file("keystore/home-debug.jks")
        storePassword = "home123"
        keyAlias = "home-debug"
        keyPassword = "home123"
        enableV1Signing = true
        enableV2Signing = true
        enableV3Signing = true
    }
}
buildTypes {
    debug { signingConfig = signingConfigs.getByName("projectDebug") }
}
```

用户首次需卸载旧版，之后所有 debug 构建都能直接覆盖安装。

### 预防

- 任何多人协作或需要持续分发 debug 构建的项目，**必须**使用项目级 keystore，不要依赖每台机器的 `~/.android/debug.keystore`
- 给用户分发 APK 时优先用 HTTP / 网盘直链，避免微信 / QQ 压缩
- AGP 8+ 默认对 targetSdk ≥ 30 禁用 v1 签名，部分国产 ROM（如 MIUI 老版本）会以 v2-only 拒绝安装。本项目显式开启 v1+v2+v3 三重签名

---

## 4. 直播打开后 App 闪退：`minBufferMs cannot be less than bufferForPlaybackAfterRebufferMs`

### 症状

App 启动正常，进入摄像头页点击任意摄像头预览图后闪退。logcat：

```
java.lang.IllegalArgumentException: minBufferMs cannot be less than bufferForPlaybackAfterRebufferMs
    at com.google.android.exoplayer2.DefaultLoadControl.assertGreaterOrEqual
    at com.google.android.exoplayer2.DefaultLoadControl$Builder.setBufferDurationsMs
    at com.homedatacenter.app.ui.cameras.CameraAdapter$CameraViewHolder.preparePlayback(CameraAdapter.kt:225)
```

### 根因

`CameraAdapter.preparePlayback` 中调用：

```kotlin
DefaultLoadControl.Builder()
    .setBufferDurationsMs(
        minBufferMs = 1000,
        maxBufferMs = 5000,
        bufferForPlaybackMs = 1000,
        bufferForPlaybackAfterRebufferMs = 1500,
    )
```

ExoPlayer `DefaultLoadControl` 在 `assertGreaterOrEqual` 中强校验：

- `minBufferMs >= bufferForPlaybackMs`
- `minBufferMs >= bufferForPlaybackAfterRebufferMs`
- `maxBufferMs >= minBufferMs`

`1000 < 1500` 违反第二条。

### 修复

把 `minBufferMs` 从 1000 改为 2000：

```kotlin
.setBufferDurationsMs(
    minBufferMs = 2_000,
    maxBufferMs = 5_000,
    bufferForPlaybackMs = 1_000,
    bufferForPlaybackAfterRebufferMs = 1_500,
)
```

校验通过：`2000 >= 1000` ✓，`2000 >= 1500` ✓，`5000 >= 2000` ✓。

### 预防

- 调用 ExoPlayer `DefaultLoadControl.Builder().setBufferDurationsMs(...)` 时必须满足三个不等式约束，源码注释里有写但容易忽略
- 在低延迟调参前先在 logcat 抓一次启动播放，确认参数被接受
- 复用同样的约束检查思路：任何 builder 模式 API 的参数依赖都要先看 javadoc / 源码的 assert 方法

---

## 5. `connection closed` 登录失败

### 症状

用户反馈登录时提示 `绑定失败，请检查凭据：connection closed`。

### 根因

实际上**不是**代码 bug，是网络问题：

1. **VPN 超时**：用户挂的 VPN 已掉线，OkHttp 直连 Cloudflare Tunnel 时 TLS 握手中途断开
2. **Access key 误读**：用户在 access_key 里把数字 `0` 看成字母 `O`，例如：
   ```
   f2bda596de5d1b4f0c99e5a15dd6293c54aa34dcf86ac554be95a470dcOOcdab  ← 错
   f2bda596de5d1b4f0c99e5a15dd6293c54aa34dcf86ac554be95a470dc00cdab  ← 对
   ```

但排查过程中也发现 Cloudflare Tunnel 在移动网络上偶尔会主动关闭 HTTP/2 stream，OkHttp 抛 `connection closed`。

### 修复

[NetworkFactory.kt](../app/src/main/java/com/homedatacenter/app/data/api/NetworkFactory.kt) 中加固 OkHttp：

```kotlin
val builder = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .callTimeout(90, TimeUnit.SECONDS)
    .pingInterval(30, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .protocols(listOf(okhttp3.Protocol.HTTP_1_1))  // 强制 HTTP/1.1
```

强制 HTTP/1.1 牺牲了多路复用，但避免了 Cloudflare Tunnel 在移动网络上偶发的 HTTP/2 stream 异常关闭。

### 预防

- 跨国移动网络下的 HTTPS 调用必须有重试 + 长 timeout + HTTP/1.1 fallback
- 提示用户输入十六进制密钥时，UI 可以用等宽字体（`android:fontFamily="monospace"`）防 `0` / `O` 混淆
- 报错时不要只说「失败」，至少把 hostname / `IOException.message` 暴露给 debug 日志

---

## 6. 直播画面不动：ProgressiveMediaSource 不适合无限 fMP4 流

### 症状

摄像头页点击预览图后进入播放状态，但画面卡在第一帧，进度条不动，几秒后超时。

### 根因

最初的设计是 MP4 优先：

```kotlin
preparePlayback(camera, mp4Url, hlsUrl, useMp4 = true)
```

go2rtc 的 `/stream.mp4` 返回的是**无限**的 fragmented MP4 流，没有 moov box 终止符。ExoPlayer 的 `ProgressiveMediaSource` 是为**有限** MP4 文件设计的，会一直等 moov，结果就是永远不开始播放。

### 修复

改为 HLS 优先，MP4 作为 fallback：

```kotlin
private fun startInlinePlayback(camera: Camera) {
    ...
    preparePlayback(camera, mp4Url, hlsUrl, useMp4 = false)  // HLS first
}

private fun preparePlayback(camera, mp4Url, hlsUrl, useMp4: Boolean) {
    ...
    val mediaSource = if (useMp4) {
        ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(Uri.parse(url)))
    } else {
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(url))
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(3_000)
                    .setMaxOffsetMs(10_000)
                    .setMinOffsetMs(1_000)
                    .build()
            )
            .build()
        HlsMediaSource.Factory(dataSourceFactory)
            .createMediaSource(mediaItem)
    }
    ...
    override fun onPlayerError(error: PlaybackException) {
        // HLS 失败 → fallback 到 MP4
        if (!useMp4 && mp4Url.isNotBlank() && !triedMp4) {
            preparePlayback(camera, mp4Url, hlsUrl, useMp4 = true)
        } else {
            stopInlinePlayback()
        }
    }
}
```

### 附加发现

- 调研时用 HTTP `HEAD` 探测 `/stream.mp4` 始终 404，误以为后端没实现。实际上 Gin 默认不响应 `HEAD`，改用 `GET` 后返回 200 OK。**结论：测后端端点用 GET，不要用 HEAD**

### 预防

- 直播流（无限流）必须用 HLS / DASH / WebRTC，不要用 ProgressiveMediaSource
- `MediaItem.LiveConfiguration` 是 ExoPlayer 控制直播延迟的正确入口（不是 `HlsMediaSource.Factory.setLiveTargetLatencyMs()`，那个方法在 2.19.1 不存在）
- 调研端点用 GET，不要用 HEAD

---

## 7. 报警行 Chip 按钮看似无效

### 症状

报警列表每行右侧有两个 Chip（「剪辑」「快照」），点击无反应，且 Chip 上没文字。

### 根因

[item_alert.xml](../app/src/main/res/layout/item_alert.xml) 中的 Chip 没设置 `android:text`，也没有在 Adapter 里挂 `setOnClickListener`：

```xml
<!-- 错的版本 -->
<com.google.android.material.chip.Chip
    android:id="@+id/chipClip"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    app:chipBackgroundColor="@color/online"
    app:closeIconVisible="false"
    android:visibility="gone" />
```

```kotlin
// AlertListAdapter 中没绑定：
// binding.chipClip.setOnClickListener { ... }  ← 缺失
```

### 修复

XML 加上 `android:text`、`android:clickable="true"`、`android:focusable="true"`：

```xml
<com.google.android.material.chip.Chip
    android:id="@+id/chipClip"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:text="@string/badge_clip"
    android:textSize="9sp"
    android:textColor="@android:color/white"
    android:clickable="true"
    android:focusable="true"
    app:chipBackgroundColor="@color/online"
    app:chipCornerRadius="4dp"
    app:chipStrokeWidth="0dp"
    app:closeIconVisible="false"
    android:layout_marginStart="6dp"
    android:visibility="gone" />
```

[AlertListAdapter.kt](../app/src/main/java/com/homedatacenter/app/ui/alerts/AlertListAdapter.kt) 挂监听：

```kotlin
binding.chipSnapshot.setOnClickListener {
    if (alert.hasSnapshot) onSnapshotClick?.invoke(alert)
}
binding.chipClip.setOnClickListener { onJumpCamera?.invoke(alert) }
binding.btnExpand.setOnClickListener { toggleExpanded(alert.id) }
```

### 预防

- 任何可点击 View 在 XML 里都要写 `android:clickable="true"` 和 `android:focusable="true"`
- Chip / Button 必须有文字（或 `app:chipIcon`），否则用户无法判断功能
- Adapter bind 时优先用 `setOnClickListener { }` 而不是 `setOnCheckedChangeListener`（后者对不可勾选的 Chip 没意义）

---

## 8. `app:itemSpacingHorizontal` 不是有效属性

### 症状

```bash
AAPT: error: attribute itemSpacingHorizontal not found
```

### 根因

`BottomNavigationView` 没有提供水平 item 间距的属性。我误以为存在。

### 修复

删除该属性。如果需要调整 item 间距，通过 `itemIconSize`、`itemPaddingTop`、自定义 `BottomNavigationItemView` 实现。

### 预防

- 在用陌生属性前先查官方文档 / `attrs.xml`
- 不要凭直觉猜属性名，Material Components 的属性名经常跟直觉不一致（如 `labelVisibilityMode` 而不是 `showLabel`）

---

## 9. `HlsMediaSource.Factory.setLiveTargetLatencyMs()` 方法不存在

### 症状

```bash
e: unresolved reference: setLiveTargetLatencyMs
```

### 根因

我误以为 ExoPlayer 2.19.1 的 `HlsMediaSource.Factory` 有 `setLiveTargetLatencyMs`。实际上该方法只在 2.18 之前的 `HlsMediaSource.Factory` 上存在，2.19 起改为通过 `MediaItem.LiveConfiguration.Builder().setTargetOffsetMs()` 设置。

### 修复

改用 `MediaItem.LiveConfiguration`：

```kotlin
val mediaItem = MediaItem.Builder()
    .setUri(Uri.parse(url))
    .setLiveConfiguration(
        MediaItem.LiveConfiguration.Builder()
            .setTargetOffsetMs(3_000)
            .setMaxOffsetMs(10_000)
            .setMinOffsetMs(1_000)
            .build()
    )
    .build()
```

### 预防

- ExoPlayer API 在大版本之间会迁移，2.18 → 2.19 有不少方法改名 / 移位。升级前看 release notes
- 调延迟统一走 `MediaItem.LiveConfiguration`，不要找 `setLiveTargetLatencyMs`

---

## 10. `app:checkable="false"` 让 Chip 编译失败

### 症状

```bash
AAPT: error: attribute checkable not found
```

### 根因

`Chip` 是 `CompoundButton` 子类，本身就不可勾选状态由 `app:checkable` 控制，但该属性属于 `styleable ChipGroup`，单 Chip 不能直接用 `app:checkable`。

### 修复

删除 `app:checkable="false"`。Chip 默认就是可勾选的，但作为「按钮」用时不关心 `isChecked`，靠 `setOnClickListener` 响应即可。

### 预防

- Chip 当按钮用时，不要去动 `app:checkable`，直接 `setOnClickListener`
- 如需不可勾选 Chip，用 `setCheckable(false)` 在代码里调，不要在 XML 写

---

## 11. Material 3 ActiveIndicator 需要 `colorSecondary`

### 症状

启动崩溃（同 #1），但触发路径不同：在 Activity recreate（主题切换 / 旋转）时崩溃。

### 根因

`BottomNavActiveIndicator` 引用 `Widget.Material3.BottomNavigationView.ActiveIndicator` 父样式，后者内部用到 `colorSecondary` / `colorPrimaryContainer`。如果应用主题是 Material 2，这些属性不存在。

### 修复

同 #1：把主题 parent 改成 `Theme.Material3.DayNight.NoActionBar`。

### 预防

- 用 M3 组件前先确认应用主题是 M3
- 如果只想用 M3 的某一个组件（如 ActiveIndicator），但保持应用主题 M2，需要在该样式里显式覆写所有 M3 需要的 color 属性，工作量通常 > 改主题 parent

---

## 12. Fragment recreate 后 WebSocket / ExoPlayer 状态丢失

### 症状

切换主题时 Activity recreate，所有 Fragment 重新创建。Fragment 内的 ExoPlayer 实例和 WebSocket 连接丢失，导致直播中断、实时推送断开。

### 根因

Fragment 默认 `retainInstance = false`，recreate 后旧实例被销毁，新实例重新走 `onCreateView` → `onViewCreated`，所有 ViewModel 之外的成员变量重置。

### 修复

- ExoPlayer：在 `onPause` / `onStop` 主动 release，并在 `onResume` / `onStart` 重新拉起
- WebSocket：在 `MainActivity.onCreate` 中连接，Activity recreate 后自动重连；Fragment 通过 `SharedFlow` 订阅事件，recreate 后重新订阅即可
- 主题切换用 `AppCompatDelegate.setDefaultNightMode(ThemeManager.currentMode)` 触发 recreate，但放在下一帧执行（`binding.root.post { }`）避免卡顿

### 预防

- 任何有状态的资源（Player / WebSocket / Camera）都不能依赖 Fragment 字段存活，必须假设随时会 recreate
- 用 ViewModel 持久化需要跨 recreate 保留的数据（如当前选中的摄像头）
- 在 `onPause` 释放重资源，在 `onResume` 重建

---

## 13. WebView WebRTC 权限未授予

### 症状

WebView 加载 WebRTC 页面（如 go2rtc 的 `webrtc.html`）时，`RTCPeerConnection` 创建成功但 ICE 一直 gathering，无法 connect。

### 根因

WebView 默认不授予媒体设备权限，即使 Manifest 声明了 `CAMERA` / `RECORD_AUDIO`。需要通过 `WebChromeClient.onPermissionRequest` 主动 grant。

### 修复

```kotlin
webView.webChromeClient = object : WebChromeClient() {
    override fun onPermissionRequest(request: PermissionRequest) {
        request.grant(request.resources)
    }
}
```

Manifest 同时声明权限：

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />

<uses-feature android:name="android.hardware.camera" android:required="false" />
<uses-feature android:name="android.hardware.microphone" android:required="false" />
```

### 预防

- 任何 WebView 中用 WebRTC / `getUserMedia`，都必须实现 `onPermissionRequest`
- `uses-feature` 设 `required="false"` 才能在无摄像头 / 麦克风的设备（如电视盒子）上安装

---

## 14. HLS 拉流需要 Cookie 而非 Authorization 头

### 症状

WebView 加载 go2rtc 的 HLS 页面（`/go2rtc/stream.html`）时 401，但 REST API 调用正常。

### 根因

go2rtc 反代路径（`/go2rtc/`）由 nginx 的 `auth_request /api/v1/auth/verify` 子请求保护。浏览器同源导航**不带** `Authorization` 头（那是 SPA axios 拦截器加的），只带 Cookie。如果 JWT 只通过 `Authorization` 发送，go2rtc 路径就 401。

### 修复

后端 `/api/v1/auth/bind` 在返回 JWT 时同时 `Set-Cookie: home_token=<jwt>`：

```go
c.SetCookie("home_token", token, maxAge, "/", "", false, false)
```

Android 客户端在 ExoPlayer 拉流时也带 Cookie：

```kotlin
val dataSourceFactory = DefaultHttpDataSource.Factory().apply {
    if (!token.isNullOrEmpty()) {
        setDefaultRequestProperties(mapOf(
            "Authorization" to "Bearer $token",
            "Cookie" to "home_token=$token",
        ))
    }
}
```

### 预防

- 后端在 `auth_request` 子请求里同时支持 `Authorization` 头和 `home_token` Cookie，因为不同客户端发送方式不同
- Android 客户端用 `CookieManager.getInstance().setCookie()` 全局注入 Cookie，避免每个请求手动加

---

## 15. `prefsManager.baseUrl` 在某些 Fragment 中为 null

### 症状

DashboardFragment 的天气卡片加载失败，但其它接口调用正常。

### 根因

移除「服务器地址」设置项后，`prefsManager.baseUrl` 永远返回 null。DashboardFragment 中 `loadWeather()` 直接 `prefsManager.baseUrl ?: return`，于是天气永远不加载。

### 修复

`AppContainer` 新增 `getApiBaseUrl()`，始终返回非空：

```kotlin
fun getApiBaseUrl(): String = prefsManager.baseUrl ?: DEFAULT_BASE_URL
```

所有需要 baseUrl 的地方改用 `container.getApiBaseUrl()`：

- `DashboardFragment.loadWeather()`
- `DashboardFragment.showSnapshotDialog()`
- `CamerasFragment` adapter 初始化
- `AlertsFragment` adapter 初始化
- `AlertsDialog` 与 `RecordingsDialog`

### 预防

- 任何「有 fallback 默认值」的配置项都应通过 getter 暴露非空类型，不要让调用方写 `?: return` 或 `?: ""`
- 删除 UI 配置项后，要在所有使用点检查是否仍有 fallback

---

## 16. XML 注释里出现 `--` 导致 AAPT 编译失败

### 症状

Gradle 构建失败：

```
AAPT: error: not well-formed (invalid token)
  at <some line in item_camera.xml>
```

### 根因

XML 规范规定：注释内容里不允许出现双连字符 `--`（`<!-- ... -- ... -->` 非法）。我在 `item_camera.xml` 里写了一段说明文字，里面用了 `--`（如 `-- player view`），AAPT 直接拒绝。

### 修复

把 `--` 替换为破折号 `—` 或括号：

```xml
<!-- 错的：用了双连字符 -->
<!-- item_camera.xml -- player view container -->

<!-- 对的：用破折号或括号 -->
<!-- item_camera.xml: player view container (default hidden) -->
```

### 预防

- XML 注释里**永远不要**写 `--`，无论作为分隔符、修饰符或减号
- 写注释时优先用冒号、括号、破折号（em-dash `—`，不是 `--`）
- 如需展示 CLI 选项（如 `--width=1280`），用反引号或 code block 包起来在 .md 里写，不要写进 XML 注释

---

## 17. ExoPlayer `AudioAttributes` 类型用错导致编译失败

### 症状

加上 `setAudioAttributes` 调用后编译报错：

```
e: Argument type mismatch: actual type is 'android.media.AudioAttributes!',
   but 'com.google.android.exoplayer2.audio.AudioAttributes' was expected
```

### 根因

我直觉性地用了 `android.media.AudioAttributes.Builder()`：

```kotlin
// 错的
newPlayer.setAudioAttributes(
    android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
        .build(),
    /* handleAudioFocus = */ true,
)
```

但 ExoPlayer 2.x 的 `setAudioAttributes` 方法签名是：

```java
public void setAudioAttributes(
    com.google.android.exoplayer2.audio.AudioAttributes audioAttributes,
    boolean handleAudioFocus
)
```

要的是 ExoPlayer 自己的包装类，不是 Android 框架类。两个 `AudioAttributes` 同名但不同包，编译器看到类型不匹配就报错。

### 修复

把 `Builder()` 改成 ExoPlayer 的版本：

```kotlin
newPlayer.setAudioAttributes(
    com.google.android.exoplayer2.audio.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
        .build(),
    /* handleAudioFocus = */ true,
)
```

注意 `USAGE_MEDIA` / `CONTENT_TYPE_MOVIE` 常量仍从 `android.media.AudioAttributes` 取，因为 ExoPlayer 的包装类没复制定义这些常量。

### 预防

- ExoPlayer 2.x 对音频属性、`TrackSelectionParameters` 等都用自己包装类，不要假设直接用 `android.media.*`
- 升级到 Media3 (ExoPlayer 3.x) 时这类包装类大多被合并回 framework 类，迁移时要再次检查
- 写 import 时尽量写全限定名避免歧义，特别是同名类

---

## 18. 真机 LAN 探测失败：WiFi connected but not validated

### 症状

模拟器上 App 启动后 Dashboard 显示「局域网」绿色标签，但同一局域网下的真机启动后却显示「远程」琥珀色标签。真机无 VPN，本应也走局域网。

### 根因

`Application.onCreate` 在用户点 App 图标的瞬间执行，此时 Android 的 WiFi stack 可能还没完成 validation（captive portal detection / DNS probe），路由不可用。`ConnectivityManager.onAvailable` 也在 validation 完成前就 fire。LAN 探测的 `GET` 请求在路由就绪前发出，TCP 连接失败，probe 返回 false，于是 fallback 到 Remote URL。

模拟器不会触发这个问题，因为它的网络通过宿主 PC 桥接，PC 的 WiFi 早就 validated 了。

### 修复

[BaseUrlResolver.kt](../app/src/main/java/com/homedatacenter/app/util/BaseUrlResolver.kt) 的 `probeLanOnStartup` 加入三层防御：

```kotlin
fun probeLanOnStartup() {
    // 1. 同步重试 + backoff
    repeat(STARTUP_RETRY_COUNT) { attempt ->   // 2 次
        if (probeUrl(LAN_URL, STARTUP_LAN_TIMEOUT_MS)) {  // 1000ms 超时
            if (resolved != LAN_URL) {
                resolved = LAN_URL
                onUrlChanged?.invoke(LAN_URL)
            }
            lastProbedAt = System.currentTimeMillis()
            return
        }
        if (attempt < STARTUP_RETRY_COUNT - 1) {
            Thread.sleep(STARTUP_RETRY_GAP_MS.toLong())  // 400ms
        }
    }

    // 2. 全部失败 → 3s 后异步重探
    lastProbedAt = System.currentTimeMillis()
    if (probing.compareAndSet(false, true)) {
        Thread {
            Thread.sleep(STARTUP_LATE_REPROBE_DELAY_MS.toLong())  // 3000ms
            probeSync()
            ...
        }.start()
    }
}
```

加上 `NetworkChangeMonitor` 注册 `onCapabilitiesChanged` 带 `NET_CAPABILITY_VALIDATED` 的回调，validation 完成后立即触发 `forceProbe()` 切到 LAN。

### 预防

- 任何依赖「网络可用」的启动逻辑都要假设 `onAvailable` 不可靠
- 用 `onCapabilitiesChanged` + `NET_CAPABILITY_VALIDATED` 作为「真正能上网」的信号
- 启动同步探测加 backoff + 延迟异步兜底，覆盖各种 validation 时间窗口
- 真机与模拟器行为不一致时，优先怀疑网络时序差异，不要假设是代码 bug

---

## 19. go2rtc 默认不转码音频，PCMA 不可播放

### 症状

直播视频流能放，但听不到声音。日志中无 audio track 信息。后端 go2rtc streams API 显示：

```json
{
  "前门": {
    "producers": [
      { "url": "ffmpeg:rtsp://...#video=h264#width=1280#hardware=vaapi" }
    ]
  }
}
```

没有 `#audio=...`，意味着 go2rtc 丢弃了 RTSP 流里的音频轨。

### 根因

摄像头（海康）RTSP 流默认音频编码是 PCMA（G.711 A-law），浏览器和 Android ExoPlayer 都不支持 PCMA 解码。go2rtc 的 ffmpeg producer 默认只处理视频，需要**显式**告诉它把音频转码到 AAC。

### 修复

[registry.go](../../home-datacenter/services/api/internal/camera/registry.go) 的 `rtspURL()` 在 `cam.Capabilities["audio"]==true` 时追加 `#audio=aac`：

```go
func (r *Registry) rtspURL(cam *model.Camera, user, pass string) string {
    raw := fmt.Sprintf("rtsp://%s:%s@%s:%d/Streaming/Channels/%d", user, pass, cam.Host, cam.RTSPPort, cam.ChannelID)
    codec := effectiveCodec(cam)
    audioOn := cameraHasAudio(cam)

    if codec == "passthrough" {
        if audioOn {
            return "ffmpeg:" + raw + "#video=copy#audio=aac"
        }
        return raw + "#audio=0"
    }

    audioFrag := ""
    if audioOn {
        audioFrag = "#audio=aac"
    }
    if codec == "h265" {
        return "ffmpeg:" + raw + "#video=h265#hardware=vaapi" + audioFrag
    }
    return "ffmpeg:" + raw + "#video=h264#width=1280#hardware=vaapi" + audioFrag
}
```

新增 `UpdateAudio()` 方法在 audio capability 变化时调用 `AddStream` 重新注册流，让 go2rtc 立即用新 URL：

```go
func (r *Registry) UpdateAudio(ctx context.Context, id uint, enabled bool) error {
    var cam model.Camera
    if err := r.DB.First(&cam, id).Error; err != nil { return err }
    if cam.Capabilities == nil { cam.Capabilities = model.JSON{} }
    cam.Capabilities["audio"] = enabled
    r.DB.Model(&cam).Updates(map[string]any{"capabilities": cam.Capabilities, "updated_at": time.Now()})
    user, pass, err := r.DecryptCredentials(&cam)
    if err == nil {
        rtspURL := r.rtspURL(&cam, user, pass)
        _ = r.Go2.AddStream(ctx, cam.StreamName, rtspURL)
    }
    return nil
}
```

HTTP 端点 `PUT /api/v1/cameras/:id/audio` 暴露给客户端调用：

```go
adminCam.PUT(":id/audio", camHandler.UpdateAudio)
```

Android 侧用 `setAudioAttributes` 让 ExoPlayer 走媒体音量 + 处理 AudioFocus（见 [§17](#17-exoplayer-audioattributes-类型用错导致编译失败)）。

### 预防

- RTSP 摄像头默认音频编码（PCMA / PCMU / G.726）大多不被 Web / Android 支持，需要服务端转码到 AAC
- go2rtc 不会自动转码音频，必须在流 URL 上**显式**追加 `#audio=aac` 或 `#audio=opus`
- `ffmpeg:` 前缀是必须的，否则 go2rtc 走 passthrough 不经过 ffmpeg
- 音频 capability 默认关闭，需要通过 API 显式开启（避免无麦克风摄像头浪费转码资源）

---

## 20. ExoPlayer `prepare()` 时无 surface 导致 MediaCodec `BAD_INDEX`

### 症状

摄像头直播卡在 loading 圈，永远不进入 READY 状态。logcat 显示：

```
I  MediaCodec: setOutputSurface -- failed to set consumer usage (BAD_INDEX)
W  ACodec  ...  98% buffers unfetched (increase minUndequeuedBuffers)
I  hevc software decoder: prefetch default frames
```

录像回放（`RecordingsDialog`）正常，只有 Cameras 页直播出问题。

### 根因

`CameraCard`（Compose）用 `AndroidView` 异步创建 `StyledPlayerView`：

```kotlin
// 错的：AndroidView factory 异步执行
AndroidView(factory = { ctx ->
    StyledPlayerView(ctx).apply {
        player = exoPlayer
    }
})
```

但 ExoPlayer 在 `prepare()` 时**同步**检查 surface，如果此时 `AndroidView` factory 还没执行，`playerView` 还不存在，ExoPlayer 会创建一个无 surface 的 MediaCodec 实例。MediaCodec 在 `configure()` 时拿不到 surface，进入「无表面渲染」模式，后续即使 `setOutputSurface` 被调用也会失败（`BAD_INDEX`），98% 的 buffer 永远拉不出来。

`RecordingsDialog` 不出问题是因为它用 XML 静态声明 `StyledPlayerView`，`binding.playerView` 在 `onCreate` 时就 ready 了。

### 修复

把 `StyledPlayerView` 移到 `item_camera.xml` 静态声明（默认 `visibility=gone`）：

```xml
<com.google.android.exoplayer2.ui.StyledPlayerView
    android:id="@+id/playerView"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:visibility="gone"
    app:resize_mode="fill"
    app:use_controller="false"
    app:show_buffering="when_playing" />
```

在 `CameraAdapter.preparePlayback` 中**先**设置 player + 可见性，**再**调 `prepare()`：

```kotlin
val newPlayer = ExoPlayer.Builder(context, renderersFactory)
    .setLoadControl(loadControl)
    .build()
    .apply {
        setAudioAttributes(...)
        setMediaSource(mediaSource)
        playWhenReady = true
        addListener(...)
    }

// 关键：先绑定 surface，再 prepare
binding.playerView.player = newPlayer
binding.playerView.visibility = View.VISIBLE
newPlayer.prepare()
```

### 预防

- ExoPlayer 与 Compose `AndroidView` 的组合在 surface 时序上有 race condition，避免组合使用
- 任何需要 surface 的播放器（ExoPlayer / MediaPlayer / TextureView 渲染器）都应在 XML 静态声明，并在 `prepare()` / `start()` 之前绑定 surface
- 录像回放没问题是因为 XML 同步绑定，所以同样代码模板在 Cameras 页直接复用即可，不要试图用 Compose 重写
- 看到 `setOutputSurface BAD_INDEX` + `98% buffers unfetched` 立即怀疑 surface 时序问题，而不是去查 codec 配置

---

## 21. 真机 LAN 探测仍然失败：OkHttp cleartext 被部分 ROM 拦截

### 症状

v1.4.3 的「2 次同步重试 + 3s 延迟异步重探」修复后，部分真机（特别是 MIUI / ColorOS）启动时仍然显示「远程」，无法切到局域网。`adb logcat` 显示每次 `probeUrl` 都是 `IOException`，即使手机在局域网内能 ping 通 NAS。

### 根因

两层原因叠加：

1. **主线程阻塞过长**：旧版 `probeLanOnStartup()` 是 `Application.onCreate` 里的同步阻塞调用，最坏情况 2.4s（2 × 1000ms + 400ms）。在真机上 WiFi validation 还没完成时，2.4s 都用来等超时 — 既没有给 WiFi stack 更多时间，也浪费了主线程。

2. **部分 ROM 拦截 OkHttp cleartext HTTP**：即使 `AndroidManifest.xml` 里 `usesCleartextTraffic=true` + `network_security_config.xml` 里 `base-config cleartextTrafficPermitted=true`，部分国产 ROM（MIUI / ColorOS / OriginOS）会通过 vendor 注入的「network policy」拦截 OkHttp 的明文 HTTP 请求。OkHttp 抛 `IOException`，probe 误判为「局域网不可达」。但同一时刻 `Socket.connect(192.168.31.234, 8088)` 是成功的 — vendor policy 只拦应用层 HTTP，不拦 TCP 层。

### 修复

[BaseUrlResolver.kt](../app/src/main/java/com/homedatacenter/app/util/BaseUrlResolver.kt) 重写为：

1. **`probeLanOnStartup()` 改为后台守护线程异步执行**，不再阻塞主线程。第一个 API 请求可能落到默认的远程 URL，但 LAN 探测成功后会触发 `onUrlChanged` 重建 Retrofit，后续请求自动切到 LAN。

2. **指数退避调度**（1.5s → 4s → 9s → 16s）：4 次重试覆盖真机 WiFi 验证窗口（实测 5-10s）。每次调度都检查 `resolved == LAN_URL`，已切到 LAN 则跳过剩余调度。

3. **TCP socket 直连兜底**：HTTP 探测失败时再用 `Socket.connect()` 探测一次，任一成功即视为可达：

```kotlin
private fun probeSync() {
    val lanHttpOk = probeUrl(LAN_URL, LAN_TIMEOUT_MS)
    val lanTcpOk = if (!lanHttpOk) probeTcp(LAN_HOST, LAN_PORT, LAN_TIMEOUT_MS) else false
    val lanAlive = lanHttpOk || lanTcpOk
    // ...
}

private fun probeTcp(host: String, port: Int, timeoutMs: Int): Boolean {
    return try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
    } catch (e: Exception) { false }
}
```

4. **`MainActivity.onCreate` / `onResume` 触发 `forceProbe()`**：进入主页时 WiFi 几乎一定已验证，是再次探测的最佳时机。

### 预防

- 国产 ROM 对明文 HTTP 的拦截是真实存在的，不能假设 `usesCleartextTraffic=true` 就万无一失
- 网络可达性探测要有多手段 fallback（HTTP → TCP → DNS），单一手段容易被 vendor 拦截
- 启动期的同步阻塞调用要尽量减少 — `Application.onCreate` 里阻塞 1s 以上都应改后台线程
- 真机排查 LAN 探测失败时，先用 `adb shell` 跑 `nc -v 192.168.31.234 8088` 或 `echo > /dev/tcp/192.168.31.234/8088` 验证 TCP 层是否通

---

## 22. KDoc 块注释里的 `/api/*` 触发嵌套块注释解析错误

### 症状

`BaseUrlResolver.kt` 编译失败：

```
e: BaseUrlResolver.kt:354:1 Syntax error: Unclosed comment.
```

但代码里看起来所有 `/** */` 都正确闭合。

### 根因

Kotlin 的块注释**支持嵌套**。KDoc 注释里出现 `/api/*` 这样的文字时，`/*` 会被解析为开启一个嵌套块注释，导致后续的 `*/` 只关闭嵌套层，外层 `/**` 永远不闭合。

具体案例：旧版注释包含

```kotlin
/**
 * The probe target is GET /api/v1/system/status on the backend —
 * a JWT-protected endpoint. nginx routes /api/* to the home-api
 * container; ...
 */
```

`/api/*` 中的 `/*` 在 Kotlin 解析器眼中是「开启嵌套块注释」，于是 `*/` 只关闭嵌套层，外层 `/**` 一直没闭合，文件后续所有内容都被吞进注释里，导致大量「Unresolved reference」错误。

### 修复

把 `/api/*` 改写成不含 `/*` 的描述，例如 `/api/ prefix`：

```kotlin
/**
 * The probe target is GET /api/v1/system/status on the backend —
 * a JWT-protected endpoint. nginx routes the /api/ prefix to the
 * home-api container; ...
 */
```

`//` 行注释里的 `/api/*` 不受影响 — 嵌套只发生在 `/* */` 块注释内。

### 预防

- **KDoc / 块注释里不要写 `/*` 字面量**，尤其是 URL 路径如 `/api/*` / `/foo/*` / `glob/*`
- 用 `/api/` 或 `the /api/ prefix` 替代
- 看到「Unclosed comment」但代码看着正常时，立即 grep `/\*` 找块注释里有没有意外的 `/*` 字面量
- 行注释 `//` 里的 `/*` 不受影响，可以照常用

---

## 总结：通用原则

1. **Material 2 / 3 不能混用** — 主题 parent 和组件父样式必须对齐
2. **ExoPlayer API 在大版本间会变** — 升级前看 release notes，调延迟统一走 `MediaItem.LiveConfiguration`
3. **Debug 构建用项目级 keystore** — 多机器协作必备
4. **跨网络移动调用要重试 + HTTP/1.1** — Cloudflare Tunnel / 移动网络都不稳定
5. **任何可点击 View 都要 text + listener** — 否则用户无法判断功能
6. **删除配置项时检查所有使用点** — 留下 `?: return` 会让某个功能静默失效
7. **测试后端端点用 GET，不要用 HEAD** — Gin / 部分框架默认不响应 HEAD
8. **WebView 中用 WebRTC 要实现 onPermissionRequest** — 默认不授予媒体权限
9. **同源代理路径的鉴权用 Cookie，REST 用 Authorization** — nginx auth_request 子请求看不到 SPA 加的头
10. **任何有状态资源都假设 Fragment 会 recreate** — 在 onPause 释放，在 onResume 重建
11. **XML 注释里不要写 `--`** — 用冒号或破折号 `—` 代替
12. **ExoPlayer 2.x 有自己的包装类**（`AudioAttributes` / `TrackSelectionParameters`），不要直接用 `android.media.*`
13. **真机网络时序与模拟器不同** — `onAvailable` 不可靠，用 `onCapabilitiesChanged` + `NET_CAPABILITY_VALIDATED`
14. **RTSP 音频默认不可播放** — PCMA/PCMU 需要服务端转码到 AAC，go2rtc 流 URL 显式追加 `#audio=aac`
15. **ExoPlayer 与 Compose `AndroidView` 的 surface 时序有 race** — `StyledPlayerView` 静态声明 + `playerView.player` 在 `prepare()` 之前设置
16. **国产 ROM 可能拦截 OkHttp cleartext** — `usesCleartextTraffic=true` 不是万金油，HTTP 探测失败时用 raw TCP socket 兜底
17. **KDoc 块注释里的 `/*` 会触发嵌套解析** — URL 路径 `/api/*` 改写为 `/api/ prefix`，行注释不受影响
