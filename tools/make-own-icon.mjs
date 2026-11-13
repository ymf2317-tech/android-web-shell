#!/usr/bin/env node
/**
 * 自己画的 App 图标（不含任何第三方 logo），给"反重力远程"用。
 *
 *   node tools/make-own-icon.mjs
 *
 * 图案：深蓝→紫的圆角方块 + 白色"上行箭头 + 地平线"（远程/上行接入的通用符号）。
 * 4 倍超采样后降采样，边缘自带抗锯齿；透明度从 0 铺到满画布，和 dsh 图标的 100% 填充一致。
 */
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

const ROOT = path.resolve(import.meta.dirname, '..');
const OUT = path.join(ROOT, 'icons', 'antigravity.png');
const SIZE = 432;
const SS = 4; // supersampling
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
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([
    Buffer.from('89504e470d0a1a0a', 'hex'),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

/* ---------- 几何与颜色 ---------- */
const R = 0.23 * N;              // 圆角半径
const BG_TOP = [0x18, 0x2A, 0x4E];  // 深蓝
const BG_BOT = [0x3A, 0x1B, 0x5C];  // 紫
const FG = [0xf2, 0xf5, 0xff];      // 近白

function inRounded(x, y) {
  const m = N * 0.012;                 // 极小的外边距，保持满画布观感
  const l = m, t = m, r = N - m, b = N - m;
  if (x < l || x > r || y < t || y > b) return false;
  const cx = Math.min(Math.max(x, l + R), r - R);
  const cy = Math.min(Math.max(y, t + R), b - R);
  const dx = x - cx, dy = y - cy;
  return dx * dx + dy * dy <= R * R;
}

/** 白色图案：上行箭头 + 地平线，按归一化坐标（0..1）描述。 */
function inGlyph(nx, ny) {
  // 箭头头部：三角形，(0.5, 0.28) 顶点，底边 y=0.60，半宽 0.17
  if (ny >= 0.28 && ny <= 0.60) {
    const halfW = 0.17 * (ny - 0.28) / (0.60 - 0.28);
    if (Math.abs(nx - 0.5) <= halfW) return true;
  }
  // 箭杆
  if (ny > 0.60 && ny <= 0.74 && Math.abs(nx - 0.5) <= 0.075) return true;
  // 地平线（两端圆头）
  if (ny >= 0.775 && ny <= 0.845) {
    const x0 = 0.36, x1 = 0.64, rr = (0.845 - 0.775) / 2, midY = (0.845 + 0.775) / 2;
    if (nx >= x0 + rr && nx <= x1 - rr) return true;
    const dx = nx < x0 + rr ? nx - (x0 + rr) : nx - (x1 - rr);
    const dy = ny - midY;
    if (dx * dx + dy * dy <= rr * rr) return true;
  }
  return false;
}

// 超采样绘制
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

// 盒式降采样（预乘 alpha，避免边缘发黑）
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
