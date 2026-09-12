# Home Datacenter Android 客户端历史更新日志归档 (v1.6.7 ~ v1.9.x)

本文件归档了 v1.10 之前的全部历史版本演进与更新记录。当前及最新版本变更请参阅 [README.md](../README.md#更新日志)。

---

## 目录

- [v1.9.x 系列 (v1.9.0 ~ v1.9.1)](#v19x-系列)
- [v1.8.x 系列 (v1.8.15 ~ v1.8.48)](#v18x-系列)
- [v1.7.x 系列 (v1.7.14 ~ v1.7.26)](#v17x-系列)
- [v1.6.x 系列 (v1.6.7 ~ v1.6.36)](#v16x-系列)

---

## v1.9.x 系列

### v1.9.1 — 预取生命周期与清理 (2026-08)
- 移除死预取键，在 Splash 退出时取消预取 scope，防止生命周期泄漏。
- 优化数据仓库与本地缓存契约。

### v1.9.0 — 框架与安全加固 (2026-08)
- 框架与网络安全性深度加固。
- 完善 Token 刷新与身份凭据重置流。

---

## v1.8.x 系列

### v1.8.48 — 维护与流控优化 (2026-08)
- 优化后台预热与摄像头并发网络连接数。

### v1.8.45 — 生命周期感知的 PrefetchManager (2026-08)
- `PrefetchManager` 绑定 `lifecycleScope`，在 Fragment 销毁时正确传播并抛出 `CancellationException`。
- `DashboardFragment` 移除冗余的 `cameras.list` 预取（由 SplashActivity 统一负责）。
- `CamerasFragment` 的 `cameras.ice` 预取接入 `lifecycleScope`。

### v1.8.36 — 录像配额告警与 Warning 级别支持 (2026-08)
- 接入服务端 `warning` 级别事件（`SystemLogLevel.WARNING`，琥珀色）。
- Dashboard 新增录像配额告警专用横幅（`system.recordings_size`），针对管理员精准提示配额状态与自动清理策略。

### v1.8.15 — TokenRefreshInterceptor 与无缝静默刷新 (2026-08)
- 新增 OkHttp `TokenRefreshInterceptor`，处理 401 token version mismatch 并使用 accessKey 重新换取 JWT。
- 引入每月静默续订机制。

---

## v1.7.x 系列

### v1.7.26 — 核查缓存修复 + 审计日志大幅拓展 (2026-08-12)

#### 核查后刷新回现 bug 修复
- 修复核查日志后下拉刷新时被核查日志又出现在"待处理日志"栏的 bug。
- 根因：核查成功后未清除 `CacheManager` 中的 `logs.page.*` 分页缓存，刷新时读到旧 critical 级别数据。
- 修复：核查成功后调用 `CacheManager.clear("logs.page.")` 清除所有日志分页缓存。

#### 审计日志大幅拓展（配合服务端 v1.8.22）
- **摄像头管理事件**：新增摄像头注册、更新编码/音频/录制计划的审计日志。
- **自动化规则事件**：新增规则触发、创建/更新/删除的审计日志。
- **设备管理事件**：新增设备硬删除、Token 轮换的审计日志。
- **运动检测报警**：摄像头检测到运动时记录 info 级别日志。
- **修复 dead topic**：`camera.status_changed` 事件之前已被订阅但从未发布，现在 health.go 在状态变更时补发。

### v1.7.25 — 日志核查降级 + 下拉刷新修复 + 更新流程优化 (2026-08-12)

#### 日志核查改为降级而非删除
- 核查按钮不再删除日志，改为调用 `PATCH /api/v1/system/logs/:id` 将日志级别从 `critical` 降级为 `normal`。
- 日志从"待处理日志"栏移除，但在"所有日志"栏继续保留，完整审计轨迹不丢失。
- 持久化到服务端，刷新后仍然有效。

#### 下拉刷新持续转圈 bug 修复
- 修复缓存命中路径（`loadNextPage` 中的 cache-hit early return）未调用 `swipeRefresh.isRefreshing = false` 导致下拉刷新永久转圈的问题。

#### 更新检查流程优化
- 更新检查从 `HomeCenterApp.onCreate` 移至 `SplashActivity` 预加载阶段，与仪表盘预取并行执行。
- APK 下载断点续传增强：失败后自动重试 3 次（立即 → 5s → 15s），每次从 `.part` 文件断点继续，中断后自动重连。

#### 用户列表调整
- 用户 tab 元信息行用"用户 ID"替换"设备数"显示。

### v1.7.24 — 日志核查修复 + 审计日志扩展 (2026-08-12)

#### 日志核查按钮修复
- 修复"核查"按钮无效 bug：原来仅在内存中标记已核查，刷新后失效；现在点击后调用 `DELETE /api/v1/system/logs/:id` 从服务端删除日志条目，并从列表实时移除。
- 新增 `pendingDeleteIds` 防重复点击机制：跟踪删除中的日志 ID，避免重复 API 调用。
- `ServiceLogAdapter` 新增 `onVerifyDelete` 回调接口，`ServiceLogsFragment.verifyAndDeleteLog` 负责调用 API 并更新 UI。

#### 审计日志扩展（配合服务端 v1.8.20）
- 用户登录 / 登出事件重新纳入日志记录。
- 新增用户创建 / 更新 / 删除事件记录（管理员操作审计）。
- 新增摄像头删除事件记录。
- 日志消息使用中文人类可读格式（如"管理员 admin 创建用户 alice"、"用户 admin 登录（设备 我的手机）"）。

### v1.7.23 — 对话框液态玻璃风格 (2026-08-12)

#### 液态玻璃对话框
- 新增 `bg_dialog_glass.xml`：26dp 圆角磨砂玻璃对话框背景（暖色边框 + 顶部折射高光 + 柔和阴影），颜色通过 `@color/glass_*` 自动适配暗色模式。
- 新增 `dialog_glass_in` / `dialog_glass_out` 动画：对话框淡入 + 轻微缩放进入 / 溶解退出，替代生硬弹出。
- `themes.xml` 新增 `GlassDialogAnimation` 窗口动画样式，明 / 暗主题均接入 `android:windowAnimationStyle`。
- 全部对话框应用玻璃风格：报警快照、报警列表、录像回放、注册设备、更新提示。
- 列表项（报警 / 设备 / 用户）与卡片背景同步微调，与液态玻璃暖色主题一致。

### v1.7.22 — APK 下载断点续传 (2026-08-12)

#### 断点续传
- `ApkInstaller.downloadOnly` 改用 `.part` 文件 + HTTP `Range` 请求实现断点续传。
- 弱网下载失败后，下次重试从已下载位置继续，而非从头开始。
- `HomeCenterApi.downloadLatestApk` 新增可选 `Range` 头参数，返回 `Response<ResponseBody>` 以区分 200（完整下载）/ 206（续传）。
- 下载完成后校验文件大小，`rename .part → 最终文件名`（原子操作）。
- 处理 416 Range Not Satisfiable：删除过期 `.part` 文件，下次完整重下。
- 进度回调正确反映续传起始百分比（如从 60% 开始继续）。
- 服务端无需修改（Go `http.ServeFile` 已原生支持 Range 请求）。

### v1.7.21 — 预加载并行化与开屏时间利用 (2026-08-11)

#### WebRTC + HLS/MP4 并行预 prepare
- `CameraDetailActivity` 新增 `fallbackPlayer` 字段和 `prepareFallbackPlayer` 方法。
- 启动 WebRTC 协商时同时创建 ExoPlayer 并 `prepare` HLS/MP4 source（`playWhenReady=false`）。
- WebRTC `onConnected`：释放 `fallbackPlayer`（成功无需 fallback）。
- WebRTC `onError`：将 `fallbackPlayer` 提升为主 player 并立即播放，省去构建+prepare 延迟。
- fallback 切换延迟从 500ms-2s 降至 ~100-300ms。
- 生命周期管理：`releaseExoPlayerOnly` 同时释放 `fallbackPlayer`，无 MediaCodec 泄漏。

#### 开屏期间预取首屏数据
- `SplashActivity` 已登录路径并行预取 `system.status` / `weather` / `alerts` / `cameras.list`。
- 数据写入 `CacheManager`，key 与 `DashboardFragment` 实际读取一致。
- `routeToNext` 等待条件改为 `max(900ms 动画, +1100ms 预取等待)`，总 2000ms 超时兜底。
- 未登录路径保持原 900ms 固定，不触发预取。

### v1.7.20 — 全链路预加载清理 + 冗余修复 (2026-08-11)

#### 死代码移除
- 移除 `takeWarmWebRtcClient()`（v1.6.10 遗留兼容 API，零调用点）。
- 移除 `BaseUrlResolver.switchTo()`（v1.6.26 遗留，`probeSync` 已改用 `applyResolved`）。
- 移除 `PrefetchManager.cancelPending()`（零调用点）。

#### 修复
- 接入 `tryAutoRefreshToken()` 到 `HomeCenterApp.onCreate`。
- 移除 `CameraDetailActivity.onCreate` 中的 `preheatCamera` 冗余调用。
- 给 `prefetchIceConfig` 加 `AtomicBoolean` 锁，防止首次启动多入口并发 2 次 GET。

### v1.7.19 — 网络探测快速路径先行 + 开屏动画 (2026-08-11)

#### 性能优化
- **网络探测快速路径先行**：LAN/IPv6 探测完成且存活即立即切换，取消仍在等待的 Tunnel 探测，不再为等 Tunnel（~1.4s）而延迟切换。
- 典型场景提速：家庭网络 ~1.4s → ~50ms；蜂窝 IPv6 ~1.4s → ~200ms。
- 仅当两条直连路径（LAN + IPv6）都不可用时，才等待 Tunnel 作为兜底。

#### 重构
- 移除 `/api/v1/network/ipv6` 冗余调用：`IPV6_DIRECT_URL` 已使用 DDNS 域名（`nas.feiyemomo.top`），AAAA 记录由 DDNS 提供商自动跟踪前缀轮换，无需再调后端接口验证。
- 删除 `fetchDynamicIpv6Url` 函数、`dynamicIpv6Url` 变量、`tokenProvider` 注入及相关后台刷新线程。

#### UI
- 新增开屏动画（`SplashActivity`）：品牌入场动画，根据登录状态路由到 `MainActivity` 或 `LoginActivity`。
- 冷启动背景改为暖色渐变 + 居中 logo，消除渲染前的黑/白闪屏。
- Tab 切换动画由淡入淡出改为滑动过渡。
- 摄像头 tab 新增"全部报警"分区。

### v1.7.17 — 主题切换 CancellationException 修复 (2026-08-02)
- 修复 Activity 重建时取消所有 Fragment `lifecycleScope` 协程导致的崩溃。
- ViewBinding 空指针保护（`onDestroyView` 后访问已销毁 binding 检查）。
- Fragment 重复添加防护（仅在 `savedInstanceState == null` 时添加）。

### v1.7.15 — 主题切换 Gradient 角度修复 (2026-08-02)
- 修复 5 个 drawable 文件中 `angle="-90"` 导致的崩溃（改为 `270`）。
- 补全暗色主题缺失的 Material3 属性。

### v1.7.14 — 液态玻璃暖色风格升级 (2026-08-02)
- 主色调升级为暖琥珀色，引入软阴影与顶部高光玻璃拟态。

---

## v1.6.x 系列

### v1.6.36 — 服务日志系统 + 摄像头预热 (2026-07-31)
- 新增服务日志 Tab（`SystemLog` 模型，系统事件与摄像头上下线流）。
- 摄像头当前状态显示与心跳事件防重。
- ICE 配置预取提前至 `HomeCenterApp.onCreate`。

### v1.6.33 — DDNS 域名统一识别 (2026-07-30)
- 域名解析跟踪 ISP IPv6 前缀轮换。

### v1.6.30 — Android 网络策略同步 (2026-07-30)
- 优化 BaseUrlResolver 回退地址与首次网络状态强制刷新。

### v1.6.29 — 延迟显示修复 (2026-07-22)
- Dashboard 网络质量卡片真实 API RTT 动态回写，连接池 Keep-Alive 延长。

### v1.6.28 — IPv6 直连延迟优化 (2026-07-22)
- OkHttp 显式连接池配置，TCP 连接预热（HEAD 探活）。

### v1.6.27 — 动态 IPv6 地址获取 (2026-07-22)
- 支持 `/api/v1/network/ipv6` 动态获取 NAS IPv6 地址。

### v1.6.24 — 全路径 WebRTC 尝试 + HLS 降级提示 (2026-07-21)
- 解除直连限制，全网络路径优先尝试 WebRTC；HLS 状态下给出延迟警告。

### v1.6.7 — Chip 合并 + 进度条并集 (2026-07-19)
- 录像时间线进度条相邻同级事件合并，提升渲染效率。
