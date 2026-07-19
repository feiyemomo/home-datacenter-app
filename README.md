# Home Datacenter App

家庭数据中心 Android 客户端 — 一个用 **Kotlin + Jetpack Compose + ExoPlayer** 实现的家庭 NVR / IoT 控制台，配合 [home-datacenter](https://github.com/feiyemomo/home-datacenter) 后端使用，提供摄像头预览、HLS 直播、录像回放、报警查看、设备状态、天气信息和实时 WebSocket 推送。

> 服务端项目：<https://github.com/feiyemomo/home-datacenter>
> 当前版本：**v1.3.5**（versionCode 10）

---

## 目录

- [项目概览](#项目概览)
- [功能特性](#功能特性)
- [技术栈](#技术栈)
- [项目结构](#项目结构)
- [架构说明](#架构说明)
- [快速开始](#快速开始)
- [构建与签名](#构建与签名)
- [配置说明](#配置说明)
- [API 与端点](#api-与端点)
- [视频播放策略](#视频播放策略)
- [常见问题](#常见问题)
- [错误教训](#错误教训)
- [License](#license)

---

## 项目概览

| 项目 | 值 |
|---|---|
| Application ID | `com.homedatacenter.app` |
| Min SDK | 29 (Android 10) |
| Target SDK | 36 (Android 16) |
| Compile SDK | 36 |
| Java / Kotlin | 17 / 2.0 |
| AGP | 9.2.1 |
| 当前版本 | 1.3.5 (versionCode 10) |
| 默认服务器 | `https://api.feiyemomo.top/` |

App 通过 `(user_id, access_key)` 换取 JWT 后访问 `home-datacenter` 的 REST API 与 WebSocket，摄像头直播走 go2rtc 暴露的 HLS（主）+ fMP4（备），录像和报警来自 Frigate 检测管道与 home-api 的代理端点。

---

## 功能特性

- **首页（Dashboard）**：天气卡片 + 系统状态网格（MQTT / 设备 / 摄像头 / 运行时长） + 最近报警列表 + 实时检测 WS 横幅。
- **摄像头（Cameras）**：缩略图卡片 + 点击内联 HLS 直播 + 录像回放对话框（拖动进度条） + 单摄像头的报警快照 / 剪辑对话框。
- **报警（Alerts）**：报警列表，可展开查看事件 ID / 抓拍时间 / 检测区域，支持跳转摄像头页和打开快照模态。
- **设备（Devices）**：所有已绑定设备的状态卡片，支持撤销设备。
- **设置（Settings）**：主题切换（明 / 暗） + 关于信息 + 退出登录。
- **登录（Login）**：user_id + access_key 设备绑定，登录后 JWT 持久化于 EncryptedSharedPreferences。
- **底部导航**：Material 3 ActiveIndicator 胶囊样式，5 个 Tab（主页 / 设备 / 摄像头 / 报警 / 设置），高度 72dp 防止文字与图标重叠。
- **网络层**：OkHttp 强制 HTTP/1.1 + 30s/60s/90s 超时 + 重试，针对 Cloudflare Tunnel 在移动网络上偶发的 stream 关闭问题。
- **实时推送**：WebSocket 客户端，订阅 `device.status` / `camera.alert` / `automation.fired` 等事件，主页横幅实时滚动。

---

## 技术栈

| 类别 | 选型 |
|---|---|
| UI Framework | View Binding + Jetpack Compose（CameraCard 用 Compose） |
| Material Design | Material 3（`Theme.Material3.DayNight.NoActionBar`） |
| 网络 | Retrofit 2.11 + OkHttp 4.12 + kotlinx.serialization 1.7 |
| 异步 | Kotlin Coroutines 1.9 + Flow |
| 持久化 | EncryptedSharedPreferences（androidx.security.crypto） |
| 视频播放 | ExoPlayer 2.19.1（core + hls + ui） |
| 导航 | AndroidX Navigation + BottomNavigationView |
| WebSocket | OkHttp WebSocket |
| DI | 手写 `AppContainer`（轻量容器，无 Hilt/Dagger） |

---

## 项目结构

```
Android/
├── app/
│   ├── build.gradle.kts              # 模块配置，含 projectDebug 签名
│   ├── keystore/
│   │   └── home-debug.jks             # 项目级固定 debug 签名
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/homedatacenter/app/
│       │   ├── HomeCenterApp.kt       # Application 入口
│       │   ├── data/
│       │   │   ├── api/               # HomeCenterApi(Retrofit), NetworkFactory
│       │   │   ├── model/             # 数据类：Camera/Alert/Device/Weather/...
│       │   │   ├── repository/        # HomeCenterRepository（统一 API 入口）
│       │   │   └── ws/                # HomeCenterWebSocket
│       │   ├── di/
│       │   │   └── AppContainer.kt    # 手写 DI 容器
│       │   ├── ui/
│       │   │   ├── login/             # LoginActivity
│       │   │   ├── main/              # MainActivity + BottomNav
│       │   │   ├── dashboard/         # DashboardFragment
│       │   │   ├── cameras/           # CamerasFragment + CameraAdapter + Dialogs
│       │   │   ├── alerts/            # AlertsFragment + AlertListAdapter
│       │   │   ├── devices/           # DevicesFragment + DeviceAdapter
│       │   │   └── settings/          # SettingsFragment
│       │   └── util/                  # PrefsManager, ThemeManager, ExoPlayerDialogFragment, ...
│       └── res/
│           ├── layout/                # activity_*, fragment_*, dialog_*, item_*
│           ├── values/                # colors, dimens, strings, themes
│           ├── values-night/           # 暗色主题覆盖
│           ├── drawable/              # 矢量图标与背景
│           ├── menu/                  # bottom_nav_menu
│           └── xml/                   # backup_rules, network_security_config
├── gradle/
│   ├── wrapper/
│   └── libs.versions.toml             # 版本目录（Version Catalog）
├── build.gradle.kts                   # 根模块配置
├── settings.gradle.kts
├── gradle.properties
└── gradlew / gradlew.bat
```

---

## 架构说明

### 整体分层

```
┌──────────────────────────────────────────────────────────┐
│                    UI Layer (Fragments)                  │
│  Dashboard │ Cameras │ Alerts │ Devices │ Settings │ ... │
└────────────────────────┬─────────────────────────────────┘
                         │ view-models / coroutine scopes
┌────────────────────────▼─────────────────────────────────┐
│              Repository Layer (HomeCenterRepository)     │
└────────────────────────┬─────────────────────────────────┘
                         │ suspend functions
┌────────────────────────▼─────────────────────────────────┐
│  Data Layer ┌──────────────┐  ┌───────────────────────┐  │
│             │ HomeCenterApi│  │ HomeCenterWebSocket   │  │
│             │ (Retrofit)   │  │ (OkHttp WebSocket)    │  │
│             └──────┬───────┘  └───────────┬───────────┘  │
│                    │                      │              │
│             ┌──────▼───────┐              │              │
│             │   OkHttp     │              │              │
│             │  HTTP/1.1    │              │              │
│             └──────┬───────┘              │              │
└────────────────────┼──────────────────────┼──────────────┘
                     │ HTTPS / WSS          │ WSS
                     ▼                      ▼
       ┌──────────────────────────────────────────┐
       │     home-datacenter backend              │
       │  (Go + Gin + SQLite + MQTT + Frigate)    │
       └──────────────────────────────────────────┘
```

### DI 容器

`AppContainer` 是手写的轻量 DI 容器，持有 `OkHttpClient`、`PrefsManager`、当前 `HomeCenterApi` 实例。当 `baseUrl` 变化时（理论上现在固定，但保留了切换能力），会重建 `Retrofit` 与 `Repository`。

关键方法：

- `getApi()`：返回当前 `HomeCenterApi`（带 baseUrl 缓存）
- `getApiBaseUrl()`：始终返回非空 URL（先看 prefs，再 fallback 到 `DEFAULT_BASE_URL`）
- `getWsUrl()`：由 baseUrl 推导 `wss://.../api/v1/ws`
- `getRepository()`：返回 `HomeCenterRepository`
- `resetApi()`：登出后清空缓存

### 登录流程

```
LoginActivity
   │
   │  POST /api/v1/auth/bind {user_id, access_key}
   │
   ▼
home-api 返回 {token: <jwt>} + Set-Cookie: home_token=<jwt>
   │
   ▼
PrefsManager.saveAuth(token, userId) → EncryptedSharedPreferences
   │
   ▼
startActivity(MainActivity)
```

JWT 同时通过 `Authorization: Bearer <token>` 头和 `Cookie: home_token=<token>` 发送，前者用于 `/api/v1/` REST 调用，后者用于 go2rtc / Frigate 的同源代理路径。

### WebSocket 推送

`HomeCenterWebSocket` 在 `MainActivity` 启动时连接 `wss://api.feiyemomo.top/api/v1/ws`，订阅以下事件类型：

- `device.status` — 设备上下线
- `camera.alert` — 新报警
- `automation.fired` — 自动化规则触发审计
- `user.notification` — 用户通知

Dashboard Fragment 注册 `SharedFlow` 收到事件后刷新对应区域；摄像头 Fragment 收到 `camera.alert` 时给对应卡片打红点。

---

## 快速开始

### 前置条件

- **JDK 17**（推荐 Temurin / Zulu）
- **Android Studio** Ladybug 或更高（AGP 9.x 要求）
- **Android SDK** 包含 compileSdk 36（Android 16）
- 一台 Android 10+ 真机或模拟器
- 可访问的后端服务（默认 `https://api.feiyemomo.top/`）

### 克隆并构建

```bash
git clone https://github.com/feiyemomo/home-datacenter-app.git
cd home-datacenter-app

# Windows PowerShell
.\gradlew.bat assembleDebug

# Linux / macOS
./gradlew assembleDebug
```

输出 APK：

```
app/build/outputs/apk/debug/app-debug.apk
```

### 安装到设备

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> ⚠️ 如果之前装过其它签名的版本，需要先卸载：`adb uninstall com.homedatacenter.app`。详见 [错误教训 §3](#3-安装包无效--package-invalid)。

### 登录

打开 App，输入：

- **用户 ID**：管理员分配的数字 ID（如 `1`）
- **访问密钥**：64 位十六进制 access_key（区分 `0` 与 `O`，详见 [错误教训 §5](#5-connection-closed-登录失败)）

点击「绑定设备」即可。

---

## 构建与签名

### 项目级 Debug Keystore

为了防止不同机器 debug 构建的签名不一致导致「安装包无效」，本仓库内置了项目级 debug keystore：

- 路径：`app/keystore/home-debug.jks`
- Store / Key 密码：`home123`
- Alias：`home-debug`
- 算法：RSA 2048，PKCS12
- 有效期：36500 天
- CN：`HomeDatacenter Debug`

`app/build.gradle.kts` 中已配置：

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

> 该 keystore 仅用于 debug 构建，不用于 release 发布。私钥泄漏不影响生产签名。

### Release 构建

目前 `release` 构建未配置专用签名，仅用于本地测试。若要发布到应用商店，请：

1. 生成专用 release keystore（**不要**复用 `home-debug.jks`）
2. 在 `build.gradle.kts` 中新增 `release` signingConfig
3. 通过环境变量或 `~/.gradle/gradle.properties` 注入密码（不要硬编码到仓库）

---

## 配置说明

### 服务器地址

固定在 [app/src/main/java/com/homedatacenter/app/di/AppContainer.kt](app/src/main/java/com/homedatacenter/app/di/AppContainer.kt)：

```kotlin
companion object {
    const val DEFAULT_BASE_URL = "https://api.feiyemomo.top/"
}
```

如果需要切换后端，修改此常量后重新构建。未来若重新开放用户自定义服务器地址，可通过 `PrefsManager.baseUrl` 注入。

### 网络安全配置

`res/xml/network_security_config.xml` 允许 cleartext 流量（用于 LAN 直连调试），生产域名走 HTTPS。

### 主题

- 亮色：`res/values/themes.xml`
- 暗色：`res/values-night/themes.xml`
- 父主题：`Theme.Material3.DayNight.NoActionBar`

主题切换通过 `ThemeManager` 设置 `AppCompatDelegate.setDefaultNightMode`，Activity 在下一帧 recreate 以避免卡顿。

### 版本号

每次发版**必须**更新 `app/build.gradle.kts` 中的 `versionCode` 与 `versionName`：

```kotlin
versionCode = 10
versionName = "1.3.5"
```

---

## API 与端点

完整 API 文档见后端项目 [home-datacenter/docs/api-documentation.md](https://github.com/feiyemomo/home-datacenter/blob/main/docs/api-documentation.md)。本客户端调用的端点汇总：

### 鉴权

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/auth/bind` | 用 (user_id, access_key) 换 JWT |
| GET | `/api/v1/auth/verify` | 验证 JWT 有效性（nginx auth_request 用） |

### 用户与设备

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/user/me` | 当前用户信息 |
| GET | `/api/v1/device/list` | 设备列表 |
| DELETE | `/api/v1/device/{id}` | 撤销设备 |

### 系统与网络

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/system/status` | 系统状态（设备数、摄像头数、运行时长） |
| GET | `/api/v1/network/status` | 网络质量（IPv6 / NAT / P2P） |
| GET | `/api/v1/network/p2p/server-endpoint` | P2P 服务端点 |
| GET | `/api/v1/weather` | 天气数据（5 分钟缓存） |

### 摄像头

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/cameras` | 摄像头列表 |
| GET | `/api/v1/cameras/{id}` | 摄像头详情 |
| GET | `/api/v1/cameras/{id}/frame` | 当前帧截图（缩略图） |
| GET | `/api/v1/cameras/{id}/stream.mp4` | fMP4 直播流（备） |
| GET | `/api/v1/cameras/{id}/recordings` | 录像列表 |
| GET | `/api/v1/cameras/alerts` | 全局报警列表 |
| POST | `/api/v1/cameras` | 注册摄像头（管理员） |
| DELETE | `/api/v1/cameras/{id}` | 删除摄像头（管理员） |
| PUT | `/api/v1/cameras/{id}/codec` | 更新编码（管理员） |
| POST | `/api/v1/cameras/{id}/ptz` | PTZ 控制（管理员） |
| PUT | `/api/v1/cameras/{id}/recording` | 设置录像计划 |
| GET | `/api/v1/cameras/{id}/presets/discover` | 发现 PTZ 预置位 |
| PUT | `/api/v1/cameras/{id}/presets/{alias}` | 设置预置位 |
| DELETE | `/api/v1/cameras/{id}/presets/{alias}` | 删除预置位 |
| POST | `/api/v1/cameras/{id}/preset/{alias}` | 跳转预置位 |

### WebRTC（仅 LAN 可用）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/cameras/ice` | ICE 配置 |
| POST | `/api/v1/cameras/{id}/webrtc` | SDP offer/answer |

> Cloudflare Tunnel 不转发 UDP，所以 WebRTC 在移动网络上不可用。客户端以 HLS 为主。

### 标准响应包络

```json
{ "code": 0, "message": "success", "data": <T> }
```

`code != 0` 表示业务错误，`message` 是可读消息。`ApiResponse.decodeData<T>()` 负责解包。

---

## 视频播放策略

### 直播（inline 播放）

[CameraAdapter.kt](app/src/main/java/com/homedatacenter/app/ui/cameras/CameraAdapter.kt) 中的 `startInlinePlayback` 按以下顺序尝试：

1. **HLS（主）** — `camera.stream.hls_url` 或 `${base}/api/stream.m3u8?src=<name>`
   - ExoPlayer `HlsMediaSource`
   - `MediaItem.LiveConfiguration`：`targetOffsetMs=3000`, `maxOffsetMs=10000`, `minOffsetMs=1000`（目标 ~3s 端到端延迟）
   - 低延迟 `DefaultLoadControl`：`minBufferMs=2000`, `maxBufferMs=5000`, `bufferForPlaybackMs=1000`, `bufferForPlaybackAfterRebufferMs=1500`
2. **MP4（备）** — `${base}/api/v1/cameras/{id}/stream.mp4`（go2rtc fMP4）
   - ExoPlayer `ProgressiveMediaSource`
   - 仅在 HLS 失败时 fallback
3. **失败** — 显示错误占位图

### 录像回放

`RecordingsDialog` 使用 ExoPlayer `PlayerControlView` + `ProgressiveMediaSource`，支持：

- 拖动进度条跳转
- 暂停 / 播放
- 静音切换

### 资源释放

- Fragment `onPause` / `onHide` → `releaseAllPlayers()`
- ViewHolder `onViewDetachedFromWindow` → `releasePlayer()`
- ViewHolder `onViewRecycled` → `recycle()`

防止后台 ExoPlayer 占用带宽与电池。

---

## 常见问题

### Q1：登录提示 `connection closed`

通常是网络问题（VPN 超时、移动网络丢包）或 access_key 输错（`0` 和 `O` 容易混淆）。请检查：

1. VPN 是否仍在线
2. access_key 是否正确（用 64 位十六进制，只含 0-9 和 a-f）
3. 服务器域名 `https://api.feiyemomo.top/` 是否可访问

### Q2：摄像头预览图不显示

可能是：

- 后端 `/api/v1/cameras/{id}/frame` 返回 4xx（摄像头离线或 go2rtc 未就绪）
- 网络不通

客户端会显示错误图标占位，并在 logcat（TAG=`CameraAdapter`）输出 HTTP 状态码。

### Q3：直播打开后 App 闪退

历史上出过一次 `DefaultLoadControl` 参数约束违例（`minBufferMs < bufferForPlaybackAfterRebufferMs`），已在 v1.3.5 修复。如果再次出现，请抓 logcat 并检查 ExoPlayer 参数。

### Q4：更新版本提示「安装包无效」

签名不一致。请先卸载旧版再安装新版。仓库内置了项目级 keystore（[app/keystore/home-debug.jks](app/keystore/home-debug.jks)），所有 debug 构建都用它签名，跨机器兼容。

### Q5：报警列表里的「剪辑」「快照」按钮无效

已在 v1.3.5 修复：Chip 加了 `android:text` 和 `setOnClickListener`。如再次失效请检查 [AlertListAdapter.kt](app/src/main/java/com/homedatacenter/app/ui/alerts/AlertListAdapter.kt) 中是否设置了点击监听。

---

## 错误教训

本节记录开发过程中踩过的坑与修复方法，供后续维护参考。详细分析见 [docs/ERRORS.md](docs/ERRORS.md)。

1. **Material 3 主题与 Material 2 父主题不兼容** → 启动崩溃 `Failed to resolve attribute at index 2`
2. **BottomNavigationView 高度不足** → 文字与图标重叠
3. **ProgressiveMediaSource 不适合无限 fMP4 流** → 直播卡顿，应优先 HLS
4. **ExoPlayer DefaultLoadControl 参数约束** → `minBufferMs` 必须 ≥ `bufferForPlaybackAfterRebufferMs`
5. **APK 签名跨机器不一致** → 「安装包无效」，需要项目级 keystore
6. **Cloudflare Tunnel 偶发关闭 HTTP/2 流** → 强制 HTTP/1.1 + 长超时 + 重试
7. **Access key 中 `0` 与 `O` 混淆** → 登录失败，提示用户检查密钥
8. **Chip 没有 `android:text` 和点击监听** → 报警行按钮看似无效
9. **Material 3 ActiveIndicator 需要 `colorSecondary` 等属性** → M2 主题缺失导致崩溃
10. **`app:itemSpacingHorizontal` 不是有效属性** → 编译失败
11. **`HlsMediaSource.Factory.setLiveTargetLatencyMs` 在 2.19.1 不存在** → 改用 `MediaItem.LiveConfiguration`

---

## License

Private / 家庭项目，未指定开源协议。
