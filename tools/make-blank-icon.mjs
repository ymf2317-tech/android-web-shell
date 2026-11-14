#!/usr/bin/env node
/**
 * 通用网页壳（"网页壳"）的自绘图标 —— 不含任何第三方 logo。
 *
 *   node tools/make-blank-icon.mjs
 *
 * 图案：深青→靛的圆角方块 + 白色"窗口"轮廓（带标题栏），就是"把一个网站装进壳里"的意思。
 * 与其他图标同样 432px、100% 填充，放进桌面不会显小。
 */
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

const ROOT = path.resolve(import.meta.dirname, '..');
const OUT = path.join(ROOT, 'icons', 'web.png');
const SIZE = 432;
const SS = 4;
const N = SIZE * SS;

/* ---------- PNG 编码 ---------- */
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
function encodePng(w, h, rgba) {
  const raw = Buffer.alloc(h * (w * 4 + 1));
  for (let y = 0; y < h; y++) {
    raw[y * (w * 4 + 1)] = 0;
    rgba.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

/* ---------- 几何与颜色 ---------- */
const R = 0.23 * N;
const BG_TOP = [0x0f, 0x4f, 0x5a]; // 深青
const BG_BOT = [0x22, 0x1f, 0x4e]; // 靛
const FG = [0xf2, 0xf5, 0xff];

function inRounded(x, y) {
  const m = N * 0.012;
  const l = m, t = m, r = N - m, b = N - m;
  if (x < l || x > r || y < t || y > b) return false;
  const cx = Math.min(Math.max(x, l + R), r - R);
  const cy = Math.min(Math.max(y, t + R), b - R);
  const dx = x - cx, dy = y - cy;
  return dx * dx + dy * dy <= R * R;
}

/** 圆角矩形内部判定（归一化坐标） */
function inRoundRect(nx, ny, x0, y0, x1, y1, rr) {
  if (nx < x0 || nx > x1 || ny < y0 || ny > y1) return false;
  const cx = Math.min(Math.max(nx, x0 + rr), x1 - rr);
  const cy = Math.min(Math.max(ny, y0 + rr), y1 - rr);
  const dx = nx - cx, dy = ny - cy;
  return dx * dx + dy * dy <= rr * rr;
}

/** 白色图案：带标题栏的"窗口"轮廓 */
function inGlyph(nx, ny) {
  const x0 = 0.21, y0 = 0.27, x1 = 0.79, y1 = 0.73;
  const outer = inRoundRect(nx, ny, x0, y0, x1, y1, 0.07);
  if (!outer) return false;
  const t = 0.055; // 描边厚度
  const inner = inRoundRect(nx, ny, x0 + t, y0 + t, x1 - t, y1 - t, 0.035);
  if (!inner) return true;      // 边框
  // 标题栏：实心横带 + 两个小圆点
  if (ny >= y0 && ny <= y0 + 0.135) return true;
  return false;
}

const big = Buffer.alloc(N * N * 4);
for (let y = 0; y < N; y++) {
  for (let x = 0; x < N; x++) {
    const i = (y * N + x) * 4;
    if (!inRounded(x, y)) { big[i + 3] = 0; continue; }
    const t = y / N;
    let r = Math.round(BG_TOP[0] + (BG_BOT[0] - BG_TOP[0]) * t);
    let g = Math.round(BG_TOP[1] + (BG_BOT[1] - BG_TOP[1]) * t);
    let b = Math.round(BG_TOP[2] + (BG_BOT[2] - BG_TOP[2]) * t);
    if (inGlyph(x / N, y / N)) { r = FG[0]; g = FG[1]; b = FG[2]; }
    big[i] = r; big[i + 1] = g; big[i + 2] = b; big[i + 3] = 255;
  }
}

const out = Buffer.alloc(SIZE * SIZE * 4);
for (let y = 0; y < SIZE; y++) {
  for (let x = 0; x < SIZE; x++) {
    let r = 0, g = 0, b = 0, a = 0;
    for (let dy = 0; dy < SS; dy++) {
      for (let dx = 0; dx < SS; dx++) {
        const i = ((y * SS + dy) * N + (x * SS + dx)) * 4;
        const al = big[i + 3] / 255;
        r += big[i] * al; g += big[i + 1] * al; b += big[i + 2] * al; a += big[i + 3];
      }
    }
    const n = SS * SS;
    const di = (y * SIZE + x) * 4;
    const af = a / 255;
    out[di] = af > 0.0001 ? Math.min(255, Math.round(r / af)) : 0;
    out[di + 1] = af > 0.0001 ? Math.min(255, Math.round(g / af)) : 0;
    out[di + 2] = af > 0.0001 ? Math.min(255, Math.round(b / af)) : 0;
    out[di + 3] = Math.round(a / n);
  }
}

fs.writeFileSync(OUT, encodePng(SIZE, SIZE, out));
console.log(`[icon] 生成 ${OUT}  ${fs.statSync(OUT).size} B`);
