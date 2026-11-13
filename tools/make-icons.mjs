#!/usr/bin/env node
/**
 * 站点图标流水线：favicon（ico / svg / png）→ 432x432 透明 PNG
 *
 *   node tools/make-icons.mjs            # 全部
 *   node tools/make-icons.mjs github     # 只做一个
 *
 * 为什么要"裁边 + 归一化"：各家 favicon 自带的透明留白不一样，
 * 直接按同一比例塞进画布，桌面上一看就有大有小（dsh 的图标是满画布 100%，
 * 其它曾只有 72%~84%）。这里的做法是：
 *   1. Chrome 把源图渲成 432 PNG
 *   2. 解码 PNG，找出非透明像素包围盒，裁掉留白
 *   3. 按目标填充率（默认 96%）重新渲一遍
 * 这样每个图标的内容都占画布同样的比例。
 */
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import zlib from 'node:zlib';

const ROOT = path.resolve(import.meta.dirname, '..');
const RAW = path.join(ROOT, 'icons', 'raw');
const OUT = path.join(ROOT, 'icons');
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const SIZE = 432;
const TARGET_FILL = 1.0; // 和 dsh 图标一样：内容占满画布

const items = {
  sillytavern: 'sillytavern.png', // SillyTavern 酒馆（8010）：/img/logo.png 330x330
  alist: 'alist.svg',
  trilium: 'trilium.ico',
  github: 'github.svg',
  antigravity: 'antigravity.ico',
};

/* ---------- PNG 编解码（只支持 8bit、非隔行、RGBA/RGB） ---------- */

function crc32(buf) {
  let c, crc = 0xffffffff;
  for (let n = 0; n < buf.length; n++) {
    c = (crc ^ buf[n]) & 0xff;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    crc = (crc >>> 8) ^ c;
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type, 'latin1'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}

function decodePng(buf) {
  if (buf.subarray(0, 8).toString('hex') !== '89504e470d0a1a0a') throw new Error('not png');
  let off = 8, w = 0, h = 0, depth = 8, color = 6, interlace = 0;
  const idat = [];
  while (off < buf.length) {
    const len = buf.readUInt32BE(off);
    const type = buf.subarray(off + 4, off + 8).toString('latin1');
    const data = buf.subarray(off + 8, off + 8 + len);
    if (type === 'IHDR') {
      w = data.readUInt32BE(0); h = data.readUInt32BE(4);
      depth = data[8]; color = data[9]; interlace = data[12];
    } else if (type === 'IDAT') idat.push(data);
    else if (type === 'IEND') break;
    off += 12 + len;
  }
  if (depth !== 8) throw new Error(`unsupported bit depth ${depth}`);
  if (interlace) throw new Error('interlaced png unsupported');
  const channels = color === 6 ? 4 : color === 2 ? 3 : 0;
  if (!channels) throw new Error(`unsupported color type ${color}`);
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = w * channels;
  const out = Buffer.alloc(h * stride);
  let pos = 0;
  for (let y = 0; y < h; y++) {
    const ft = raw[pos++];
    const line = raw.subarray(pos, pos + stride);
    pos += stride;
    const cur = out.subarray(y * stride, (y + 1) * stride);
    const prev = y > 0 ? out.subarray((y - 1) * stride, y * stride) : null;
    for (let x = 0; x < stride; x++) {
      const a = x >= channels ? cur[x - channels] : 0;
      const b = prev ? prev[x] : 0;
      const c = prev && x >= channels ? prev[x - channels] : 0;
      const v = line[x];
      let r;
      if (ft === 0) r = v;
      else if (ft === 1) r = v + a;
      else if (ft === 2) r = v + b;
      else if (ft === 3) r = v + ((a + b) >> 1);
      else if (ft === 4) {
        const p = a + b - c;
        const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        r = v + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c);
      } else throw new Error(`bad filter ${ft}`);
      cur[x] = r & 0xff;
    }
  }
  return { w, h, channels, px: out };
}

function encodePng(w, h, rgba) {
  const raw = Buffer.alloc(h * (w * 4 + 1));
  for (let y = 0; y < h; y++) {
    raw[y * (w * 4 + 1)] = 0; // filter: none
    rgba.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  return Buffer.concat([
    Buffer.from('89504e470d0a1a0a', 'hex'),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

/** 把 alpha>8 的包围盒裁出来，输出 RGBA。 */
function trimAlpha(png) {
  const { w, h, channels, px } = png;
  let minX = w, minY = h, maxX = -1, maxY = -1;
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const a = channels === 4 ? px[(y * w + x) * 4 + 3] : 255;
      if (a > 8) {
        if (x < minX) minX = x;
        if (x > maxX) maxX = x;
        if (y < minY) minY = y;
        if (y > maxY) maxY = y;
      }
    }
  }
  if (maxX < 0) throw new Error('图片全透明');
  const bw = maxX - minX + 1, bh = maxY - minY + 1;
  const out = Buffer.alloc(bw * bh * 4);
  for (let y = 0; y < bh; y++) {
    for (let x = 0; x < bw; x++) {
      const si = ((y + minY) * w + (x + minX)) * channels;
      const di = (y * bw + x) * 4;
      out[di] = px[si];
      out[di + 1] = px[si + 1];
      out[di + 2] = px[si + 2];
      out[di + 3] = channels === 4 ? px[si + 3] : 255;
    }
  }
  return { w: bw, h: bh, px: out };
}

/** 双线性缩放 + 居中贴进 canvas canvas 大小的透明画布（预乘 alpha，避免边缘发黑）。 */
function fitInto(src, maxEdge, canvas) {
  const scale = maxEdge / Math.max(src.w, src.h);
  const dw = Math.max(1, Math.round(src.w * scale));
  const dh = Math.max(1, Math.round(src.h * scale));
  const ox = Math.round((canvas - dw) / 2);
  const oy = Math.round((canvas - dh) / 2);
  const out = Buffer.alloc(canvas * canvas * 4);
  const s = src.px;
  for (let y = 0; y < dh; y++) {
    const sy = Math.min(src.h - 1, (y + 0.5) / scale - 0.5);
    const y0 = Math.max(0, Math.floor(sy));
    const y1 = Math.min(src.h - 1, y0 + 1);
    const fy = sy - y0;
    for (let x = 0; x < dw; x++) {
      const sx = Math.min(src.w - 1, (x + 0.5) / scale - 0.5);
      const x0 = Math.max(0, Math.floor(sx));
      const x1 = Math.min(src.w - 1, x0 + 1);
      const fx = sx - x0;
      // 预乘后插值
      let r = 0, g = 0, b = 0, a = 0;
      for (const [px, py, wgt] of [
        [x0, y0, (1 - fx) * (1 - fy)], [x1, y0, fx * (1 - fy)],
        [x0, y1, (1 - fx) * fy], [x1, y1, fx * fy],
      ]) {
        const i = (py * src.w + px) * 4;
        const al = s[i + 3] / 255;
        r += s[i] * al * wgt;
        g += s[i + 1] * al * wgt;
        b += s[i + 2] * al * wgt;
        a += s[i + 3] * wgt;
      }
      const di = ((y + oy) * canvas + (x + ox)) * 4;
      const af = a / 255;
      out[di] = af > 0.0001 ? Math.min(255, Math.round(r / af)) : 0;
      out[di + 1] = af > 0.0001 ? Math.min(255, Math.round(g / af)) : 0;
      out[di + 2] = af > 0.0001 ? Math.min(255, Math.round(b / af)) : 0;
      out[di + 3] = Math.min(255, Math.round(a));
    }
  }
  return out;
}

/* ---------- Chrome 渲染 ---------- */

function render(imgUrl, imgCss, out, tmpDir, tag) {
  const html = path.join(tmpDir, `${tag}.html`);
  fs.writeFileSync(html, `<!doctype html><meta charset="utf-8">
<style>html,body{margin:0;padding:0;background:transparent;width:${SIZE}px;height:${SIZE}px;overflow:hidden}
#c{position:absolute;inset:0;display:flex;align-items:center;justify-content:center}
img{${imgCss};display:block}</style>
<div id="c"><img src="${imgUrl}"></div>`, 'utf8');
  execFileSync(CHROME, [
    '--headless=new',
    '--disable-gpu',
    '--hide-scrollbars',
    '--force-device-scale-factor=1',
    '--default-background-color=00000000',
    `--user-data-dir=${path.join(tmpDir, `p-${tag}`)}`,
    `--window-size=${SIZE},${SIZE}`,
    `--screenshot=${out}`,
    html,
  ], { stdio: 'ignore', timeout: 60000 });
  if (!fs.existsSync(out) || fs.statSync(out).size < 500) throw new Error('Chrome 截图失败');
}

/* ---------- 主流程 ---------- */

const only = process.argv[2];
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'shell-icons-'));
let failed = 0;

for (const [id, file] of Object.entries(items)) {
  if (only && only !== id) continue;
  const src = path.join(RAW, file);
  if (!fs.existsSync(src)) {
    console.warn(`[icons] 缺文件，跳过：${src}`);
    continue;
  }
  try {
    const url = 'file:///' + src.replace(/\\/g, '/');
    // 1) 先整张渲出来（占满画布，保留源图自身留白）
    const step1 = path.join(tmp, `${id}-1.png`);
    render(url, `width:${SIZE}px;height:${SIZE}px;object-fit:contain`, step1, tmp, `${id}1`);
    // 2) 裁掉透明留白
    const trimmed = trimAlpha(decodePng(fs.readFileSync(step1)));
    const trimmedPath = path.join(tmp, `${id}-trim.png`);
    fs.writeFileSync(trimmedPath, encodePng(trimmed.w, trimmed.h, trimmed.px));
    // 3) 自己缩放居中贴进 432 画布（不依赖 Chrome 的排版，尺寸完全可预测）
    const out = path.join(OUT, `${id}.png`);
    const final = fitInto(trimmed, Math.round(SIZE * TARGET_FILL), SIZE);
    fs.writeFileSync(out, encodePng(SIZE, SIZE, final));
    console.log(`[icons] ${id}: ok  ${fs.statSync(out).size}B  (源内容 ${trimmed.w}x${trimmed.h} → 填充 ${Math.round(TARGET_FILL * 100)}%)`);
  } catch (e) {
    failed++;
    console.error(`[icons] ${id}: 失败 ${e.message}`);
  }
}

fs.rmSync(tmp, { recursive: true, force: true });
process.exit(failed ? 1 : 0);
