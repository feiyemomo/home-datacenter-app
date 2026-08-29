# Home Datacenter App

家庭数据中心 Android 客户端 — 一个用 **Kotlin + Jetpack Compose + ExoPlayer + WebRTC** 实现的家庭 NVR / IoT 控制台，配合 [home-datacenter](https://github.com/feiyemomo/home-datacenter) 后端使用，提供摄像头预览、WebRTC/MP4/HLS 直播（含音频）、录像回放、报警查看、设备状态、天气信息、局域网/远程自动切换和实时 WebSocket 推送。

> 服务端项目：<https://github.com/feiyemomo/home-datacenter>
> 当前版本：**v1.9.1**（versionCode 127）

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
| 当前版本 | 1.9.1 (versionCode 128) |
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
- **底部导航**：Material 3 ActiveIndicator 胶囊样式，5 个 Tab（主页 / 摄像头 / 日志(管理员) / 用户管理(管理员) / 设置），高度 72dp 防止文字与图标重叠。
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

Release 构建使用**正式 keystore**（`app/keystore/home-release.jks`，V1/V2/V3 签名），
发布版携带稳定、可升级、可在应用商店验证的官方身份。keystore 与凭据是敏感文件：

- `app/keystore/home-release.jks` 与 `keystore.properties` **已被 `.gitignore` 排除，绝不入库**。
- 签名配置在 `app/build.gradle.kts` 的 `releaseSigning`，从 `keystore.properties` 读取
  `RELEASE_STORE_FILE / RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD`。
- 若 `keystore.properties` 缺失，release 构建会**直接失败**而非产出未签名 APK（防止静默发布坏包）。

**备份（务必）**：`home-release.jks` 丢了就无法再为已发布 APK 出升级包。请运行
`.\backup-keystore.ps1`（复制到仓库外目录并生成恢复指南），并把备份拷贝到离线/U盘/密码管理器。

**CI 自动构建**：`.github/workflows/release.yml` 提供 GitHub Actions 流水线——
- 推 `main`：跑 JVM 单元测试（`app/src/test/**`）。
- 打 `v*` tag 或手动触发：用 GitHub Secrets 重建 keystore 并产出现正式签名的 `app-release.apk`。
- Secrets 配置见 `SECRETS.md`。

**debug / release 共存**：debug 构建带 `applicationIdSuffix = ".debug"`（包名
`com.homedatacenter.app.debug`），release 为 `com.homedatacenter.app`，两者是独立应用、
可同时安装（此前因签名不同且包名相同，用 release 覆盖安装 debug 会报「软件包冲突」）。

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
- **Fisheye chip 滑动 + 中心放大替换 + 全屏按钮贴角 + btnBack 美化（v1.6.4 rev5）**：v1.6.4 rev4 的 chip "滑动不了，我想要滑动的时候，中间大的chip跟着替换"。rev5 四处改动：① **chip 改回滑动**——`FisheyeChipScroller` 从 `ViewGroup` 改回 `HorizontalScrollView` 子类，在 `onScrollChanged` / `onLayout` 中遍历 chip 应用 fisheye 变换：`dx = abs(chipCenterX - viewportCenterX)`，`dx ≤ 80px`（中心带）scale=1.0 显示"HH:mm"文字，超出后线性衰减到 `minScale=0.4`；当 scale < `textThreshold=0.65` 时清空 text + 强制 width=3dp → 边缘 chip 变成无文字彩色细条。alpha 始终 1.0（不淡化）。新增 `scrollToCenterChip(idx)` 让点击 chip 后该 chip 滚动到 viewport 中心成为焦点（"中间大的chip跟着替换"）。② **chip cap 调回 100**（rev4 是 60，rev4 是 fit-to-screen 不滚动；rev5 滑动所以可以更多）。③ **初始定位到当前播放位置**——`populateMotionChips` 末尾遍历找最接近当前 ExoPlayer `currentPosition` 的 chip，调用 `scrollToCenterChip` 把它居中，给用户"you are here"焦点。④ **全屏按钮贴角**——`btnFullscreen` margin 从 2dp → 0dp（紧贴右下角），背景透明（去掉黑底），iconSize 36dp → 40dp，padding 12dp → 14dp。⑤ **"返回列表"按钮美化**——从丑陋的 `Widget.Material3.Button` 改为 `Widget.Material3.Button.OutlinedButton.Icon` 风格：圆角 24dp（pill 形）+ 1dp 半透明白描边 + 左侧 ic_baseline_arrow_back_24 图标 + 白色文字（在黑色视频背景上对比清晰）。
- **液态玻璃 + 温馨风格 + 播放器交互增强 + 摄像头卡片放大（v1.6.5 rev6）**：用户提出"记住设计风格：液态玻璃，温馨"。v1.6.5 五处改动：① **后端 tier-aware chip 合并**——`ListMotionRanges` 不再用统一的 2s gap，改为按 motion 强度分级：LOW tier（motion≤2 且 objects==0，绿色）之间用 60s gap 合并（"quiet period" 内的零碎低运动段归并为一条），MID/HIGH/ALERT 之间保持严格 2s gap 保留事件边界。`curLowTier` 状态在遇到非低运动段时切换为 false，确保高运动事件进入后段不会再被错误合并。修复 v1.6.4 rev5 chip "绿色段太碎"的问题。② **录像查看液态玻璃背景**——新增三个 drawable：`bg_liquid_glass_warm.xml`（warm cream→soft blue 渐变 + 18% 白色覆盖层模拟 frosted glass）、`bg_glass_dark_panel.xml`（60% 深蓝灰 + 1dp 20% 白色描边，用于 player 上的 toolbar / 进度条 / chip 滚动器覆盖层）、`bg_glass_card_warm.xml`（95% 暖白 + 1.2dp 暖桃色描边 + 22dp 圆角，用于摄像头列表卡片）。`dialog_recordings.xml` 根背景从纯白换为 `bg_liquid_glass_warm`，toolbar / dayScrubBarContainer / motionChipScroller 背景从 `#CC000000` / `#1A000000` 换为 `bg_glass_dark_panel`。③ **播放器交互增强**——新增 `PlayerGestureHelper.kt`，在 playerView 上挂 `GestureDetector`：(a) **暂停键**——`btnCenterPause` 56dp 圆形玻璃按钮居中浮在播放器表面，单击播放器表面切换显隐（2s 后自动隐藏），点击按钮切换 ExoPlayer play/pause 并 swap 图标（ic_pause ↔ ic_play_circle）；(b) **双击屏幕左右实现快退进**——左 40% 区域双击 seek -10s，右 40% 双击 seek +10s，中间 20% 保留给暂停按钮（避免误触）；(c) **长按屏幕实现倍速播放**——`onLongPress` 期间 playback speed 跳到 3.0x 快进，松手（ACTION_UP/ACTION_CANCEL）恢复到 `savedSpeed`（用户原本选择的速度）。`RecordingsDialog` 在 player 创建后 attach，dismiss / btnBack 时 release。④ **摄像头卡片放大 + 文字减少**——`CameraCard.kt` 卡片高度 84dp → 132dp，缩略图 128×72dp → 192×108dp（2.25× 面积），背景从冷暗 `#171C24` 改为暖液态玻璃 `#F2FFFFFF` + 暖桃描边 `#66FFD4B8`，文字色从白改为深 slate `#2D3748`（在暖白玻璃上对比清晰）。删除 vendor 行，仅保留摄像头名 + codec/audio 徽章，文字 footprint 大幅减少。`CameraAdapter.kt` 缩略图 `inSampleSize` 4 → 2 以匹配更大的渲染尺寸（960×540 解码后的 bitmap 在 16 条 LRU 中约 16MB 内存上限）。
- **chip 进一步合并 + Fisheye 修复 + 进度条 tier 标注 + Slider 变速 + 5x 倍速 + 双击《》动画 + 录像背景透出（v1.6.6 rev7）**：用户反馈"chip还是很多；两头的chip不能完全拉出来；点击相应chip之后chip跳乱滚；把展示出来的chip区间大概标注再进度条上面吧；变速条改为可滑动的，最高切到5x吧，长按倍速也改为5x；双击快退进改为两个单书名号样式闪烁两下的动画吧；查看录像时的背景怎么还是一片黑色啊"。v1.6.6 八处改动：① **后端 tier 合并扩展到三档**——`ListMotionRanges` LOW gap 60s→180s（3 分钟 quiet period 归一条），新增 MID tier（motion 3-5）15s gap，HIGH/ALERT 保持严格 2s。同 tier 使用该 tier gap，跨 tier 严格 2s；`curTier` 遇高 tier 升级后不降级，避免高事件被错误合并。② **FisheyeChipScroller 修复跳乱滚**——简化 transform 为纯 `scaleX`/`scaleY`（不再 toggle `layoutParams.width`），消除每帧 `requestLayout()` 风暴；`scrollToCenterChip` 改用 `smoothScrollTo` 替代 `scrollTo + post{}` 跳变。③ **首尾 chip 边缘 padding**——`updateContainerEdgePadding()` 将 container 左右 padding 设为半屏宽 + `clipToPadding=false`，使首尾 chip 可滚动到 viewport 中心。④ **AlertRangeOverlay tier 上色**——新增 `setTieredRanges(TieredRange)` + `TIER_LOW/MID/HIGH/ALERT` 四档 paint（teal/amber/orange/red，与 chip 颜色完全一致），`RecordingsDialog` 调用 `setTieredRanges` 把 chip 区间以同色标注在 SeekBar 上方；overlay 高度 12→14dp 提升可见性。⑤ **变速改为水平 Slider**——新增 `speedBarContainer` (220dp LinearLayout) 包含 Material `Slider`（0.5-5.0 step 0.5）+ `tvSpeedLabel`，浮于 playerView 顶部居中。Slider `addOnChangeListener` 实时更新 ExoPlayer `PlaybackParameters` + 标签。⑥ **长按 5x**——`PlayerGestureHelper.longPressSpeed` 3.0f→5.0f；新增 `onSpeedChangedBySlider()` 让 slider 拖动同步 `savedSpeed`，松手恢复到 slider 当前值（而不是过时的 pre-slider 快照）。⑦ **双击快退进《》闪烁动画**——新增 `tvSeekRewindHint` / `tvSeekForwardHint` TextView（56sp 《》），`playSeekHintAnimation()` 用 `ObjectAnimator.ofFloat(view, "alpha", 0f, 1f, 0f, 1f, 0f)` 500ms 闪烁两下，结束自动 GONE。⑧ **录像查看背景透出**——`videoContainer` 背景从 `@android:color/black` 改为 `transparent`，让根布局 `bg_liquid_glass_warm` 暖色渐变从 playerView 下方区域透出，配合 `bg_glass_dark_panel` 浮层形成液态玻璃分层效果。
- **chip ⋯ 合并 + 进度条区间并集 + 移除浅灰背景 + btnBack 提高对比度（v1.6.7 rev8）**：用户反馈"我是指位于中间的一些chip的时段并集大概标注在进度条上面；一系列连续绿色的之间就用省略号或其他形式代替，你觉咋好看咋来，就算翻到中间也不用展开；chip,进度条这些的浅灰色背景不可用去掉吗；返回列表的对比度太低了"。v1.6.7 四处改动：① **chip ⋯ 合并**——`RecordingsDialog.populateMotionChips` 重写：扫描 `sorted` 列表检测连续 LOW-tier（peak==0 且 motion < maxScore/4）runs，run 长度 ≥3 则折叠为单个"⋯" chip（teal 背景、`U+22EF HORIZONTAL ELLIPSIS` 字符）。"⋯" chip 的 click → seek 到 run 中间的 MotionChip。"就算翻到中间也不用展开"通过 chip 一旦生成不再变更实现——FisheyeChipScroller 只对 scale 做 transform，不重排 child。"⋯" 是单字符，在 fisheye 缩放下行为与普通 chip 一致。为避免 label 误匹配（多个 "⋯" 共享同一 tag），新增 `childIndexToMotionChip: Map<Int, MotionChip>` 字段在 populateMotionChips 中填充，`seekToMotionStart` 改用 `===` 引用相等查找目标 chip 的 container index，无法找到时降级为旧的 label 匹配。初始定位（findClosestChip）也通过 `containerToSortedIdx` 把 logical chip index 翻译到 container child index，让 ellipsis chip 仍可作为 "you are here" 焦点。② **进度条区间并集**——`AlertRangeOverlay.setTieredRanges` 新增 `consolidateRanges(ranges, gapMs=30s)`：连续同 tier 且 gap ≤ 30s 的 range 合并为一条（min start, max end），把若干小色块合并成一条宽色条。"位于中间的一些chip的时段并集"指此——后台已合并 3 分钟 quiet period，前端再合并视觉上相邻的同色 marker。③ **移除浅灰背景**——`motionChipScroller` 与 `dayScrubBarContainer` 背景 `bg_glass_dark_panel` → `transparent`。之前在 transparent `videoContainer` 上的半透深色面板会显出"浅灰"效果，移除后 warm cream 渐变直接从 chip/进度条区透出，与液态玻璃主题一致。④ **btnBack 提高对比度**——`Widget.Material3.Button.OutlinedButton.Icon`（1dp 50% 白色描边 + 白色文字）→ `Widget.Material3.Button.Icon`（filled）+ `backgroundTint #B3000000`（70% 黑）+ 白色粗体文字 + minWidth 120dp。在暖色渐变上深色 filled pill 对比度极强，明显可见。
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

## 更新日志

### v1.7.26 — 核查缓存修复 + 审计日志大幅拓展 (2026-08-12)

#### 核查后刷新回现 bug 修复
- 修复核查日志后下拉刷新时被核查日志又出现在"待处理日志"栏的 bug
- 根因：核查成功后未清除 `CacheManager` 中的 `logs.page.*` 分页缓存，刷新时读到旧 critical 级别数据
- 修复：核查成功后调用 `CacheManager.clear("logs.page.")` 清除所有日志分页缓存

#### 审计日志大幅拓展（配合服务端 v1.8.22）
- **摄像头管理事件**：新增摄像头注册、更新编码/音频/录制计划的审计日志
- **自动化规则事件**：新增规则触发、创建/更新/删除的审计日志
- **设备管理事件**：新增设备硬删除、Token 轮换的审计日志
- **运动检测报警**：摄像头检测到运动时记录 info 级别日志
- **修复 dead topic**：`camera.status_changed` 事件之前已被订阅但从未发布，现在 health.go 在状态变更时补发

### v1.7.25 — 日志核查降级 + 下拉刷新修复 + 更新流程优化 (2026-08-12)

#### 日志核查改为降级而非删除
- 核查按钮不再删除日志，改为调用 `PATCH /api/v1/system/logs/:id` 将日志级别从 `critical` 降级为 `normal`
- 日志从"待处理日志"栏移除，但在"所有日志"栏继续保留，完整审计轨迹不丢失
- 持久化到服务端，刷新后仍然有效

#### 下拉刷新持续转圈 bug 修复
- 修复缓存命中路径（`loadNextPage` 中的 cache-hit early return）未调用 `swipeRefresh.isRefreshing = false` 导致下拉刷新永久转圈的问题

#### 更新检查流程优化
- 更新检查从 `HomeCenterApp.onCreate` 移至 `SplashActivity` 预加载阶段，与仪表盘预取并行执行
- APK 下载断点续传增强：失败后自动重试 3 次（立即 → 5s → 15s），每次从 `.part` 文件断点继续，中断后自动重连

#### 用户列表调整
- 用户 tab 元信息行用"用户 ID"替换"设备数"显示

### v1.7.24 — 日志核查修复 + 审计日志扩展 (2026-08-12)

#### 日志核查按钮修复
- 修复"核查"按钮无效 bug：原来仅在内存中标记已核查，刷新后失效；现在点击后调用 `DELETE /api/v1/system/logs/:id` 从服务端删除日志条目，并从列表实时移除
- 新增 `pendingDeleteIds` 防重复点击机制：跟踪删除中的日志 ID，避免重复 API 调用
- `ServiceLogAdapter` 新增 `onVerifyDelete` 回调接口，`ServiceLogsFragment.verifyAndDeleteLog` 负责调用 API 并更新 UI

#### 审计日志扩展（配合服务端 v1.8.20）
- 用户登录 / 登出事件重新纳入日志记录
- 新增用户创建 / 更新 / 删除事件记录（管理员操作审计）
- 新增摄像头删除事件记录
- 日志消息使用中文人类可读格式（如"管理员 admin 创建用户 alice"、"用户 admin 登录（设备 我的手机）"）

### v1.7.23 — 对话框液态玻璃风格 (2026-08-12)

#### 液态玻璃对话框
- 新增 `bg_dialog_glass.xml`：26dp 圆角磨砂玻璃对话框背景（暖色边框 + 顶部折射高光 + 柔和阴影），颜色通过 `@color/glass_*` 自动适配暗色模式
- 新增 `dialog_glass_in` / `dialog_glass_out` 动画：对话框淡入 + 轻微缩放进入 / 溶解退出，替代生硬弹出
- `themes.xml` 新增 `GlassDialogAnimation` 窗口动画样式，明 / 暗主题均接入 `android:windowAnimationStyle`
- 全部对话框应用玻璃风格：报警快照、报警列表、录像回放、注册设备、更新提示
- 列表项（报警 / 设备 / 用户）与卡片背景同步微调，与液态玻璃暖色主题一致

### v1.7.22 — APK 下载断点续传 (2026-08-12)

#### 断点续传
- `ApkInstaller.downloadOnly` 改用 `.part` 文件 + HTTP `Range` 请求实现断点续传
- 弱网下载失败后，下次重试从已下载位置继续，而非从头开始
- `HomeCenterApi.downloadLatestApk` 新增可选 `Range` 头参数，返回 `Response<ResponseBody>` 以区分 200（完整下载）/ 206（续传）
- 下载完成后校验文件大小，`rename .part → 最终文件名`（原子操作）
- 处理 416 Range Not Satisfiable：删除过期 `.part` 文件，下次完整重下
- 进度回调正确反映续传起始百分比（如从 60% 开始继续）
- 服务端无需修改（Go `http.ServeFile` 已原生支持 Range 请求）

### v1.7.21 — 预加载并行化与开屏时间利用 (2026-08-11)

#### WebRTC + HLS/MP4 并行预 prepare
- `CameraDetailActivity` 新增 `fallbackPlayer` 字段和 `prepareFallbackPlayer` 方法
- 启动 WebRTC 协商时同时创建 ExoPlayer 并 `prepare` HLS/MP4 source（`playWhenReady=false`）
- WebRTC `onConnected`：释放 `fallbackPlayer`（成功无需 fallback）
- WebRTC `onError`：将 `fallbackPlayer` 提升为主 player 并立即播放，省去构建+prepare 延迟
- fallback 切换延迟从 500ms-2s 降至 ~100-300ms
- 生命周期管理：`releaseExoPlayerOnly` 同时释放 `fallbackPlayer`，无 MediaCodec 泄漏

#### 开屏期间预取首屏数据
- `SplashActivity` 已登录路径并行预取 `system.status` / `weather` / `alerts` / `cameras.list`
- 数据写入 `CacheManager`，key 与 `DashboardFragment` 实际读取一致
- `routeToNext` 等待条件改为 `max(900ms 动画, +1100ms 预取等待)`，总 2000ms 超时兜底
- 未登录路径保持原 900ms 固定，不触发预取

### v1.7.20 — 全链路预加载清理 + 冗余修复 (2026-08-11)

#### 死代码移除
- 移除 `takeWarmWebRtcClient()`（v1.6.10 遗留兼容 API，零调用点）
- 移除 `BaseUrlResolver.switchTo()`（v1.6.26 遗留，`probeSync` 已改用 `applyResolved`）
- 移除 `PrefetchManager.cancelPending()`（零调用点）

#### 修复
- 接入 `tryAutoRefreshToken()` 到 `HomeCenterApp.onCreate`（v1.8.15 编写但从未调用，现为每月 JWT 静默刷新接入启动流程）
- 移除 `CameraDetailActivity.onCreate` 中的 `preheatCamera` 冗余调用（`CamerasFragment` 已在 <100ms 前触发）
- 给 `prefetchIceConfig` 加 `AtomicBoolean` 锁，防止首次启动多入口并发 2 次 GET

#### 文档
- 更新 `ARCHITECTURE.md` 第 4.6 节：移除已废弃的 `dynamicIpv6Url`/`tokenProvider`/`fetchDynamicIpv6Url` 描述，改为 v1.7.19 DDNS 域名方案

### v1.7.19 — 网络探测快速路径先行 + 开屏动画 (2026-08-11)

#### 性能优化
- **网络探测快速路径先行**：LAN/IPv6 探测完成且存活即立即切换，取消仍在等待的 Tunnel 探测，不再为等 Tunnel（~1.4s）而延迟切换
- 典型场景提速：家庭网络 ~1.4s → ~50ms；蜂窝 IPv6 ~1.4s → ~200ms
- 仅当两条直连路径（LAN + IPv6）都不可用时，才等待 Tunnel 作为兜底

#### 重构
- 移除 `/api/v1/network/ipv6` 冗余调用：`IPV6_DIRECT_URL` 已使用 DDNS 域名（`nas.feiyemomo.top`），AAAA 记录由 DDNS 提供商自动跟踪前缀轮换，无需再调后端接口验证
- 删除 `fetchDynamicIpv6Url` 函数、`dynamicIpv6Url` 变量、`tokenProvider` 注入及相关后台刷新线程

#### UI
- 新增开屏动画（`SplashActivity`）：品牌入场动画，根据登录状态路由到 `MainActivity` 或 `LoginActivity`
- 冷启动背景改为暖色渐变 + 居中 logo，消除渲染前的黑/白闪屏
- Tab 切换动画由淡入淡出改为滑动过渡

#### 修复
- 修复首页"最近报警"卡片"全部"按钮跳转位置错误：原跳转到服务日志 tab（不含报警数据），改为跳转到摄像头 tab 的"全部报警"分区
- 摄像头 tab 新增"全部报警"分区

### v1.7.17 — 主题切换 CancellationException 修复 (2026-08-02)

#### 修复
- **主题切换闪退**：Activity 重建时取消所有 Fragment `lifecycleScope` 协程，`CancellationException` 被通用 `catch (e: Exception)` 捕获并显示为"job was cancelled"
- **ViewBinding 空指针**：`catch`/`finally` 块在 `onDestroyView` 后访问已销毁的 binding 导致 NPE
- **Fragment 重复添加**：`setupFragments()` 无条件调用 `add()` 导致已恢复的 Fragment 抛出 `IllegalStateException`

#### 修复方式
- 所有 6 个 Fragment 添加 `catch (e: CancellationException) { throw e }` 在通用 Exception 捕获之前
- 所有 `catch`/`finally` 块添加 `view != null` 检查
- `setupFragments()` 仅在 `savedInstanceState == null` 时添加 Fragment

### v1.7.15 — 主题切换 Gradient 角度修复 (2026-08-02)

#### 修复
- 5 个 drawable 文件中 `angle="-90"` 导致 Android 崩溃（要求非负 45 的倍数），改为 `angle="270"`
- 补全暗色主题 Missing Material3 颜色属性（`colorSurface`, `colorOnSurface`, `colorSurfaceVariant`, `colorOnSurfaceVariant`, `colorOutline`）

### v1.7.14 — 液态玻璃暖色风格升级 (2026-08-02)

#### 新增
- **颜色系统**：主色从珊瑚橙改为暖琥珀色（`colors.xml` 明暗双模式）
- **玻璃效果**：软阴影 + 顶部高光 drawable（`bg_glass_card.xml`, `bg_button_primary.xml` 等）
- **组件更新**：主按钮暖色渐变、CameraCard Compose 暗色模式适配、底部导航玻璃样式
- 14 个文件修改（+341/-188 行）

### v1.6.36 — 服务日志系统 + 摄像头预热 (2026-07-31)

#### 新增
- **服务日志 Tab**：`SystemLog` 模型，显示后端事件日志（设备/摄像头上下线）
- **摄像头当前状态显示**：日志列表中每条 `camera.*` 日志显示"当前状态：在线/离线"副标题
- **Dashboard 最近日志卡片**：显示最近 5 条服务日志，WebSocket 实时更新

#### 修复
- **心跳误判为设备上线**：`SetOnline()`/`SetOffline()` 缺少转换守卫，每次 WebSocket 重连都发布重复事件
- **MQTT 心跳冗余日志**：`handleStatus()` 尾部无条件重发 `device.status` 事件

#### 优化
- **日志分级**：`SystemLog` 新增 `Level` 字段（critical/normal/info），图标着色
- **ICE 配置预取提前**：从 `DashboardFragment.onResume` 提前到 `HomeCenterApp.onCreate`

### v1.6.33 — DDNS 域名统一识别 (2026-07-30)

#### 修复
- **`fetchDynamicIpv6Url()` 返回字面量 URL**：改为返回 `IPV6_DIRECT_URL`（DDNS 域名），DDNS 提供商自动跟踪前缀轮换

### v1.6.30 — Android 网络策略同步 (2026-07-30)

#### 修复
- **`BaseUrlResolver` IPv6 回退地址陈旧**：更新为当前 ISP 前缀
- **Dashboard 首次网络状态缓存**：首次调用传 `refresh=true` 强制后端刷新

### v1.6.29 — 延迟显示修复 (2026-07-22)

#### 修复
- Dashboard 网络质量卡片显示值从 ~500ms 降到 ~250ms
- `updateRttFromApiCall()` 让真实 API 调用 RTT 写回显示值
- `probeSync()` 在 probe 前先 warmup 当前 resolved URL
- ConnectionPool keep-alive 5 分钟 → 10 分钟

### v1.6.28 — IPv6 直连延迟优化 (2026-07-22)

#### 优化
- **OkHttp ConnectionPool**：显式配置 `.connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))`
- **warmupConnection**：`probeSync()` 检测到 URL 变化时通过 `HEAD` 请求预建 TCP 连接

### v1.6.27 — 动态 IPv6 地址获取 (2026-07-22)

#### 新增
- **`fetchDynamicIpv6Url()`**：从后端 `/api/v1/network/ipv6` 动态获取 NAS IPv6 地址
- **`tokenProvider`**：late-binding lambda 注入 JWT，登录后立即生效

### v1.6.24 — Tunnel 路径尝试 WebRTC + HLS 延迟提示 (2026-07-21)

#### 变更
- **WebRTC 在所有路径尝试**：不再在调用 `startWebRtcStream()` 前检查 `isDirectPath()`
- **HLS 延迟提示**：HLS 激活时显示"网络质量差，延迟较大"提示

### v1.6.7 — Chip 合并 + 进度条并集 + 移除浅灰背景 (2026-07-19)

#### 优化
- **Chip ⋯ 合并**：连续 LOW-tier chip 折叠为"⋯"字符
- **进度条区间并集**：连续同 tier 且 gap ≤ 30s 的 range 合并
- **移除浅灰背景**：motionChipScroller 背景透明
- **btnBack 提高对比度**：filled pill 样式

---

## License

Private / 家庭项目，未指定开源协议。
