# Home Datacenter App

家庭数据中心 Android 客户端 — 一个用 **Kotlin + Jetpack Compose + ExoPlayer + WebRTC** 实现的家庭 NVR / IoT 控制台，配合 [home-datacenter](https://github.com/feiyemomo/home-datacenter) 后端使用，提供摄像头预览、WebRTC/MP4/HLS 直播（含音频）、录像回放、报警查看、设备状态、天气信息、局域网/远程自动切换和实时 WebSocket 推送。

> 服务端项目：<https://github.com/feiyemomo/home-datacenter>
> 当前版本：**v1.11.0**锛坴ersionCode 139）

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
- [更新日志](#更新日志)
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
| 当前版本 | 1.12.2 (versionCode 143) |
| 默认服务器 | `https://api.feiyemomo.top/`（远程） / `http://192.168.31.235:8088/`（局域网，自动探测并持久化） |

App 通过 `(user_id, access_key)` 换取 JWT 后访问 `home-datacenter` 的 REST API 与 WebSocket。**BaseUrlResolver** 在启动时通过后台守护线程异步探测局域网 `http://192.168.31.235:8088/` 是否可达（TTFB ~10ms vs Cloudflare Tunnel 1.4s+），可达则切到局域网，否则走远程 Cloudflare Tunnel。冷启动会优先恢复上次有效网络路径，使家庭 Wi-Fi 场景下首个请求即命中内网。启动调度采用指数退避重试（1.5s → 4s → 9s → 16s），覆盖真机「WiFi connected but not validated」窗口；同时附加 TCP socket 直连探测作为 OkHttp cleartext 拒绝时的兜底。NetworkChangeMonitor 注册 ConnectivityManager.NetworkCallback，在 WiFi/移动网络切换时立即触发 re-probe，无需等 5 分钟 TTL。摄像头直播走 go2rtc 暴露的 MP4（主）+ HLS（备），后端根据摄像头 `capabilities.audio` 在 go2rtc 流 URL 上自动追加 `#audio=aac` 启用音频转码，前端通过 ExoPlayer `volume` 控制静音/取消静音。

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

> 💡 早期完整版本演进记录（v1.6.7 ~ v1.9.1）已统一收拢归档至文档：[docs/CHANGELOG_ARCHIVE.md](docs/CHANGELOG_ARCHIVE.md)。

### 版本里程碑演进

| 大版本 | 核心演进方向 |
|---|---|
| **v1.10.x** | 全局架构演进、冷启动无阻协程预加载、Tab 按需懒加载、Compose 列表复用、Token 防风暴锁与内网兼容加固 |
| **v1.9.x** | 框架与安全性加固、Token 自动轮换机制规范化、预取生命周期安全回收 |
| **v1.8.x** | 录像配额与 Warning 告警系统、TokenRefreshInterceptor 401 自动恢复、后台下载增强 |
| **v1.7.x** | 暖琥珀液态玻璃风格重构、快速路径先行探测、断点续传 APK 安装器、主题切换安全加固 |
| **v1.6.x** | 服务日志体系、WebRTC 全路径尝试、DDNS 动态 IPv6 适配、录像进度合并优化 |

---

### 最新版本详情

### v1.10.9 (versionCode 138) — 录像片段媒体库导出、四分屏多路同屏与安防事件多维过滤 (2026-09)
- **录像片段相册导出**：`RecordingsDialog` 在视频播放工具栏提供专属【保存到本地】按钮，一键将当前播放的 60 秒 MP4 录像片段流式下载到手机 `Movies/HomeDatacenter` 目录，通过 `MediaStore` 自动注册索引，保存后系统相册即时可查并支持一键分享。
- **多路监控大厅（四分屏 2x2 宫格）**：新增 `MultiCameraActivity` 与专属入口按钮，支持同时渲染 4 路摄像头的实时画面与在线状态；点击任意一路无缝平滑进入单路全功能详情大图。
- **AI 安防事件多维过滤与回放联动**：`AlertsDialog` 引入目标分类过滤标签组（全部 / 人形 / 车辆 / 动物），支持按需筛选；点击事件卡片直接携带精确时间戳拉起 `RecordingsDialog`，自动精确定位至事发录像片段。

### v1.10.8 (versionCode 137) — 画中画悬浮播放与系统级安防告警通知 (2026-09)
- **画中画（Picture-in-Picture）**：`CameraDetailActivity` 接入 Android 原生画中画模式，切出应用或按下 Home 键自动进入小窗监控，播放栏与顶栏提供专属画中画入口；浮窗模式下自动隐藏操作面板填满视口，返回前台无感复原。
- **WebRTC 快速熔断降级**：看门狗超时调优（局域网 3.5s，远程 4.5s），消除网络抖动或防火墙阻断时的漫长黑屏等待，快速平滑降级至 MP4 直播流。
- **系统级安防与运维通知（Heads-Up Notifications）**：注册安防警戒（高优先级、声音/振动）与系统运维两大独立通知渠道，实时捕获摄像头 AI 目标检测（人形/车辆/宠物）与存储配额告警；提供 5 秒防刷保护，点击通知直达对应摄像头并自动定位录像回放。

### v1.10.7 (versionCode 136) — 凭据滑动续签与流媒体自愈加固 (2026-09)
- **凭据滑动续签**：对接服务端 `POST /api/v1/auth/refresh` 端点，`TokenManager` 新增 `refreshViaToken` 机制并支持 5 秒防抖互斥锁；后台自动续订优先采用老 Token 无缝滑动续期，免除频繁调用设备绑定的网络与鉴权开销。
- **直播弱网自愈与退避重试**：`CameraDetailActivity` 针对 WebRTC 及 ExoPlayer 直播断流增加指数退避自愈机制（最多 3 次，间隔 2s / 4s / 6s），断流时界面提供点触重试交互；播放就绪或恢复连接时自动复位重试计数器。
- **录像切片断流自愈**：`RecordingsDialog` 录像回放增加跨分段与弱网抖动自愈重试逻辑（最多 2 次），显著提升录像片段切换时的播放平滑度。
- **存储配额告警主动同步**：`DashboardFragment` 在冷启动与下拉刷新拉取近期系统日志时，主动嗅探是否存在 `system.recordings_size` 存储配额报警，避免未收到实时 WebSocket 广播时开屏漏显告警横幅。

### v1.10.6 (versionCode 135) — 用户角色推送分流与发包自动化 (2026-09)
- **更新推送分流**：普通用户仅接收经过官方 Keystore 签名的 Release 版本；Admin 用户可同时接收 Debug 与 Release 版本的最新更新。
- **Debug 标识感知**：设置页面对于 Admin 用户拉取到的 Debug 版本，在版本号旁醒目展示 `(Debug)` 标识。
- **发包脚本自动化**：`push-apk.ps1` 默认发包 Flavor 修改为 `release`，支持未构建时自动触发 `assembleRelease`；新增 `-Bump` / `-NewVersion` 支持一键自动递增版本号并同步发包。

### v1.10.4 (versionCode 133) — 签名与发布链路增强 (2026-09)

#### 性能与冷启动优化
- **开屏协程无阻预加载**：彻底移除 `SplashActivity` 中的 `runBlocking` 与 `postDelayed`，改用 `lifecycleScope.launch` 纯协程并行调度（保证品牌动画 900ms 与后台预加载 2000ms 熔断并存），开屏动画主线程 0 冻结帧。
- **冷启动局域网路径持久化**：`BaseUrlResolver` 新增 `KEY_LAST_RESOLVED_URL` 本地持久化，冷启动在家庭 Wi-Fi 下直连 NAS（~10ms），彻底消除启动初始阶段盲目向远程隧道发请求的延迟。
- **首屏网络状态全量预取**：开屏预加载阶段并行拉取 `network.status` 写入 `CacheManager`，首页 Dashboard 网络质量卡片首帧秒画。
- **主界面 Tab 页面按需懒加载**：`MainActivity` 废除 5 个 Fragment 在冷启动瞬间强行全量初始化的反模式，改为首次导航时延迟挂载，直接削减冷启动时多余的日志 WebSocket 长连接和全量用户列表网络并发。
- **Camera 列表 Compose 树复用**：`CameraViewHolder` 在 `init` 中单次挂载 Compose 树，通过 `currentCamera` 状态驱动局部刷新，消除列表滚动 GC 停顿与掉帧；增加 `lastAnimatedPosition` 优化入场动画防抖。

#### 网络与连接稳定性加固
- **WebSocket 自动重连锁死修复**：修复 `disconnect()` 导致 `shouldReconnect` 永久为 false 的严重缺陷，确保网络重连恢复正常，并在连接关闭时安全置空引用。
- **局域网与国产 ROM 离线误拦截优化**：`NetworkMonitor` 放宽强依赖 `NET_CAPABILITY_VALIDATED` 的策略，在家庭局域网（有 Wi-Fi/以太网连接）但无公网 Internet 或 Google 探测受阻时，依然允许内网请求。
- **Token 换票并发风暴锁**：`TokenManager` 加并发互斥锁与 5s 双重检查缓存，杜绝并发 401 触发多次重复绑定请求。
- **天气接口统一**：天气接口统一纳入 Retrofit 声明与 Repository 协程调用，消除裸 OkHttp 阻塞代码。

### v1.10.2 — 新用户密码认证模式 (2026-09)
- 支持手动密码创建新用户，替代纯随机 AccessKey 模式。
- 登录界面表单标签适配密码输入。

### v1.10.1 — ViewModel 数据流收拢 (2026-09)
- 重构 `DashboardViewModel` 与 `CamerasViewModel`，将 Dashboard 轮询数据与报警分页状态统一收拢到 ViewModel，避免横竖屏与生命周期导致数据丢失。

### v1.10.0 — 凭据管理器抽取与 401 响应重构 (2026-09)
- 独立提取 `TokenManager`，统一处理月度自动续订与 401 重新换票。
- 修复换票失败时响应体被过早消费导致下游崩溃的问题。

---

## License

Private / 家庭项目，未指定开源协议。
