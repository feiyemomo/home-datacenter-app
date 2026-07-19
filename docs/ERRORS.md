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
