#!/usr/bin/env node
/**
 * 华为音乐导出的歌单（markdown 表格）→ J4 歌单 JSON
 *
 * 华为的歌曲 ID 在 App 里没有对应的音源实现（我们只接了网易云 + QQ），
 * 直接把 huawei:<id> 写进歌单只会得到一堆点了没反应的条目。所以这里按
 * 「歌名 + 歌手」在网易云 / QQ 上各搜一次，挑最像的那条，把它的平台 id
 * 写进 J4 歌单 —— 导入之后立刻就能播。
 *
 * 用法：node huawei_to_j4.js <输入.md> <输出.json>
 */
const fs = require('fs');
const http = require('http');
const https = require('https');

const BASE = process.env.J4_BASE || '';
const TOKEN = process.env.J4_TOKEN || '';
const CONCURRENCY = Number(process.env.J4_CONC || 6);

const inPath = process.argv[2];
const outPath = process.argv[3];
if (!inPath || !outPath) {
  console.error('用法: node huawei_to_j4.js <输入.md> <输出.json>');
  process.exit(2);
}

/* ------------------------------------------------------------------ 输入解析 */

function parseMarkdown(text) {
  const lines = text.split(/\r?\n/);
  const songs = [];
  const meta = {};
  for (const raw of lines) {
    const line = raw.trim();
    let m = /^-\s*([^:：]+)[:：]\s*(.*)$/.exec(line);
    if (m) {
      const k = m[1].trim();
      if (k === '歌单ID' || k === '创建者' || k === '歌曲数' || k === '分类' || k === '描述' || k === '封面') {
        meta[k] = m[2].trim();
      }
      continue;
    }
    // | 1 | 歌名 | 歌手 | 专辑 | 歌曲ID |
    if (line.startsWith('|')) {
      const cells = line.split('|').map((s) => s.trim());
      // split 后首尾是空串：["", "1", "歌名", ...]
      if (cells.length < 6) continue;
      const idx = cells[1];
      if (!/^\d+$/.test(idx)) continue;          // 跳过表头与分隔行
      songs.push({
        title: cells[2],
        artist: cells[3],
        album: cells[4],
        huaweiId: cells[5]
      });
    }
  }
  return { meta, songs };
}

/* ------------------------------------------------------------------ HTTP */

function request(url) {
  return new Promise((resolve, reject) => {
    const lib = url.startsWith('https') ? https : http;
    const req = lib.get(url, { headers: { 'X-J4-Token': TOKEN } }, (res) => {
      let body = '';
      res.setEncoding('utf8');
      res.on('data', (c) => (body += c));
      res.on('end', () => resolve(body));
    });
    req.on('error', reject);
    req.setTimeout(20000, () => req.destroy(new Error('timeout')));
  });
}

async function search(platform, keywords, limit = 8) {
  const url = BASE + '/search?platform=' + platform +
    '&keywords=' + encodeURIComponent(keywords) + '&limit=' + limit + '&type=1';
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      const body = await request(url);
      const j = JSON.parse(body);
      const list = (j.result && j.result.songs) || [];
      if (list.length) return list;
    } catch (e) {
      /* 空结果与网络抖动一样：再试一次 */
    }
    await new Promise((r) => setTimeout(r, 250 + attempt * 450));
  }
  return [];
}

/* ------------------------------------------------------------------ 匹配打分 */

function norm(s) {
  return String(s || '')
    .toLowerCase()
    .replace(/[\u3000\s]/g, '')
    .replace(/[·•・.,，。!！?？'"“”‘’\-_/\\&+()（）\[\]【】<>《》]/g, '');
}

/** 去掉括号里的补充说明："アンダーヒロイン(地下女主角)" → "アンダーヒロイン" */
function baseTitle(s) {
  return norm(String(s || '').replace(/[（(【\[].*?[）)】\]]/g, ''));
}

function artistNames(cand) {
  const arr = cand.ar || cand.artists || cand.singer || [];
  if (Array.isArray(arr)) return arr.map((a) => (typeof a === 'string' ? a : a && a.name)).filter(Boolean);
  return [];
}

function artistHit(want, got) {
  const parts = String(want || '').split(/[\/、,&，]/).map((s) => norm(s)).filter((s) => s.length >= 1);
  if (!parts.length) return false;
  const g = norm(got);
  if (!g) return false;
  return parts.some((p) => g.includes(p) || p.includes(g.slice(0, 6)));
}

/** 命中的歌手占比：原曲只有周杰伦，候选却是「周杰伦/某翻唱歌手」时不该拿满分 */
function artistScore(want, got) {
  const parts = String(want || '').split(/[\/、,&，]/).map((s) => norm(s)).filter((s) => s.length >= 1);
  if (!parts.length) return 0;
  const g = norm(got);
  if (!g) return 0;
  const hit = parts.filter((p) => g.includes(p)).length;
  return hit === 0 ? 0 : 6 * (hit / parts.length);
}

function albumOf(cand) {
  const al = cand.al || cand.album || {};
  return al && al.name ? al.name : '';
}

function coverOf(cand) {
  const al = cand.al || cand.album || {};
  return al && (al.picUrl || al.pic_url) ? (al.picUrl || al.pic_url) : null;
}

function durationOf(cand) {
  const d = cand.dt != null ? cand.dt : (cand.duration != null ? cand.duration : cand.interval);
  if (d == null) return 0;
  const n = Number(d) || 0;
  return n > 0 && n < 1000 ? n * 1000 : n;   // 少数接口回的是秒
}

function score(item, cand) {
  const wantT = norm(item.title);
  const wantB = baseTitle(item.title);
  const gotT = norm(cand.name || cand.title);
  const gotB = baseTitle(cand.name || cand.title);
  let s = 0;
  if (gotT && gotT === wantT) s += 12;
  else if (gotB && wantB && gotB === wantB) s += 9;
  else if (gotT && wantT && (gotT.startsWith(wantT) || wantT.startsWith(gotT)) && Math.min(gotT.length, wantT.length) >= 3) s += 5;
  else if (gotB && wantB && (gotB.includes(wantB) || wantB.includes(gotB)) && Math.min(gotB.length, wantB.length) >= 3) s += 3;
  else return -1;                              // 歌名都对不上，直接否掉

  // 歌手占 0~6 分：全部对上给满，命中的歌手只占一部分时按比例给，
  // 这样「周杰伦/翻唱歌手」不会和原版拿一样的分数
  s += Math.round(artistScore(item.artist, artistNames(cand).join('/')));
  if (artistHit(item.artist, artistNames(cand).join('/'))) s += 2;
  const al = norm(albumOf(cand));
  if (al && norm(item.album) && (al === norm(item.album) || al.includes(norm(item.album)))) s += 2;

  // 翻唱/现场/伴奏版本在歌名完全一致时会额外混进来，压一压
  const tag = /(live|现场|演唱会|伴奏|instrumental|dj|remix|翻唱|cover|版)/i;
  if (tag.test(cand.name || '') && !tag.test(item.title)) s -= 4;
  return s;
}

function toSong(source, cand) {
  const isQq = source === 'qq';
  const raw = isQq ? (cand.mid || String(cand.id)) : String(cand.id);
  const numId = isQq ? String(cand.id || '') : String(cand.id || '');
  const fee = Number(cand.fee || 0);
  const extra = isQq
    ? { mid: raw, songid: numId, platform: 'qqmusic' }
    : { songid: numId, platform: 'netease' };
  return {
    id: source + ':' + raw,
    source: source,
    title: cand.name || cand.title || '',
    artist: artistNames(cand).join('/'),
    album: albumOf(cand),
    durationMs: durationOf(cand),
    coverUrl: coverOf(cand),
    vip: fee === 1 || fee === 4,
    localPath: null,
    quality: 'standard',
    extra: extra
  };
}

/* ------------------------------------------------------------------ 单个条目解析 */

const PLATFORMS = ['netease', 'qq'];

async function resolveOne(item) {
  const keywordSets = [];
  const artist = String(item.artist || '').split(/[\/、,&，]/)[0].trim();
  keywordSets.push([item.title, artist].filter(Boolean).join(' '));
  keywordSets.push(item.title);

  let best = null;
  for (const kw of keywordSets) {
    for (const plat of PLATFORMS) {
      const cands = await search(plat, kw, 8);
      for (const c of cands) {
        const s = score(item, c);
        if (s < 0) continue;
        const fee = Number(c.fee || 0);
        const free = fee === 0 || fee === 8 ? 1 : 0;
        // 分数相同的时候优先免费的：能直接播比「原版但锁着」有用
        const better = !best || s > best.s || (s === best.s && free > best.free);
        if (better) best = { s: s, free: free, plat: plat, cand: c };
      }
      // 歌名完全一致且歌手也对上，就不必再问别的平台了
      if (best && best.s >= 18) break;
    }
    if (best && best.s >= 18) break;
  }
  if (!best) return { item: item, song: null, reason: '搜索无匹配' };
  return { item: item, song: toSong(best.plat, best.cand), reason: '', score: best.s };
}

/* ------------------------------------------------------------------ 封面回填 */

/**
 * 网易的 /search 只回 picId、不回 picUrl（QQ 才有 al.picUrl），所以搜出来的歌
 * 全是没封面的灰块。/song/detail 才有真实封面地址，这里按 50 首一批补上。
 */
async function backfillCovers(songs) {
  const need = songs.filter((s) => !s.coverUrl && s.source === 'netease');
  if (!need.length) return 0;
  const map = new Map();
  for (let i = 0; i < need.length; i += 50) {
    const chunk = need.slice(i, i + 50);
    const ids = chunk.map((s) => s.extra.songid).filter(Boolean).join(',');
    if (!ids) continue;
    for (let attempt = 0; attempt < 3; attempt++) {
      try {
        const body = await request(BASE + '/song/detail?platform=netease&ids=' + encodeURIComponent(ids));
        const j = JSON.parse(body);
        const arr = j.songs || (j.result && j.result.songs) || [];
        for (const o of arr) {
          const al = o.al || o.album || {};
          const pic = al.picUrl || al.pic_url || '';
          if (pic && o.id != null) map.set(String(o.id), pic);
        }
        if (arr.length) break;
      } catch (e) {
        /* 网络抖动：重试 */
      }
      await new Promise((r) => setTimeout(r, 300));
    }
  }
  let filled = 0;
  for (const s of songs) {
    if (s.coverUrl) continue;
    const pic = map.get(String(s.extra.songid));
    if (pic) { s.coverUrl = pic; filled++; }
  }
  return filled;
}

/* ------------------------------------------------------------------ 主流程 */

async function pool(items, worker, limit) {
  const out = new Array(items.length);
  let cursor = 0;
  let done = 0;
  async function run() {
    while (true) {
      const i = cursor++;
      if (i >= items.length) return;
      out[i] = await worker(items[i], i);
      done++;
      if (done % 20 === 0 || done === items.length) {
        process.stderr.write('  进度 ' + done + '/' + items.length + '\n');
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, run));
  return out;
}

(async () => {
  const text = fs.readFileSync(inPath, 'utf8');
  const { meta, songs } = parseMarkdown(text);
  console.error('解析到 ' + songs.length + ' 首，歌单名：' + (meta['歌单ID'] || '?'));
  if (!songs.length) {
    console.error('没有解析到歌曲，检查表格格式');
    process.exit(1);
  }

  const started = Date.now();
  const results = await pool(songs, resolveOne, CONCURRENCY);

  const list = results.filter((r) => r.song).map((r) => r.song);
  const missing = results.filter((r) => !r.song);

  const usedIds = new Set();
  const unique = [];
  for (const s of list) {
    if (usedIds.has(s.id)) continue;
    usedIds.add(s.id);
    unique.push(s);
  }

  const out = {
    format: 'j4',
    version: 1,
    name: '全部的歌',
    kind: 'custom',
    source: 'huawei',
    remoteId: meta['歌单ID'] || '',
    exportedAt: Date.now(),
    count: unique.length,
    cover: meta['封面'] || null,
    note: '由华为音乐歌单转换：原始 ' + songs.length + ' 首，按歌名+歌手匹配到网易云/QQ ' + unique.length + ' 首',
    songs: unique
  };
  const filled = await backfillCovers(unique);
  console.error('封面回填 ' + filled + ' 首');
  fs.writeFileSync(outPath, JSON.stringify(out, null, 2), 'utf8');

  const bySource = unique.reduce((acc, s) => { acc[s.source] = (acc[s.source] || 0) + 1; return acc; }, {});
  console.log(JSON.stringify({
    input: songs.length,
    matched: unique.length,
    unmatched: missing.length,
    bySource: bySource,
    ms: Date.now() - started,
    out: outPath
  }, null, 1));

  if (missing.length) {
    const report = missing.map((m) => '- ' + m.item.title + ' — ' + m.item.artist + '（' + m.reason + '，华为 ID ' + m.item.huaweiId + '）').join('\n');
    fs.writeFileSync(outPath.replace(/\.json$/, '') + '.unmatched.md', '# 未匹配 ' + missing.length + ' 首\n\n' + report + '\n', 'utf8');
    console.error('未匹配清单已写到 ' + outPath.replace(/\.json$/, '') + '.unmatched.md');
  }
})().catch((e) => {
  console.error('失败: ' + (e && e.stack || e));
  process.exit(1);
});
