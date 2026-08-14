# CI Secrets Setup — GitHub Actions

`home-datacenter-app` 的 CI 需要 4 个 [Actions Secrets](https://github.com/feiyemomo/home-datacenter-app/settings/secrets/actions) 来在构建机上重建正式 release 签名（keystore 与凭据绝不允许入库，因此只在 CI 的临时 runner 里从 Secret 重建）。

> 这些值与 `keystore.properties` 里 `RELEASE_*` 对应，与本地 `app/keystore/home-release.jks` 是同一把 key。

## 需要的 4 个 Secret

| Secret 名 | 值 | 示例 |
|---|---|---|
| `RELEASE_KEYSTORE_B64` | `home-release.jks` 的 base64 编码（见下） | `MIIK0AIBAzCCCno...` |
| `RELEASE_KEY_ALIAS` | 别名 | `home-release` |
| `RELEASE_STORE_PASSWORD` | store 密码 | 与本地 `keystore.properties` 一致 |
| `RELEASE_KEY_PASSWORD` | key 密码 | 与本地 `keystore.properties` 一致 |

## 生成 `RELEASE_KEYSTORE_B64`

在**本机**（有 keystore 的机器）执行任一种：

PowerShell：
```powershell
$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes("D:\Projects\Android\app\keystore\home-release.jks"))
Set-Content -Path "$env:TEMP\release-keystore-b64.txt" -Value $b64 -NoNewline
# 用记事本打开 $env:TEMP\release-keystore-b64.txt，全选复制
```

或 cmd（Windows 自带 certutil）：
```cmd
certutil -encode app\keystore\home-release.jks release-keystore-b64.txt
rem 注意：certutil 输出带 BEGIN/END 行，需删掉首尾那两行再复制正文
```

把整串 base64（无换行、无 `-----BEGIN CERTIFICATE-----` 包装）粘贴到 `RELEASE_KEYSTORE_B64`。

## 配置步骤

1. 打开仓库 [Settings → Secrets and variables → Actions](https://github.com/feiyemomo/home-datacenter-app/settings/secrets/actions)。
2. **New repository secret**，依次添加上面 4 个。
3. 完成后，测试触发：
   - 手动：仓库 **Actions** 页 → 选择 `Build & Test` → **Run workflow**（分支选 `main`）。
   - 或打个 tag：`git tag v1.8.44 && git push origin v1.8.44`。
4. 在 Actions 运行页的 **release** job 里下载 `app-release-apk` artifact，就是正式签名的 release APK。

## 安全说明

- `home-release.jks`、`keystore.properties` 已被 `.gitignore` 排除，不会入库。
- CI 只在临时 runner 里短暂重建 keystore，job 结束即销毁。
- 改过 keystore 密码 / 重新生成 keystore 后，需同步更新这里 4 个 Secret **和** 本地 `keystore.properties`，否则 CI 构建出的 APK 签名与本地不一致（无法覆盖升级）。
- 若 `RELEASE_KEYSTORE_B64` 缺失或密码配错，`assembleRelease` 会**直接失败**而非产出未签名 APK（这是有意设计，防止静默发布坏包）。