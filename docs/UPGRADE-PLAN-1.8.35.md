# Android 升级记录：v1.8.24 → v1.8.36（原规划 1.8.35）

> 目标：把 App 的后端对齐到 v1.8.36，新增**录像配额告警**的专用展示。
> 本文原为实施前规划文档（v1.8.35），现已按最终方案实施完成（v1.8.36 / versionCode 122）。
> **关键决定**：经确认，配额告警横幅**仅对 admin 用户可见**（非 admin 收不到也不显示）。

---

## 1. 现状盘点（已核实）

App 已经具备的实时与告警基础设施（`DashboardFragment` / `HomeCenterWebSocket`）：

| 能力 | 现状 | 说明 |
|---|---|---|
| WebSocket 实时通道 | ✅ 已有 | 订阅 `device` `camera` `camera.motion` `system.log` |
| 实时检测横幅 | ✅ 已有 | `camera.motion` 事件 → `showLiveDetection()` 顶部横幅（8s 自动消失） |
| 系统日志实时追加 | ✅ 已有 | `system.log` 事件 → 最近日志列表实时插入（`RecentLogAdapter`） |
| 录像配额告警接收 | ⚠️ 半有 | 配额告警作为 `system.recordings_size` 事件已能到达，但**混在普通日志里**，无专用 UX |
| 危险级别区分 | ⚠️ 缺失 | 前端只有 `critical/normal/info` 三色；后端 warn 落库为 `normal`，视觉上无法识别 |

**关键缺口**：录像配额告警缺少"一眼可辨"的呈现；后端缺少 `warning` 级别，导致告警和普通日志无法区分。

---

## 2. 需要后端支撑的改动（前置依赖）

### 2.1 增加 `warning` 级别

- `internal/model/model.go`：`SystemLog.Level` 增加 `LevelWarning = "warning"`。
- `internal/maintenance/recordings_size.go`：warn 档 `sev := model.LevelNormal` → `model.LevelWarning`。
- `internal/maintenance/disk.go`（及 backup/recording 相关监控）：warn 档同样改用 `LevelWarning`。
- 事件总线 severity 已用 `eventbus.SeverityWarn`，无需改动。

> 理由：目前配额 warn 和普通日志同为 `normal`（橙色 icon），用户无法区分"提醒"与"例行记录"。引入 `warning`（琥珀色）后，告警在最近日志和完整日志页都能一眼识别。

### 2.2 确认配额告警载荷（无需改动，供客户端使用）

`system.recordings_size` 的 `payload` 已含结构化字段：

```json
{ "path": "/media/frigate/recordings", "size_bytes": 7.7e8, "files": 1234, "level": "warn" }
```

Android 端可直接解析 `size_bytes` 渲染人类可读大小。

---

## 3. Android 改动清单

### 3.1 级别与渲染

| 文件 | 改动 |
|---|---|
| `data/model/SystemLog.kt` | `SystemLogLevel` 增加 `WARNING = "warning"` |
| `ui/dashboard/RecentLogAdapter.kt` | `colorForLevel` 增加 `WARNING -> R.color.warning`（琥珀色） |
| `ui/logs/ServiceLogAdapter.kt` | 同上，保持 dashboard 与 logs 页一致 |
| `res/values/colors.xml` | 新增 `warning` 色（如 `#FFB300`） |

### 3.2 录像配额告警专用横幅（Dashboard，仅 admin）

在 `DashboardFragment.handleWebSocketMessage` 的 `system.log` 分支中识别 `event_type == "system.recordings_size"`：

- **admin 门控**：`system.log` 分支入口先判断 `prefsManager.isAdmin != true` 则直接 `return`，因此 `handleQuotaAlert` 只在 admin 用户上下文被调用；非 admin 用户**收不到也不显示**该横幅。
- 新增**专用告警横幅**（`quotaAlertBanner`，区别于普通日志行）：
  - 文案：使用后端事件的 `message`（如"录像配额已用尽 736.9 MiB（已自动缩短录像保留天数）"）。
  - 颜色：琥珀色（`R.color.warning`），三角告警 icon（`ic_alerts`）。
  - 点击：跳转日志页（`bottomNav` → `nav_logs`）。
- 恢复逻辑：收到 `event_type == "system.recordings_size"` 且 `level == "normal"`（回落恢复事件）时隐藏横幅。
- 后端在配额超限/恢复时分别发布 `level=warning` / `level=normal` 事件（v1.8.36 新增），前端据此切换横幅显隐。

### 3.3 MQTT 实时事件展示增强

- **检测事件**：现有 `camera.motion` 实时横幅已覆盖，无需改动。
- **系统级实时告警**：将配额/磁盘/录制/备份等 `system.*` 事件在 dashboard 的实时区域做**高亮呈现**（除了日志行，再在横幅区轮播/置顶显示），让运维类告警不被淹没在日志流中。
- （可选）在设置页新增"实时事件开关"，控制是否弹横幅，避免打扰。

### 3.4 版本号

`app/build.gradle.kts`：
- `versionCode` 121 → 122
- `versionName` "1.8.24" → "1.8.36"

---

## 4. 构建与发布

沿用项目既有 Android 发布流程：

1. 更新 `versionCode` / `versionName`。
2. 构建 debug APK（`./gradlew assembleDebug`，使用 `keystore/home-debug.jks`）。
3. 推送 APK 到 NAS `data/releases`（`push-apk.ps1`）。
4. 清理 `data/releases` 旧 APK（仅保留最新 5 个，见后端 v1.8.27 清理逻辑）。
5. 更新 README / ai-context 的 Android 版本号。

---

## 5. 验证清单（已通关）

- [x] 后端 `system.recordings_size` 配额告警 `level == "warning"`（NAS 实测 `id=4331 level=warning`）。
- [x] App 最近日志 / 日志页中 warning 级别显示琥珀色 icon。
- [x] 触发配额超限（临时降到 10M）：后端发布 `warning` 事件，admin 端实时弹出专用横幅。
- [x] 恢复配额后：发布 `level=normal` 恢复事件，横幅自动消失。
- [x] 非 admin 用户**不显示**配额横幅（`system.log` 分支 admin 门控）。
- [x] `camera.motion` 实时检测横幅不受影响。
- [x] 版本号在 App / 发布接口正确显示 1.8.36。

---

## 6. 分工与顺序（已完成）

1. 后端：`warning` 级别（2.1）→ 部署 NAS → 验证。
2. 后端：配额超限/恢复发布 `system.recordings_size` 事件（`emitQuotaEvent`）。
3. Android：级别渲染（3.1）→ 专用横幅 + admin 门控（3.2）。
4. 版本号 + 构建 + 推送（3.4 / 4）→ `app-debug-v1.8.36.apk` 已推送 NAS。
5. 回归验证（5）。

> ⚠️ 依赖说明：Android 3.2 的专用横幅依赖后端 2.1 提供的 `warning` 级别与配额事件的发布（v1.8.36）；两者均已落地。