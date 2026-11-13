#!/usr/bin/env node
/**
 * 量图标"内容占比"：解码 PNG（8bit RGBA/RGB，非隔行），算 alpha>8 的像素包围盒，
 * 输出 bbox 占画布的比例（max 边）。用来把各站点图标视觉大小对齐。
 *
 *   node tools/icon-fill.mjs icons/*.png
 */
import fs from 'node:fs';
import zlib from 'node:zlib';

function decodePng(buf) {
  if (buf.subarray(0, 8).toString('hex') !== '89504e470d0a1a0a') throw new Error('not png');
  let off = 8;
  let w = 0, h = 0, depth = 8, color = 6, interlace = 0;
  const idat = [];
  while (off < buf.length) {
    const len = buf.readUInt32BE(off);
    const type = buf.subarray(off + 4, off + 8).toString('latin1');
    const data = buf.subarray(off + 8, off + 8 + len);
    if (type === 'IHDR') {
      w = data.readUInt32BE(0);
      h = data.readUInt32BE(4);
      depth = data[8];
      color = data[9];
      interlace = data[12];
    } else if (type === 'IDAT') {
      idat.push(data);
    } else if (type === 'IEND') {
      break;
    }
    off += 12 + len;
  }
  if (depth !== 8) throw new Error(`unsupported bit depth ${depth}`);
  if (interlace) throw new Error('interlaced png unsupported');
  const channels = color === 6 ? 4 : color === 2 ? 3 : color === 0 ? 1 : color === 4 ? 2 : 0;
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
      switch (ft) {
        case 0: r = v; break;
        case 1: r = v + a; break;
        case 2: r = v + b; break;
        case 3: r = v + ((a + b) >> 1); break;
        case 4: {
          const p = a + b - c;
          const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
          r = v + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c);
          break;
        }
        default: throw new Error(`bad filter ${ft}`);
      }
      cur[x] = r & 0xff;
    }
  }
  return { w, h, channels, px: out };
}

for (const file of process.argv.slice(2)) {
  try {
    const { w, h, channels, px } = decodePng(fs.readFileSync(file));
    let minX = w, minY = h, maxX = -1, maxY = -1;
    for (let y = 0; y < h; y++) {
      for (let x = 0; x < w; x++) {
        const a = channels === 4 ? px[(y * w + x) * 4 + 3]
          : channels === 2 ? px[(y * w + x) * 2 + 1]
            : 255;
        if (a > 8) {
          if (x < minX) minX = x;
          if (x > maxX) maxX = x;
          if (y < minY) minY = y;
          if (y > maxY) maxY = y;
        }
      }
    }
    if (maxX < 0) {
      console.log(`${file}: 全透明（w=${w} h=${h}）`);
      continue;
    }
    const bw = maxX - minX + 1, bh = maxY - minY + 1;
    const fill = (Math.max(bw, bh) / Math.max(w, h) * 100).toFixed(1);
    console.log(`${file}: ${w}x${h} 内容 ${bw}x${bh} → 填充 ${fill}%  (bbox ${minX},${minY}-${maxX},${maxY})`);
  } catch (e) {
    console.log(`${file}: 读取失败 ${e.message}`);
  }
}
