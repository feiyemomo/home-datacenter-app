# Home Datacenter App

家庭数据中心 Android 客户端 — 一个用 **Kotlin + Jetpack Compose + ExoPlayer + WebRTC** 实现的家庭 NVR / IoT 控制台，配合 [home-datacenter](https://github.com/feiyemomo/home-datacenter) 后端使用，提供摄像头预览、WebRTC/MP4/HLS 直播（含音频）、录像回放、报警查看、设备状态、天气信息、局域网/远程自动切换和实时 WebSocket 推送。

> 服务端项目：<https://github.com/feiyemomo/home-datacenter>
> 当前版本：**v1.6.4**（versionCode 47）

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
| 当前版本 | 1.6.4 (versionCode 47) |
| 默认服务器 | `https://api.feiyemomo.top/`（远程） / `http://192.168.31.234:8088/`（局域网，自动探测） |

App 通过 `(user_id, access_key)` 换取 JWT 后访问 `home-datacenter` 的 REST API 与 WebSocket。**BaseUrlResolver** 在启动时通过后台守护线程异步探测局域网 `http://192.168.31.234:8088/` 是否可达（TTFB ~10ms vs Cloudflare Tunnel 1.4s+），可达则切到局域网，否则走远程 Cloudflare Tunnel。启动调度采用指数退避重试（1.5s → 4s → 9s → 16s），覆盖真机「WiFi connected but not validated」窗口；同时附加 TCP socket 直连探测作为 OkHttp cleartext 拒绝时的兜底。NetworkChangeMonitor 注册 ConnectivityManager.NetworkCallback，在 WiFi/移动网络切换时立即触发 re-probe，无需等 5 分钟 TTL。摄像头直播走 go2rtc 暴露的 MP4（主）+ HLS（备），后端根据摄像头 `capabilities.audio` 在 go2rtc 流 URL 上自动追加 `#audio=aac` 启用音频转码，前端通过 ExoPlayer `volume` 控制静音/取消静音。

---

## 功能特性

- **首页（Dashboard）**：天气卡片 + 系统状态网格（MQTT / 设备 / 摄像头 / 运行时长） + 网络质量卡片（含**当前路径标签**：局域网=绿点 / 远程=琥珀点，每 5s 刷新） + 最近报警列表 + 实时检测 WS 横幅。
- **摄像头（Cameras）**（v1.5.2 重构，v1.5.3 加入 WebRTC 直播）：列表改为**紧凑缩略图卡片**（128×72 缩略图 + 名称 / 厂商 / codec 徽章），点击整卡进入 `CameraDetailActivity`；详情页顶部为 **WebRTC 优先直播区**（v1.5.3：`WebRtcClient` 通过 `POST /api/v1/cameras/{id}/webrtc` 走 WHEP 信令，sub-second 延迟；失败自动 fallback 到 MP4 → HLS），下方为三按钮动作区——`查看录像`、`报警记录`、`重新加载`——分别打开 `RecordingsDialog`、`AlertsDialog`、重启直播流。`CameraDetailActivity` 同时承载 PTZ / 预设位 / 音频开关 / **Frigate 持续录像开关（v1.5.3：从 `camera.meta["recording"]` 解析实际状态，不再硬编码 false）** / H264 切换 / 删除（管理员）。`CamerasFragment` 列表不再持有 ExoPlayer 实例，滚动只解码缩略图位图（LRU 16 条缓存），翻页/复用零 MediaCodec 开销。**注册 FAB 移到顶部右上**（mini size + `ic_add`），不再与底部导航冲突。**v1.5.3：状态栏间距由根 ScrollView `fitsSystemWindows=true` 推到状态栏下方；播放器全屏按钮强制横屏，独立 `btnFullscreen` overlay 兼容 WebRTC 与 ExoPlayer 模式**。
- **报警（Alerts）**（v1.5.2 精简，v1.6.0 增强跳转）：报警项布局重写——删除"截图"按钮、删除可展开详情、删除大图模态；保留缩略图 + 标签 + 摄像头名 + 时间 + **"查看录像"** Chip。**v1.6.0：点击"查看录像"或首页实时报警横幅直接跳转到 `CameraDetailActivity` 并自动打开 `RecordingsDialog`，传入 `EXTRA_INITIAL_TIMESTAMP`（报警 `start_time`）——对话框按日期锚定到对应录像日、构建 24h 播放列表、ExoPlayer 在首次 `STATE_READY` 时通过 `pendingAlertSeekMs` 一次性 seek 到报警的精确时刻**（之前仅切换到 cameras tab，需用户手动查找）。失败回退到旧切换 tab 行为，并在 DashboardFragment 缓存 `lastLiveAlert` 供横幅点击使用。
- **设备（Devices）**：所有已绑定设备的状态卡片，支持撤销设备。
- **设置（Settings）**：**个人资料卡片**（用户名 / 角色 / JWT user_id / device_id / 签发与到期时间 / 剩余天数） + **管理员分区**（仅 `prefsManager.isAdmin=true` 时显示，入口跳转 `UsersActivity`） + 主题切换（明 / 暗 / 跟随系统） + 退出登录 + 版本号。
- **管理员用户管理**（v1.5.0 新增）：`UsersActivity` 列出全部用户（含设备数 / 注册时间），FAB 创建用户并可选创建首台设备（一次性返回 64 位 AccessKey），点击列表项打开编辑对话框（改名 / 切换管理员 / 删除），自删与自降级在客户端先拦截、服务端再兜底。
- **摄像头注册**（v1.5.0 新增，v1.5.2 调整位置）：`CamerasFragment` **右上角 FAB**（mini size + `ic_add`，不再与底部导航冲突）→ `RegisterCameraDialog` 表单（名称 / 主机 IP / 厂商 / 通道 / ONVIF/RTSP 端口 / 用户名 / 密码 / PTZ/音频/动作复选框）→ `POST /api/v1/cameras`。
- **设备实时状态**（v1.5.1 增强）：`DeviceAdapter` 显示三态（已吊销 / 在线 / 离线）— 在线状态由 `SystemStatus.onlineDeviceIds` 推断（DashboardFragment 通过 5s 轮询 + WS `device.status` / `online_list` 事件维护该列表到 PrefsManager 缓存），`DevicesFragment.onResume` 与 `revokeDevice` 之后强制刷新缓存并推送给 Adapter。
- **网络详情页**（v1.5.1 新增）：Dashboard 网络质量卡片可点击跳转 `NetworkDetailActivity`，展示 `NetworkStatus` 完整字段（IPv6 启用/可达/地址、NAT 类型/公网 IP/端口、P2P 支持/原因、Relay 可用/类型、初始与实际策略、质量评分、检测时间）+ `/network/p2p/server-endpoint` 返回的服务端点（公网 IP / 端口 / IPv6 / NAT 类型 / 策略）+ `SystemStatus` 的 MQTT 连接状态、WS 在线客户端数、服务运行时长。支持下拉刷新与 toolbar 刷新按钮强制 `refresh=true`。
- **MQTT / WebSocket 调试**（v1.5.1 合并到网络详情页）：因后端目前未暴露 `/mqtt/*` 端点，MQTT 调试入口在网络详情页的"MQTT / WebSocket"分区，展示 `mqtt_connected` 在线状态 + `ws_clients` 数量 + `uptime_seconds` 运行时长。
- **登录（Login）**：user_id + access_key 设备绑定，登录后 JWT 持久化于 EncryptedSharedPreferences。
- **底部导航**：Material 3 ActiveIndicator 胶囊样式，5 个 Tab（主页 / 设备 / 摄像头 / 报警 / 设置），高度 72dp 防止文字与图标重叠。
- **网络层**：OkHttp 强制 HTTP/1.1 + 30s/60s/90s 超时 + 重试，针对 Cloudflare Tunnel 在移动网络上偶发的 stream 关闭问题。
- **LAN/Remote 自动切换**（v1.4.4 重写）：
  - 启动调度改为后台守护线程异步执行（不再阻塞主线程），指数退避重试 4 次（1.5s → 4s → 9s → 16s），覆盖真机 WiFi 验证窗口（5-10s）。
  - 在 HTTP 探测之外附加 TCP socket 直连探测作为兜底 — 部分 ROM（MIUI / ColorOS）即使 `usesCleartextTraffic=true` 也会拦截 OkHttp 的明文 HTTP 请求，但 raw socket 直连不受影响。
  - `MainActivity.onCreate` 与 `onResume` 触发 `forceProbe()` — 用户进入主页时 WiFi 几乎一定已验证，是再次探测的最佳时机。
  - 运行时 5 分钟 TTL 异步重探。
- **实时网络监听**：`NetworkChangeMonitor` 注册 `ConnectivityManager.NetworkCallback`，`onAvailable` / `onLost` / `onCapabilitiesChanged`（含 `NET_CAPABILITY_VALIDATED`）时立即触发 `forceProbe()`，无需等 TTL。
- **音频直播**：
  - 后端 `rtspURL()` 在摄像头 `capabilities.audio=true` 时追加 `#audio=aac` 启用 ffmpeg 转码（PCMA→AAC）。
  - ExoPlayer 通过 `setAudioAttributes(USAGE_MEDIA, CONTENT_TYPE_MOVIE, handleAudioFocus=true)` 走媒体音量并在来电时自动暂停。
  - 前端通过 `ExoPlayer.volume` 控制静音/取消静音，无需重新 prepare media source（瞬间切换）。
  - `onTracksChanged` 回调日志输出 video/audio 轨道数量，便于排查「无声」问题是否是后端轨道缺失。
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

启动时由 `BaseUrlResolver` 自动探测局域网 (`http://192.168.31.234:8088/`) 与远程 (`https://api.feiyemomo.top/`) 的可达性：

- 局域网 TTFB ~10ms，Cloudflare Tunnel 1.4s+，差距约 70 倍
- 探测端点 `GET /api/v1/system/status`（JWT 保护，401=API 存活）
- 启动时 `probeLanOnStartup()` 同步重试 2 次 + 400ms backoff，覆盖真机 WiFi 验证窗口
- 失败后延迟 3s 异步重探
- 运行时 5 分钟 TTL + ConnectivityManager.NetworkCallback 触发立即重探

详见 [util/BaseUrlResolver.kt](app/src/main/java/com/homedatacenter/app/util/BaseUrlResolver.kt) 与 [util/NetworkChangeMonitor.kt](app/src/main/java/com/homedatacenter/app/util/NetworkChangeMonitor.kt)。

如需手动固定服务器地址，可在 `AppContainer.kt` 中通过 `prefsManager.baseUrl` 注入（绕过自动探测）。

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
versionCode = 18
versionName = "1.4.3"
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
| GET | `/api/v1/user/me` | 当前用户信息（id / name / is_admin） |
| GET | `/api/v1/user` | 用户列表（管理员） |
| POST | `/api/v1/user` | 创建用户（管理员，可选 `initial_device_name`，返回一次性 `access_key`） |
| GET | `/api/v1/user/{id}` | 用户详情（管理员） |
| PUT | `/api/v1/user/{id}` | 修改用户（管理员，`name` / `is_admin` 部分更新） |
| DELETE | `/api/v1/user/{id}` | 删除用户（管理员，级联删除设备） |
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
| GET | `/api/v1/cameras/{id}/stream.mp4` | fMP4 直播流（主，含音频） |
| GET | `/api/v1/cameras/{id}/recordings` | 录像列表 |
| GET | `/api/v1/cameras/alerts` | 全局报警列表 |
| GET | `/api/v1/cameras/{id}/motion-ranges?after=UNIX&before=UNIX` | **v1.6.0**：指定时间窗口内的 motion 范围（用于录像 SeekBar 红标） |
| POST | `/api/v1/cameras` | 注册摄像头（管理员） |
| DELETE | `/api/v1/cameras/{id}` | 删除摄像头（管理员） |
| PUT | `/api/v1/cameras/{id}/codec` | 更新编码（管理员） |
| PUT | `/api/v1/cameras/{id}/audio` | 切换音频转码（管理员，`{enabled: bool}`） |
| POST | `/api/v1/cameras/{id}/ptz` | PTZ 控制（管理员） |
| PUT | `/api/v1/cameras/{id}/recording` | 设置录像计划 |
| GET | `/api/v1/cameras/{id}/presets/discover` | 发现 PTZ 预置位 |
| PUT | `/api/v1/cameras/{id}/presets/{alias}` | 设置预置位 |
| DELETE | `/api/v1/cameras/{id}/presets/{alias}` | 删除预置位 |
| POST | `/api/v1/cameras/{id}/preset/{alias}` | 跳转预置位 |

### WebRTC（v1.5.3 主路径，跨 LAN / 远程）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/cameras/ice` | ICE 配置 |
| POST | `/api/v1/cameras/{id}/webrtc` | WHEP SDP offer/answer（Content-Type: application/sdp） |

> v1.5.3：客户端通过 `WebRtcClient`（[io.getstream:stream-webrtc-android:1.3.10](https://github.com/getstream/stream-webrtc-android)，v1.5.4 升级以支持 Android 15+ 16KB page size）发起 recvonly PeerConnection，等待 ICE gathering 完成后 POST 完整 SDP offer（non-trickle ICE），后端返回 SDP answer 即开始 sub-second 直播。失败自动 fallback 到 MP4 → HLS。Cloudflare Tunnel 不转发 UDP，因此远程场景下 WebRTC 通常会失败并自动切到 MP4。

### 标准响应包络

```json
{ "code": 0, "message": "success", "data": <T> }
```

`code != 0` 表示业务错误，`message` 是可读消息。`ApiResponse.decodeData<T>()` 负责解包。

---

## 视频播放策略

### 直播（v1.5.3：WebRTC 优先，MP4/HLS 备用）

[CameraDetailActivity.kt](app/src/main/java/com/homedatacenter/app/ui/cameras/CameraDetailActivity.kt) 中的 `startPlayback` 按以下顺序尝试：

1. **WebRTC（主，v1.5.3）** — `POST ${base}/api/v1/cameras/{id}/webrtc`
   - `WebRtcClient`（`io.getstream:stream-webrtc-android:1.1.0`）创建 recvonly PeerConnection，等待 ICE gathering 完成（`GATHER_ONCE` + 轮询 `iceGatheringState()`，超时 5s）后 POST 完整 SDP offer 到 WHEP 端点。
   - ICE 配置来自 `GET /api/v1/cameras/ice`（缓存复用），LAN 场景下通常为空（host candidate 足够）。
   - 视频通过 `SurfaceViewRenderer` 渲染（与 ExoPlayer `StyledPlayerView` 并列于 `videoContainer`，初始 `visibility=gone`），音频通过 `JavaAudioDeviceModule` 走系统媒体流。
   - **延迟**：LAN host-candidate ~100-300ms，远程 STUN/TURN 500ms-2s。Cloudflare Tunnel 不转发 UDP，远程场景下 ICE 通常 FAILED → 自动 fallback。
2. **MP4（备 1）** — `${base}/api/v1/cameras/{id}/stream.mp4`（home-api 代理 go2rtc 的 fMP4 流）
   - ExoPlayer `ProgressiveMediaSource`
   - 之所以保留 MP4 作为 ExoPlayer 主路径：go2rtc 的 HLS `Init()` helper（`internal/hls/session.go`）只等 3 秒（60 × 50ms）让 consumer 产出第二个 packet，否则返回 nil → `handlerInit` 404。冷流 / 转码路径下 3 秒可能不够，ExoPlayer 又不会重试 init.mp4，会直接把 404 上报为 Source error。hls.js 会自动重试，但 ExoPlayer 不会，所以 MP4-first 更稳。
3. **HLS（备 2）** — `camera.stream.hls_url` 或 `${base}/api/stream.m3u8?src=<name>`
   - ExoPlayer `HlsMediaSource`
   - `MediaItem.LiveConfiguration`：`targetOffsetMs=3000`, `maxOffsetMs=10000`, `minOffsetMs=1000`（目标 ~3s 端到端延迟）
   - 低延迟 `DefaultLoadControl`：`minBufferMs=2000`, `maxBufferMs=5000`, `bufferForPlaybackMs=1000`, `bufferForPlaybackAfterRebufferMs=1500`
4. **失败** — 显示错误占位图，点击重试

### 录像 / 报警片段回放

`RecordingsDialog` / `AlertsDialog` 通过 ExoPlayer `ProgressiveMediaSource` 播放后端拼接的 MP4 片段。v1.5.3：
- `resize_mode="fit"`（信箱模式，避免裁切）
- 独立 `btnPlaybackSpeed` overlay（0.5x / 1x / 1.5x / 2x，从设置菜单移出）
- 独立 `btnFullscreen` overlay（强制横屏，与 `PlayerFullscreenHelper` 配合）
- 错误处理：失败显示 `tvVideoError`，不自动 fallback（录像为单文件，无备选路径）

**v1.6.0 整天播放增强** / **v1.6.1 修复**：
- **报警时段标红（motion-ranges）**：`RecordingsDialog.loadAlertRangesForDay` 改用 `GET /api/v1/cameras/{id}/motion-ranges?after&before` 替代旧 `/api/v1/cameras/alerts`。alerts 端点仅在 AI 检测到 person/car 等目标时才生成事件；对于普通家庭摄像头，Frigate 配置常常未跨过 AI 阈值，导致 alerts 列表为空，SeekBar 上无红标。motion-ranges 直接查询 Frigate 录制段的 `motion` 字段，返回所有像素级活动时间窗口，是「这里有动静」的更真实信号。返回格式为 `{"ranges": [[startUnix, endUnix], ...], "total": N}`，客户端用 `kotlinx.serialization.json.JsonArray` 手动解析（裸数组无法用 `@Serializable List<Pair<Long,Long>>` 直接解码）。红标渲染由 `AlertRangeOverlay` 自定义 View 完成（**v1.6.1：最小宽度 4px → 2px**，配合后端不再合并段后每个 10s Frigate 段独立显示为细红线）。
- **后端 v1.6.1：不合并 motion 段**：`ListMotionRanges` 的 `mergeGapSeconds` 从 10s 改为 0s。v1.6.0 把相邻段合并成大段导致 24h 时间线上显示成几条粗红条（用户报告"标红太宽了"）；改为不合并后每段独立，约 750 段/24h（v1.6.0 仅 77 段），用户能看到 motion 的精确起止时刻。
- **进度条吸附（snap-to-range）**：`onStopTrackingTouch` 释放 SeekBar 时调用 `snapProgressToRangeEdge(progress)` 检查最近的 motion range 边缘；若距离 < `motionSnapRadiusMs`（**v1.6.1：30s → 120s**，约对应屏幕上 1px）则自动吸附到该边缘。v1.6.0 的 30s 半径仅 0.25px，用户根本感受不到吸附；120s 让用户在拖动到 motion 附近时能明显感受到"咬住"边缘的效果。吸附后的 `progress` 更新 SeekBar 视觉与位置标签，并作为 seek 目标传给 ExoPlayer。
- **报警精确跳转**：`AlertsFragment.jumpToCameraAtTimestamp` / `DashboardFragment.jumpToCamerasWithAlert` 启动 `CameraDetailActivity` 时附带 `EXTRA_INITIAL_TIMESTAMP`（报警 `start_time`）；`CameraDetailActivity.onCreate` 检测到该 extra 后自动调用 `showRecordings(initialTimestamp)`，`RecordingsDialog` 构造函数接受 `initialTimestamp` 参数后通过 `pendingInitialTimestamp` 延迟到 `loadRecordings` 完成再触发 `openDayForTimestamp` —— **v1.6.1 修复了 v1.6.0 的时序竞态 BUG**：v1.6.0 在 init 中 `binding.root.post { openDayForTimestamp }` 总是在 `loadRecordings()` 网络请求完成前执行，导致 `allRecordings` 为空时 `playDayAsPlaylist` 过滤出空列表并显示"该日期无录像"。
- **默认进入整天模式（v1.6.1）**：`RecordingsDialog` 在 `loadRecordings` 完成后，如果没有 `initialTimestamp`，自动调用 `showDayPicker()` 弹出日期选择器——之前默认显示分条录像列表，整天播放是次要入口；按用户要求"以整天查看为先"调换位置，分条列表可通过取消日期选择器或点"返回列表"按钮进入。
- **按天列表 UI（v1.6.2）**：`RecordingsDialog` 默认列表从 60s-bucket 分条视图改为按 LOCAL 日期分组的现代化卡片列表（`item_day_recording.xml` + `DayRecordingAdapter`）。每张卡片显示：大日期数字 + 月份/年 + 星期 + 录像段数 + 总时长 + 绿色"N 段"状态 chip。点击任意一张卡片直接进入当天的 24h ExoPlayer 播放列表。`groupRecordingsByDay` 函数把后端返回的 ~10k 条 60s buckets 按 LOCAL 日期分组聚合成 ~7 张卡片（最新在前）。删除了 v1.5.x 的 `RecordingAdapter`、`item_recording.xml` 的引用、`loadMoreRecordings` 分页逻辑、`showDayPicker` 日期选择器（不再需要——列表本身就是按天选）、`playRecording` 单段播放方法（整天播放是唯一入口）。报警跳转的 `pendingInitialTimestamp` 仍生效——点击报警后仍自动跳到对应日期的整天播放并 seek 到精确时刻。
- **Motion chip 列表（v1.6.3）**：用户报告 v1.6.0-v1.6.2 的 SeekBar 红条方案"范围太大不精准"——根本问题是 24h/720px 时间轴上每像素 = 120s，红条永远只能显示成"宽 2-4px 的线段"，无法承载精确信息。v1.6.3 改用**报警事件 chip 列表**：在 SeekBar 上方新增 `HorizontalScrollView`，每个 motion range 渲染成一个 `item_motion_chip.xml` TextView，显示"HH:mm:ss · Ns"，点击即触发 `seekToMotionStart(chip)` 自动 seek ExoPlayer 到该 motion 起始时刻。chip 背景色编码 motion 强度：低（teal #4DB6AC）/ 中（amber #FFB300）/ 高（bright orange #FF7043）/ AI 检测（red #EF5350，`peak_objects > 0`）。chip 数量超过 200 时按 `motion_score` 排序保留 top 200，再按时间排序展示（避免繁忙日压垮 UI）。`AlertRangeOverlay` 同步优化：`minW` 从 2px 降到 1px（更精准），新增白色 tick dot 标记每个 range 起点，AI 检测段用更亮的 `#FF5252` 区分。后端 `MotionRange` 结构体返回预聚合字段 `start/end/duration/motion_score/segment_count/peak_objects`，客户端零现场计算；后端 60s TTL 缓存避免重复请求 Frigate 的 1-2s 慢查询。
- **全天交互式时间轴（v1.6.4）**：v1.6.3 的 chip 滚动列表用户反馈"划 chip 还是很慢，且不直观，无法一屏内看完整天"。v1.6.4 把 `AlertRangeOverlay` 完全重写为**交互式全天时间轴**：每个 motion range 按时间比例（`startMs/maxMs * width`）渲染到屏幕宽度的对应 X 位置，**24 小时全部一屏可见，无需滚动**。自然 motion 密度（早晚活跃、午间安静）直接呈现为「两边密、中间疏」的形态，正是用户想要的视觉效果。4-tier 颜色编码（teal/amber/orange/red）按 `motion_score` 四分位 + `peak_objects > 0` 阈值判定，覆盖层从 2 色升级到 4 色。点击交互：触摸 slop 内的 tap 触发 `onSeekToRange` 回调，调到 `seekToMotionStartMs` 走与 SeekBar 同样的 `clipStartOffsets` 二分查找 seek 路径；sub-pixel range 的 hit-rect 扩展到 `touchSlop` 宽度（10s range 在 720px 屏幕上仅 ~0.7px 宽，扩展后保证可点）。Overlay 独立成行（28dp 高，在 SeekBar 之上），不再与 SeekBar 共享 FrameLayout，避免 tap/drag 手势冲突。配套修复：① 倍速按钮 `formatSpeed` 把 `1.5f` 截断为 `1` 显示成"1x"的 bug（同时与"1.0x"重复）—— 改为 `speed == speed.toInt().toFloat()` 判断整数速度；② 全屏退出后 `playerView` 高度从 `MATCH_PARENT` 被错误还原成 200dp（`savedPlayerHeightPx` 默认值 0 + `if (params.height > 0) save(...)` 跳过 -1 + fallback 到 `DEFAULT_PLAYER_HEIGHT_DP`）—— 改为始终保存原始 `height` 值，恢复时直接使用，默认值改为 `MATCH_PARENT`；③ `btnFullscreen` 从 `bottom|end` 移到 `top|end`（marginTop 54dp），`btnBack` 从 `bottom|center_horizontal` 移到 `center`（marginBottom 80dp），消除与底部 scrub bar 的重叠。
- **报警推送精确跳转**：`AlertsFragment.onJumpCamera` / `DashboardFragment.jumpToCamerasWithAlert` 现在直接 `startActivity(CameraDetailActivity)`，Intent extra `EXTRA_INITIAL_TIMESTAMP = alert.startTime.toLong()`。`CameraDetailActivity.onCreate` 检测到该 extra 后 `post { showRecordings(initialTs) }`，`RecordingsDialog` 构造参数新增 `initialTimestamp: Long`，内部 `openDayForTimestamp` 锚定到报警所在日期并构建播放列表，`pendingAlertSeekMs` 标志在 ExoPlayer 首次 `STATE_READY` 时一次性 seek 到报警精确时刻（用 `clipStartOffsets.binarySearch` 计算目标 window 与位置）。失败回退到旧的「切换到 cameras tab」行为。DashboardFragment 的实时报警横幅也加了点击监听，复用同一 jump 逻辑。

### Surface 时序

`StyledPlayerView` 必须在 XML 中声明（不能通过 Compose `AndroidView` 异步创建），且 `binding.playerView.player` 必须在 `prepare()` **之前**设置。否则 ExoPlayer 在 `prepare()` 时无 surface 可用，会触发 MediaCodec `setOutputSurface -- failed to set consumer usage (BAD_INDEX)` 错误，导致 98% 的 buffer 未被取出。

详见 [错误教训 §20](docs/ERRORS.md#20-exoplayer-prepare-时无-surface-导致-mediacodec-bad_index)。

### 音频路由

ExoPlayer 通过 `setAudioAttributes` 配置媒体路由：

```kotlin
newPlayer.setAudioAttributes(
    com.google.android.exoplayer2.audio.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
        .build(),
    /* handleAudioFocus = */ true,
)
```

- 走媒体音量（不是通话 / 铃声音量）
- `handleAudioFocus=true`：来电时自动暂停播放，挂断后自动恢复
- 类型用 `com.google.android.exoplayer2.audio.AudioAttributes`（不是 `android.media.AudioAttributes`），否则编译报参数类型不匹配

后端在 `internal/camera/registry.go` 的 `rtspURL()` 中根据 `cam.Capabilities["audio"]==true` 自动追加 `#audio=aac`，让 go2rtc 通过 ffmpeg 把摄像头的 PCMA 音频转码为浏览器/Android 可解码的 AAC。详见 [错误教训 §19](docs/ERRORS.md#19-go2rtc-音频转码-audioaac)。

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
12. **真机 LAN 探测失败（WiFi 已连接但未 validated）** → `probeLanOnStartup` 加重试 + backoff + 延迟异步重探
13. **XML 注释里出现 `--`** → AAPT 编译失败，注释中不允许双连字符
14. **ExoPlayer AudioAttributes 类型用错** → 必须用 `com.google.android.exoplayer2.audio.AudioAttributes`，不是 `android.media.AudioAttributes`
15. **go2rtc 默认不转码音频** → PCMA 不可播放，需在流 URL 追加 `#audio=aac` 走 ffmpeg
16. **ExoPlayer prepare() 时无 surface** → MediaCodec `setOutputSurface BAD_INDEX` + 98% buffer 未取出，必须在 `prepare()` 前设置 `playerView.player`
17. **HLS Init() 只等 3 秒** → 冷流 404，ExoPlayer 不重试 init.mp4，改为 MP4 优先策略
18. **HEAD 探测 Gin GET 路由返回 404** → 探测后端端点必须用 GET，详见错误教训 §6 附加发现

---

## License

Private / 家庭项目，未指定开源协议。
