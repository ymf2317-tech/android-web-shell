#!/usr/bin/env node
/**
 * 流水线：sites.json → 每个站点一个独立 APK
 *
 *   node scripts/build.mjs                  # 构建 sites.json 里全部站点（release）
 *   node scripts/build.mjs --site=dsh       # 只构建一个
 *   node scripts/build.mjs --debug          # 出 debug 包（免签名，仅供自测）
 *
 * 产物落在 dist/<id>-<versionName>.apk
 */
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const ROOT = path.resolve(import.meta.dirname, '..');
const TC = 'D:\\android-toolchain';
const GRADLE = path.join(TC, 'gradle', 'gradle-8.9', 'bin', 'gradle.bat');

const args = process.argv.slice(2);
const only = (args.find(a => a.startsWith('--site=')) || '').split('=')[1];
const debug = args.includes('--debug');
const task = debug ? 'assembleDebug' : 'assembleRelease';

const sitesFile = path.join(ROOT, 'sites.json');
const cfg = JSON.parse(fs.readFileSync(sitesFile, 'utf8'));
const sites = (cfg.sites || []).filter(s => !only || s.id === only);
if (!sites.length) {
  console.error(`[build] sites.json 里没有匹配的站点${only ? `（--site=${only}）` : ''}`);
  process.exit(1);
}

const env = {
  ...process.env,
  JAVA_HOME: path.join(TC, 'jdk'),
  ANDROID_HOME: path.join(TC, 'sdk'),
  ANDROID_SDK_ROOT: path.join(TC, 'sdk'),
};

/**
 * 默认"甩系统浏览器"的白名单：这些是明确拒绝 WebView 的登录/风控域名。
 * 站点可以用 sites.json 的 externalHosts 覆盖它（覆盖，不是追加）。
 */
const DEFAULT_EXTERNAL_HOSTS = []; // 默认全部在壳内处理（内部登录）；要甩系统浏览器的站点自己写 externalHosts

/**
 * 默认"必须留在当前 WebView 里"的登录域名。
 * 为什么不能开二层：OAuth 回来时靠的是同一个 JS 环境（window.opener / 同窗口 redirect），
 * 另开一层页面登录成功了，站点本体也拿不到 session —— 这就是 antigravity「登录了但本体没登录」的原因。
 * 站点可用 sites.json 的 inlineHosts 覆盖（覆盖，不是追加）。
 *
 * 注意：**别往里放普通浏览站点**（github.com 曾经在这里，后果是 DSH 里点 GitHub 链接
 * 直接把 DSH 页面替换掉 —— 需要二层网页的站点必须把 popupLayer 打开）。
 */
const DEFAULT_INLINE_HOSTS = [
  'accounts.google.com',
  'accounts.youtube.com',
  'myaccount.google.com',
  'login.microsoftonline.com',
  'appleid.apple.com',
  'open.weixin.qq.com',
];

function writeSiteResources(site) {
  const resValues = path.join(ROOT, 'app', 'src', 'main', 'res', 'values');
  // url 可以留空：那就是"通用壳"，首启进设置页让用户自己填（site_host 也留空，运行时按用户填的地址算）
  const host = site.url ? new URL(site.url).host : '';
  // 白名单：命中就走系统浏览器（登录页 / 要装 App 的站）。默认兜住各家 OAuth。
  const external = (site.externalHosts || DEFAULT_EXTERNAL_HOSTS)
    .map(h => String(h).trim()).filter(Boolean);
  const externalXml = external
    .map(h => `    <item>${h.replace(/&/g, '&amp;')}</item>`).join('\n');
  const inline = (site.inlineHosts || DEFAULT_INLINE_HOSTS)
    .map(h => String(h).trim()).filter(Boolean);
  const inlineXml = inline
    .map(h => `    <item>${h.replace(/&/g, '&amp;')}</item>`).join('\n');
  // popupLayer=true：允许开新窗口，但 onCreateWindow 把目标地址接住后交给 route()
  //   → 站外链接走二层页面，绝不替换当前页（DSH 这类不需要站内登录的站点用）。
  // false（默认）：setSupportMultipleWindows(false)，window.open 也塞进当前页 ——
  //   这是"登录必须原地完成"的站点（如 antigravity）要的。
  const popupLayer = site.popupLayer === true;
  const xml = `<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- 由 scripts/build.mjs 生成，请勿手改 -->
    <string name="site_url" translatable="false">${site.url.replace(/&/g, '&amp;')}</string>
    <string name="site_host" translatable="false">${host}</string>
    <bool name="site_popup_layer">${popupLayer}</bool>
    <string-array name="site_external_hosts" translatable="false">
${externalXml}
    </string-array>
    <string-array name="site_inline_hosts" translatable="false">
${inlineXml}
    </string-array>
</resources>
`;
  fs.writeFileSync(path.join(resValues, 'site.xml'), xml, 'utf8');

  const stringsPath = path.join(resValues, 'strings.xml');
  let strings = fs.readFileSync(stringsPath, 'utf8');
  strings = strings.replace(/<string name="app_name">[^<]*<\/string>/,
    `<string name="app_name">${site.appName.replace(/&/g, '&amp;')}</string>`);
  fs.writeFileSync(stringsPath, strings, 'utf8');

  // 长按桌面图标的「设置」入口：targetPackage 必须按站点包名生成
  const xmlDir = path.join(ROOT, 'app', 'src', 'main', 'res', 'xml');
  fs.mkdirSync(xmlDir, { recursive: true });
  fs.writeFileSync(path.join(xmlDir, 'shortcuts.xml'), `<?xml version="1.0" encoding="utf-8"?>
<shortcuts xmlns:android="http://schemas.android.com/apk/res/android">
    <shortcut
        android:shortcutId="settings"
        android:enabled="true"
        android:icon="@mipmap/ic_launcher"
        android:shortcutShortLabel="@string/shortcut_settings">
        <intent
            android:action="io.dsh.shell.action.SETTINGS"
            android:targetPackage="${site.packageName}"
            android:targetClass="io.dsh.shell.SettingsActivity" />
    </shortcut>
</shortcuts>
`, 'utf8');

  const mipmapDir = path.join(ROOT, 'app', 'src', 'main', 'res', 'mipmap-xxxhdpi');  fs.mkdirSync(mipmapDir, { recursive: true });
  const iconSrc = path.resolve(ROOT, site.icon || 'icons/dsh.png');
  if (fs.existsSync(iconSrc)) {
    fs.copyFileSync(iconSrc, path.join(mipmapDir, 'ic_launcher.png'));
  } else {
    console.warn(`[build] 图标不存在，沿用上一张：${iconSrc}`);
  }
}

function runGradle(site) {
  const argv = [
    task,
    '--no-daemon',
    '-q',
    `-PsiteAppId=${site.packageName}`,
    `-PsiteVersionName=${site.versionName || '1.0'}`,
    `-PsiteVersionCode=${site.versionCode || 1}`,
  ];
  console.log(`[build] gradle ${argv.join(' ')}`);
  const r = spawnSync(GRADLE, argv, { cwd: ROOT, env, stdio: 'inherit', shell: true });
  if (r.status !== 0) {
    throw new Error(`gradle 退出码 ${r.status}`);
  }
}

function collect(site) {
  const variant = debug ? 'debug' : 'release';
  const outDir = path.join(ROOT, 'app', 'build', 'outputs', 'apk', variant);
  if (!fs.existsSync(outDir)) {
    throw new Error(`找不到产物目录：${outDir}`);
  }
  const apk = fs.readdirSync(outDir).find(f => f.endsWith('.apk'));
  if (!apk) {
    throw new Error(`产物目录里没有 .apk：${outDir}`);
  }
  const distDir = path.join(ROOT, 'dist');
  fs.mkdirSync(distDir, { recursive: true });
  const target = path.join(distDir, `${site.id}-${site.versionName || '1.0'}${debug ? '-debug' : ''}.apk`);
  fs.copyFileSync(path.join(outDir, apk), target);
  const size = (fs.statSync(target).size / 1048576).toFixed(2);
  console.log(`[build] OK  ${target}  (${size} MB)`);
  return target;
}

let failed = 0;
for (const site of sites) {
  try {
    console.log(`\n===== ${site.id} : ${site.appName} → ${site.url} =====`);
    writeSiteResources(site);
    runGradle(site);
    collect(site);
  } catch (e) {
    failed++;
    console.error(`[build] ${site.id} 失败：${e.message}`);
  }
}
console.log(failed ? `\n[build] 完成，${failed} 个失败` : '\n[build] 全部成功');
process.exit(failed ? 1 : 0);
