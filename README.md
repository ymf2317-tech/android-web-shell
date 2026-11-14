# android-web-shell · 网页壳工厂

**把任意网页站点，打成一个极简安卓 App。** 一套模板 + 一份 `sites.json` = N 个独立 APK：
只调系统 WebView，真全屏（铺到摄像头挖孔区），没有地址栏、没有工具栏、没有广告位、不收集任何数据。

> 这不是浏览器，是**壳**：它只加载你指定的那一个站点。推理、账号、数据全在站点那边跑，壳本身不碰。

## 为什么会有这个东西

- 手机上把网页「添加到桌面」得到的是 PWA：安卓上 **挖孔区永远留一条白带**（Chromium 未修的 WebAPK bug），
  Chrome 的 edge-to-edge 也只铺到手势条。要真正铺满，只能自己写原生壳。
- 现成的壳（如 Hermit、Native Alpha）要么是破解版拿不到更新，要么把 `layoutInDisplayCutoutMode` 写成可选项、
  targetSdk 还停在 32，实测开了「全屏」也照样留白。
- 于是有了这套东西：`setDecorFitsSystemWindows(false)` + `hide(systemBars)` + `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`，
  另外把「文档预览」这件事交给系统 WebView 的版本去解决。

## 现成的壳（本仓库的构建产物）

| APK | 桌面名 | 说明 |
| --- | --- | --- |
| `web-*.apk` | 网页壳 | **通用壳**：地址留空，首次启动自动进设置页，填任意站点即可。对外分发用这个 |
| `antigravity-*.apk` | 反重力远程 | Google Antigravity 的 remote-control 页面。官方没有移动端 App，这个壳补上了：真全屏 + 登录就在本页面完成 |
| `dsh-*.apk` | DSH | DeepSeek Harness 的网页 GUI |
| `sillytavern-*.apk` | 酒馆 | SillyTavern |
| `alist-*.apk` / `trilium-*.apk` / `github-*.apk` | 网盘 / 笔记 / GitHub | 也是同一套模板编出来的，可按需取用 |

**两类包，别搞混**：

- **通用壳**（`web`）：`sites.json` 里 `url` 留空 → `site_host` 也留空 → 运行时用用户填的地址算站点 host。
  所以站内跳转、二层网页、外链白名单的判定逻辑一条都没改，只是"首页地址"这件事交给了用户。
- **自带地址的包**：`url` 写死，装完直接进站点，适合自己用。别人装也一样能改地址
  （长按桌面图标 → 快捷方式「设置」），只是首次不用填。

## 免责声明

- 非官方项目，与 Google、GitHub 及其他站点运营方**没有任何关联**，未获其授权或背书。
- 仓库里**不包含**任何第三方的商标图形：图标是代码画的，应用名用的是中文描述性叫法。
- 它只是一个 WebView 容器：你访问什么、登录什么，与本项目无关；本项目不采集、不上报任何数据。

## 自己加一个站点

```bash
# 1) 图标：抓站点 favicon 到 icons/raw/，渲成 432×432 透明 PNG
node tools/make-icons.mjs

# 2) sites.json 里复制一条，改 id / appName / packageName / url / icon / 版本号
# 3) 编它
node scripts/build.mjs --site=<id>       # 产物在 dist/
```

签名：仓库**不含** keystore。自己生成一个，写进 `keystore.properties`（已 gitignore）：

```bash
keytool -genkeypair -v -keystore keystore/shell.jks -alias shell \
  -keyalg RSA -keysize 2048 -validity 10000 -storepass <你的口令> -keypass <你的口令>
```

没有 keystore 也能编 `--debug` 包直接试。

> CI：仓库里的 `ci/build.yml` 是现成的 GitHub Actions 工作流（打 tag → 编译 → 传 release 附件）。
> 放到 `.github/workflows/` 即可启用 —— 但推送 workflow 文件要求 PAT 带 `workflow` scope，本仓库的自动化暂时手动跑。

---

# 工程细节


把任意自建网页做成极简安卓壳：只调系统 WebView，真全屏（铺到摄像头挖孔区），没有地址栏、没有按钮。

**一套模板 + 一份 `sites.json` = N 个独立 APK。** 不写分支、不做多套流水线，站点差异只在 JSON 里。

---

## 快速开始

```bash
cd /d/dsh-workspaces/dsh/android-shell
/d/node24/node.exe scripts/build.mjs                # 编全部站点
/d/node24/node.exe scripts/build.mjs --site=dsh     # 只编一个
/d/node24/node.exe scripts/build.mjs --debug        # 出免签调试包
```

产物在 `dist/<id>-<版本>.apk`，交付时拷到 `D:\dsh-workspaces\dsh\apk\`。
签名固定为 `keystore/shell.jks`（口令 `dshshell`），**同签名可覆盖安装**，不需要卸载。

工具链固定在 `D:\android-toolchain`（JDK 17 / SDK 35 / build-tools 35.0.0 / Gradle 8.9），
`build.mjs` 会自己设 `JAVA_HOME`、`ANDROID_HOME` 给 gradle，**不改系统环境变量**。

## sites.json

| 字段 | 说明 |
| --- | --- |
| `id` | 目录/产物名 |
| `appName` | 桌面显示名（可用中文） |
| `packageName` | 每站独立，`io.dsh.shell.<id>` |
| `versionName` / `versionCode` | 升级时 `versionCode` 必须递增 |
| `url` | 首页；可以带 `#fragment`，但别把一次性 token 写死进去 |
| `icon` | `icons/<id>.png`，432×432 透明 PNG |
| `externalHosts` | 可选。**默认空 = 全部在壳内处理，登录也在壳内 WebView 完成**；只有确实要甩系统浏览器的域名才往里写 |

默认白名单是**空数组**（`DEFAULT_EXTERNAL_HOSTS = []`）：早期版本把 Google / Microsoft / Apple / 微信授权域名甩给系统浏览器，实测 WebView 本身能登（Hermit 就是在内部登的），所以现在改成壳内登录 —— 关键是 `WebTuning.normalizeUserAgent()` 把 UA 里的 `; wv` 和 `Version/4.0` 抹掉，否则登录页会判定"浏览器不安全"。

## 运行期路由（`MainActivity.route`，统一模板、无站点分支）

顺序是「站点域名 → 登录域名 → 显式外链 → 其它」：

1. **站点自己的域名** 与 **登录域名（`inlineHosts`）→ 原地加载（返回 false）**。
   登录绝不能开二层、也不能开新窗口：OAuth 回跳靠的是同一个 JS 环境（同窗口 redirect / `window.opener`），
   在另一层登录成功了，站点本体照样没有 session —— antigravity「登录了但应用本体没登录」就是这么来的。

1. **站点自己的域名** → 当前 WebView 原地跳。DSH 这类 SPA 的内部路由不能开新层，否则返回栈会乱。
2. **命中站点自己写的 `externalHosts`** → `ACTION_VIEW` 丢给系统浏览器（默认不会命中，留作逃生口：某个站真的拒绝 WebView 时才填）。
3. **其它 http(s)** → `InnerActivity` 二层网页：顶部 44dp 返回栏（`‹` 返回 / 标题 / `✕` 关闭），
   自带前进后退栈，返回键先 `web.canGoBack()`。这就是"在 DSH 里点链接开二层网页、不顶掉当前页"。

`window.open` / `target=_blank` 走 `followPopup()`，遵守同一套路由。但**这条路径要 `popupLayer` 才通**：

- `popupLayer: true`（DSH、通用壳）→ `setSupportMultipleWindows(true)`，`onCreateWindow` 把目标地址接住再交给 `route()`：
  站外进二层、本站与登录域名原地加载。**当前页永远不被替换**。
- 不写 / `false`（反重力远程这类靠同页 OAuth 回跳的站点）→ `setSupportMultipleWindows(false)`，
  `window.open` 一律塞回当前 WebView。此时**别把普通浏览站点写进 `inlineHosts`**：
  之前 `github.com` 在默认名单里，结果 DSH 里点 GitHub 直接把 DSH 顶掉，返回还要重新加载。

## 已实装能力

- **真全屏**：`setDecorFitsSystemWindows(false)` + `hide(statusBars|navigationBars)`
  + `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`，顶到摄像头挖孔区（PWA / Chrome 做不到）。
- **上传三选**：拍照（`MediaStore.ACTION_IMAGE_CAPTURE` + MediaStore insert 拿 content URI，
  免 FileProvider、免 CAMERA 权限）/ 照片和视频（API 33+ `ACTION_PICK_IMAGES`）/ 文件（`ACTION_GET_CONTENT`）。
- **下载**：`DownloadManager` + 自动带上 WebView 当前 `Cookie` 头。
- **登录态持久化**：`onPause` 时 `CookieManager.getInstance().flush()`，站内登录一次即可。
- **崩溃自曝**：未捕获异常直接把完整栈摊在屏幕上（不是 Toast），并写 `crash.txt`。
- **桌面图标长按 → 「设置」**：静态快捷方式，主界面零按钮。

## 踩过的坑

1. **Android 15+ 全屏时序**：`getInsetsController()` 只能在 `setContentView()` **之后**调。
   之前放在前面，`PhoneWindow.getInsetsController → DecorView == null` 直接 NPE，
   在 Android 17（API 37）上必崩——1.0.0 / 1.0.1 的"一点就闪退"就是这个。
2. **明文 http 白屏**：manifest 必须 `android:usesCleartextTraffic="true"`，
   否则 `http://100.92.67.72:8010`、`:5244` 这类地址打不开且没有任何提示。
3. **favicon 转 PNG**：`node tools/make-icons.mjs` 用 Chrome headless 渲 432×432 透明 PNG。
   注意有的站 `favicon.ico` 其实是 **SVG**（alist 就是），不改扩展名会渲成破图；
   本身是 PNG 的（antigravity）直接复制。
4. **APK 只有 40–120 KB 是正常的**：无 AndroidX、无图片资源、`resConfigs zh,en`。别再动 R8。
5. **主题**：平台没有 `Theme.DeviceDefault.DayNight.NoActionBar`，在 `values-v29/themes.xml`
   用 `Theme.DeviceDefault.DayNight` + `windowActionBar=false` + `windowNoTitle=true`。
6. **校验**：
   ```bash
   AAPT=/d/android-toolchain/sdk/build-tools/35.0.0/aapt2
   $AAPT dump badging dist/dsh-1.1.0.apk | head -1
   $AAPT dump xmltree dist/dsh-1.1.0.apk --file AndroidManifest.xml | grep -o InnerActivity
   ```
   编译输出里的 `Note: 使用或覆盖了已过时的 API` 只是提示；`[build] OK` 才算过。

## 加一个站点

```bash
cd /d/dsh-workspaces/dsh/android-shell
# 1. 抓 favicon 到 icons/raw/，然后
/d/node24/node.exe tools/make-icons.mjs
# 2. sites.json 里复制一条，改 id / appName / packageName / url / icon / versionName / versionCode
/d/node24/node.exe scripts/build.mjs --site=<id>
cp dist/<id>-*.apk /d/dsh-workspaces/dsh/apk/
# 3. aapt2 dump badging 校验包名与版本
```

## 与 Hermit 的关系

Hermit 是破解版、拿不到更新；壳 App 只调系统 WebView（与 Hermit 同一内核），
所以**文档预览能力取决于系统 WebView 版本**：需要 WebView 145+
（`Map.prototype.getOrInsertComputed`，Chrome 145 起、2026-02-10）。
实测做法：装 **Android System WebView Beta 159**（独立 APK，不覆盖稳定版），
再到「开发者选项 → WebView 实现」手动选中它并重启 App。

---

## 图标：统一"内容占比"，不然桌面上一看就大小不一

`tools/make-icons.mjs` 三步走：Chrome 渲源图 → 解码 PNG 裁掉透明留白 → 自己按目标填充率缩放居中贴进 432 画布。

起因：各家 favicon 自带的透明留白比例不同。dsh 那张是满画布（**100%**），其余五张实测只有
alist 75.7%、antigravity 72.1%、trilium/github 83.8%、minipulse 83.8% —— 装到桌面上就是"小一圈"。
现在统一 `TARGET_FILL = 1.0`，六张全部 100%。

复核命令（自己解码 PNG 算非透明包围盒，不依赖任何图像库）：

```bash
/d/node24/node.exe tools/icon-fill.mjs icons/*.png
```

两个坑：

- `.ico` 里最大帧只有 32/48px 的（GitHub、SillyTavern），放大到 432 必糊 → 换高清源：
  GitHub 用 `https://github.githubassets.com/favicons/favicon.svg`；
  SillyTavern 用站点自带的 `/img/logo.png`（330×330，注意是文字 logo，不是圆标）。
- SVG 首渲时 `<img>` 必须写死 `width:432px`，只给 `max-width` 的话 Chrome 会按 SVG 声明的 32px 出图。

## 当前站点（`sites.json`）

| id | 桌面名 | 包名 | 版本 | 地址 |
| --- | --- | --- | --- | --- |
| dsh | DSH | io.dsh.shell.dsh | 1.2.0 / 6 | tailnet 根 |
| sillytavern | 酒馆 | io.dsh.shell.sillytavern | 1.0.0 / 1 | `http://100.92.67.72:8010/` |
| alist | 网盘 | io.dsh.shell.alist | 1.1.0 / 2 | `http://100.92.67.72:5244/` |
| trilium | 笔记 | io.dsh.shell.trilium | 1.1.0 / 2 | `https://ymfd-tril.duckdns.org/` |
| antigravity | 反重力远程 | io.dsh.shell.antigravity | 1.2.1 / 4 | `https://antigravity.google.com/remote?...` |
| github | GitHub | io.dsh.shell.github | 1.1.1 / 3 | `https://github.com/` |
| web | 网页壳 | io.dsh.shell.web | 1.0.0 / 1 | **空**（首启由用户填） |

> `sillytavern` 之前叫 `minipulse`/「监控」，是**叫错了**：8010 是酒馆（SillyTavern），
> minipulse 在 61208。包名换了，旧的那只「监控」需要手动卸载。

## 变更记录

- **web-v1.0.0（通用壳）**：`sites.json` 的 `url` 允许留空 → 首启进设置页让用户填站点；`site_host` 改为运行时解析；`SettingsActivity` 空值拦截与首次提示；图标 `tools/make-blank-icon.mjs` 代码绘制。
- **v1.2.1（当前）**：登录域名改为**原地加载**（`DEFAULT_INLINE_HOSTS`：accounts.google.com / login.microsoftonline.com / appleid.apple.com / open.weixin.qq.com / github.com …），并关掉 `setSupportMultipleWindows`，让 `window.open` 也落在同一个 WebView —— 修「登录成功在二层、本体没登录」。
- v1.2.0：登录统一走壳内 WebView（`DEFAULT_EXTERNAL_HOSTS = []` + `WebTuning.normalizeUserAgent`），
  不再甩系统浏览器（dsh 也不例外）；图标全部归一化到 100% 填充；minipulse → sillytavern（酒馆）。
- v1.1.0：六站点流水线 + `InnerActivity` 二层网页 + 外链白名单 + 图标渲染脚本。
- v1.0.2：修 Android 17 上 `setContentView` 之前取 `WindowInsetsController` 的 NPE（真全屏落地）。
- v1.0：首个壳，上传三选（拍照 / 照片和视频 / 文件）。
