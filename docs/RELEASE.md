# 发布编译好的 APK

本文说明怎么把编译产物发布到你的 GitHub 仓库。

**核心结论：不要把 APK 提交进 git 仓库，而是发 GitHub Release 并把它作为附件。**

---

## 为什么不直接提交 APK 到仓库

| 做法 | 问题 |
|---|---|
| `git add app-debug.apk` 后 commit | ❌ 每次改动都新增一份二进制，**仓库体积会永久膨胀**（git 历史无法瘦身）；clone 变慢；而且 APK 是可再生成的产物，不该进版本控制 |
| 发 **GitHub Release** 并上传 APK | ✅ 附件**不计入仓库体积**；有版本号、下载统计、release notes；用户一眼就能找到下载按钮 |
| 用 **GitHub Actions** 自动构建发布 | ✅ 最省事，打 tag 就自动出包（本仓库已配好） |

---

## 三种方式，按推荐顺序

### 方式一：打 tag，让 GitHub Actions 自动发布（推荐，已配好）

仓库里已有 `.github/workflows/release.yml`。你只需要：

```bash
git tag v1.0.0
git push origin v1.0.0
```

然后 GitHub Actions 会自动：
1. 装 JDK 17 + Android SDK 36
2. 用 Secrets 里的密钥签名
3. 编译 release APK
4. **创建一个 Release**，把 `HyperRedial-v1.0.0.apk` 挂上去，并自动生成 release notes

去 https://github.com/WiN5551111/HyperRedial/actions 看进度，
完成后在 https://github.com/WiN5551111/HyperRedial/releases 就能看到。

#### 先配好签名 Secrets（只需一次）

不配也能出包，但会是 debug 签名。配置方法：

**1. 先生成正式密钥**（本机跑一次）：
```powershell
cd E:\TEST\HyperRedial
powershell -ExecutionPolicy Bypass -File .\make-keystore.ps1
```
会生成 `release.jks` 和 `keystore.properties`（都已被 .gitignore 忽略，不会提交）。

**2. 把 keystore 转成 base64**：
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) | Set-Clipboard
```
（已复制到剪贴板）

**3. 在仓库里加 Secrets**：
打开 `https://github.com/WiN5551111/HyperRedial/settings/secrets/actions`
→ New repository secret，依次添加：

| Secret 名 | 值 |
|---|---|
| `KEYSTORE_BASE64` | 上一步剪贴板里的 base64 |
| `KEYSTORE_PASSWORD` | 你生成密钥时设的密码 |
| `KEY_ALIAS` | `hyperredial`（或你设的别名） |
| `KEY_PASSWORD` | 同 `KEYSTORE_PASSWORD` |

> ⚠️ **务必备份 `release.jks` 和密码**。丢了就永远无法发布"能覆盖安装旧版"的更新，
> 用户必须先卸载才能装新的。

---

### 方式二：手动创建 Release（不折腾 CI）

**1. 本机打正式签名包**
```powershell
cd E:\TEST\HyperRedial
powershell -ExecutionPolicy Bypass -File .\make-keystore.ps1   # 只需一次
.\gradlew.bat :app:assembleRelease '-PversionName=1.0.0' '-PversionCode=1'
```
产物：`app\build\outputs\apk\release\HyperRedial-v1.0.0.apk`

**2. 在网页上发 Release**
打开 https://github.com/WiN5551111/HyperRedial/releases/new

- **Choose a tag**: 输入 `v1.0.0` → 点 "Create new tag on publish"
- **Release title**: `HyperRedial v1.0.0`
- **Describe this release**: 写更新说明（可以直接抄下面「Release 说明模板」）
- **Attach binaries**: 把 `HyperRedial-v1.0.0.apk` 拖进去
- 点 **Publish release**

> 只有「Publish release」之后附件才会公开可下载；存成 draft 只有你自己看得到。

**3. 命令行等价做法**（如果你装了 `gh` CLI）
```bash
gh release create v1.0.0 \
  "app/build/outputs/apk/release/HyperRedial-v1.0.0.apk" \
  --title "HyperRedial v1.0.0" \
  --notes-file CHANGELOG.md
```

---

### 方式三：只上传 Actions artifact（不发 Release）

`workflow_dispatch` 手动触发时不会创建 Release，只在 Actions 页面留下 artifact
（保留 90 天）。适合自己测试用。

---

## Release 说明模板

```markdown
## HyperRedial v1.0.0

把 HyperOS 电话 App 的通话记录列表改成 iOS 点击逻辑的 LSPosed 模块。

| 操作 | 行为 |
|---|---|
| 点通话记录**整行** | 打开详情页（原本是直接拨号） |
| 点**右侧按钮** | 拨号 / 回拨（原本是打开详情） |
| **长按**条目 | 保留 MIUI 原生菜单 |
| 右侧按钮图标 | iOS 风格听筒 |
| 右侧按钮热区 | 已放大 |

### 安装
1. 下载下面的 APK
2. 安装后到 LSPosed 管理器启用，**作用域只勾「电话」**
3. 强制停止电话 App（或重启手机）

### 适用环境
- HyperOS 4.0 / Android 17 (SDK 37)
- 电话 App `com.android.contacts` 18.30.00.55
- LSPosed v2.2.1+

其它版本可能类名不同，装好后看 logcat 里的 `HyperRedial selfTest` 日志即可判断。

### 校验签名（可选）
```bash
apksigner verify --print-certs HyperRedial-v1.0.0.apk
# 应显示 CN=HyperRedial, OU=OpenSource, O=HyperRedial, C=CN
```
```

---

## 本机打包常用命令

```powershell
# debug 包（自用调试）
.\gradlew.bat :app:assembleDebug

# 正式签名包（发版用）
.\gradlew.bat :app:assembleRelease '-PversionName=1.0.0' '-PversionCode=1'

# 产物位置
#   debug:   app\build\outputs\apk\debug\app-debug.apk
#   release: app\build\outputs\apk\release\HyperRedial-v1.0.0.apk

# 验证签名
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\apksigner.bat" verify --print-certs `
    app\build\outputs\apk\release\HyperRedial-v1.0.0.apk
```

`versionCode` 必须**每次发版递增**，否则 Android 不允许覆盖安装。

---

## 常见问题

**Q: 报 `Task '.0.0' not found in root project`？**
A: PowerShell 把 `-PversionName=1.0.0` 按点号拆开了。**`-P` 参数必须加引号**：
```powershell
.\gradlew.bat :app:assembleRelease '-PversionName=1.0.0' '-PversionCode=1'
```
（cmd.exe 和 bash 没有这个问题，只有 PowerShell 会。）

**Q: 报 `keystore password was incorrect` / `BadPaddingException`？**
A: `-Force` 生成新密钥时会先删旧文件再建。如果旧文件还在，keytool 会尝试用
**新密码**去打开它，于是解密失败。重跑一次 `make-keystore.ps1 -Force` 即可
（现在的版本会先删后建）。

**Q: `[IO.File]::ReadAllBytes("release.jks")` 报找不到文件？**
A: .NET 静态方法用的是**进程工作目录**，不是你 `cd` 到的目录。传绝对路径：
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("E:\TEST\HyperRedial\release.jks")) | Set-Clipboard
```

**Q: 用户装不上 / 提示"应用未安装"？**
A: 多半是签名不一致（之前装过 debug 签名的版本）。让用户先卸载旧版再装。

**Q: Actions 构建失败说找不到 build-tools 36.0.0？**
A: workflow 里已经显式 `sdkmanager "build-tools;36.0.0"`。如果上游 SDK 仓库变了，
把 `app/build.gradle.kts` 里的 `buildToolsVersion` 和 workflow 里的版本一起改。

**Q: 不想让别人看到我的 keystore？**
A: 它本来就不在仓库里（`.gitignore` 已排除）。CI 用的是 Secrets，
日志里也会被 GitHub 自动打码。**永远不要**把 `release.jks` 或
`keystore.properties` commit 进去。
