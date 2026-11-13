// J4 网关：把开源 multiPlatformMusicApi（127.0.0.1:8081）暴露到公网 8080，
// 并挂一个网页控制台（扫码 / 粘贴 Cookie 登录上游账号）。
//
// 三件事：
//   1. 令牌校验（App 用 X-J4-Token，浏览器用 ?token=）；
//   2. QQ 搜索补丁层 —— QQ 的 musicu.fcg 对出口 IP 有突发限流，同一个查询
//      连打两次就可能回 req_1.code=2001（表现成「搜索成功但 0 条」），
//      这里换成 limit=30 重试并缓存 10 分钟，第一次慢、之后都命中本地；
//   3. 服务端登录态 —— 网页上扫码/贴 Cookie 后存在 /opt/j4-gateway/cookies.json，
//      之后任何不带 cookie 参数的请求都自动带上，这就是「分发会员」：
//      上游登录一次，所有连这个服务的 App 都用它的会员音源。
const http = require('http')
const fs = require('fs')
const path = require('path')
const { URL } = require('url')
const QRCode = require('qrcode')   // 网易的扫码接口只给 unikey，不给图片，图片在这里自己生成

const PORT = Number(process.env.PORT || 8080)
const TARGET_HOST = '127.0.0.1'
const TARGET_PORT = Number(process.env.TARGET_PORT || 8081)
// SERVER-1 的出口 IP 被 QQ 的 musicu.fcg 按 IP 限流（2001 空结果），
// 所以 QQ 搜索改走 SERVER-2 上的中继（ssh -L 隧道映射到本机 8091）。
const RELAY_PORT = Number(process.env.RELAY_PORT || 8091)
// 口令必须由环境变量提供；仓库里不放默认值，避免把线上令牌带进版本库
const TOKEN = process.env.J4_TOKEN || ''
const CACHE_TTL = 10 * 60 * 1000
const COOKIE_FILE = process.env.COOKIE_FILE || path.join(__dirname, 'cookies.json')
const CONSOLE_FILE = path.join(__dirname, 'public', 'index.html')
const PLATFORMS = ['netease', 'qqmusic']
const startedAt = Date.now()

const cache = new Map()   // key -> {at, body}

/* ---------------------------------------------------------------- */
/* 服务端登录态                                                      */
/* ---------------------------------------------------------------- */

let cookies = {}   // platform -> { cookie, uid, at }

function loadCookies() {
  try {
    const raw = fs.readFileSync(COOKIE_FILE, 'utf8')
    const j = JSON.parse(raw)
    cookies = (j && typeof j === 'object') ? j : {}
  } catch (e) {
    cookies = {}
  }
}

function saveCookies() {
  try {
    const tmp = COOKIE_FILE + '.tmp'
    fs.writeFileSync(tmp, JSON.stringify(cookies, null, 2))
    fs.renameSync(tmp, COOKIE_FILE)
  } catch (e) {
    console.error('[cookies] 保存失败: ' + e.message)
  }
}

/**
 * 上游回下来的 cookie 有两副面孔：
 *  - 字符串（浏览器复制的那种 `a=1; b=2`）；
 *  - **对象**（开源实现返回的是 `{ MUSIC_U: '...', __csrf: '...' }`，QQ 是合并后的字典）。
 * 早先只按字符串处理，结果扫码成功后 `String({})` 变成 "[object Object]"、
 * 匹配不到 `name=value` 就悄悄不保存 —— 表现就是「扫码提示成功，卡片还是未登录」。
 */
function cookieToString(v) {
  if (!v) return ''
  if (typeof v === 'string') return v.trim()
  if (Array.isArray(v)) return v.map(cookieToString).filter(Boolean).join('; ')
  if (typeof v === 'object') {
    const parts = []
    Object.keys(v).forEach((k) => {
      const val = v[k]
      if (val == null || val === '' || typeof val === 'object') return
      parts.push(k + '=' + String(val))
    })
    return parts.join('; ')
  }
  return ''
}

function uidOf(platform, cookie) {
  if (!cookie) return ''
  if (platform === 'qqmusic') {
    const m = /(?:^|;\s*)uin=o?(\d+)/i.exec(cookie)
    if (m) return m[1]
    const m2 = /(?:^|;\s*)(?:p_uin|qqmusic_uin|wxuin)=o?(\d+)/i.exec(cookie)
    if (m2) return m2[1]
  }
  return ''
}

function setCookie(platform, cookie) {
  const clean = cookieToString(cookie)
  if (!clean || !/[\w-]+=/.test(clean)) return false
  const old = cookies[platform]
  cookies[platform] = {
    cookie: clean,
    uid: uidOf(platform, clean) || (old && old.uid) || '',
    at: Date.now()
  }
  saveCookies()
  return true
}

loadCookies()

/* ---------------------------------------------------------------- */
/* 工具                                                              */
/* ---------------------------------------------------------------- */

function authorized(req, url) {
  if (req.headers['x-j4-token'] === TOKEN) return true
  if (url.searchParams.get('token') === TOKEN) { url.searchParams.delete('token'); return true }
  return false
}

function cors() {
  return { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*', 'Access-Control-Allow-Methods': 'GET,POST,OPTIONS' }
}

function sendJson(res, status, obj) {
  const body = JSON.stringify(obj)
  res.writeHead(status, Object.assign({ 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' }, cors()))
  res.end(body)
}

function readBody(req, limit) {
  const cap = limit || 1024 * 256
  return new Promise((resolve) => {
    let data = ''
    req.setEncoding('utf8')
    req.on('data', (c) => { data += c; if (data.length > cap) req.destroy() })
    req.on('end', () => {
      try { resolve(JSON.parse(data || '{}')) } catch (e) { resolve({}) }
    })
    req.on('error', () => resolve({}))
  })
}

/** 直接问上游一次，拿回 (statusCode, bodyString) */
function fetchUpstream(method, path, headers, body, port) {
  return new Promise((resolve, reject) => {
    const req = http.request({ host: TARGET_HOST, port: port || TARGET_PORT, method, path,
      headers: Object.assign({}, headers, { host: TARGET_HOST + ':' + (port || TARGET_PORT) }) }, (res) => {
      let data = ''
      res.setEncoding('utf8')
      res.on('data', (c) => { data += c })
      res.on('end', () => resolve({ status: res.statusCode || 502, type: res.headers['content-type'] || 'application/json', body: data }))
    })
    req.on('error', reject)
    if (body) req.write(body)
    req.end()
  })
}

function songCount(body) {
  try {
    const j = JSON.parse(body)
    const songs = j && j.result && j.result.songs
    return Array.isArray(songs) ? songs.length : 0
  } catch (e) { return -1 }
}

/** 把 limit 改掉，其余参数原样保留 */
function withLimit(path, limit) {
  const u = new URL(path, 'http://x')
  u.searchParams.set('limit', String(limit))
  return u.pathname + '?' + u.searchParams.toString()
}

/* ---------------------------------------------------------------- */
/* QQ 搜索补丁层                                                     */
/* ---------------------------------------------------------------- */

async function handleSearchQQ(url, headers) {
  // 缓存按「关键词+offset」存整份 30 条，不同 limit 都在本地截断，
  // 这样用户在搜索框里边打字边搜不会每敲一个字就重新撞一次 QQ。
  const kwKey = 'kw:' + (url.searchParams.get('keywords') || '') + '|' + (url.searchParams.get('offset') || '0')
  const want = Number(url.searchParams.get('limit') || 30)
  const hit = cache.get(kwKey)
  if (hit && Date.now() - hit.at < CACHE_TTL) return slim(hit.body, want, true)

  // 首选 SERVER-2 中继：换出口 IP 后 QQ 一次就中（实测 4/4），
  // 本机 8081 的出口 IP 现在已经 100% 返回 req_1.code=2001。
  const relayPath = '/search?keywords=' + encodeURIComponent(url.searchParams.get('keywords') || '')
    + '&limit=30&offset=' + (url.searchParams.get('offset') || '0')
  for (let i = 0; i < 3; i++) {
    try {
      const r = await fetchUpstream('GET', relayPath, { 'x-j4-token': TOKEN }, null, RELAY_PORT)
      if (songCount(r.body) > 0) {
        cache.set(kwKey, { at: Date.now(), body: r.body })
        return slim(r.body, want, false)
      }
    } catch (e) { /* 隧道断了下一次再来 */ }
    await new Promise((res) => setTimeout(res, 600))
  }

  // 中继也拿不到才回落到本机开源上游（带重试）
  const samePath = url.pathname + (url.search || '')
  const big = withLimit(samePath, 30)
  let last = null
  for (const p of [big, big, samePath]) {
    last = await fetchUpstream('GET', p, headers)
    if (songCount(last.body) > 0) {
      cache.set(kwKey, { at: Date.now(), body: last.body })
      return slim(last.body, want, false)
    }
    await new Promise((res) => setTimeout(res, 700))
  }
  return last
}

/** 把缓存/大份结果按调用方要的数量截断 */
function slim(body, want, cached) {
  try {
    const j = JSON.parse(body)
    if (j && j.result && Array.isArray(j.result.songs)) {
      const all = j.result.songs
      j.result.songs = all.slice(0, want)
      j.result.songCount = j.result.songs.length
      j.result.hasMore = all.length > want
    }
    if (j && cached) j.cached = true
    return { status: 200, type: 'application/json', body: JSON.stringify(j) }
  } catch (e) {
    return { status: 200, type: 'application/json', body }
  }
}

/* ---------------------------------------------------------------- */
/* 给上游请求补上服务端登录态                                        */
/* ---------------------------------------------------------------- */

/**
 * 给上游请求补上登录态。
 *
 * 两种补法不一样：
 *  - 网易这类实现直接从 Cookie 头/参数取登录态，`cookie=` 就够；
 *  - QQ 的 `platforms/qqmusic/module/song_url.js` 读的是 **`query.uin` 与 `query.qm_keyst`**，
 *    根本不看 `cookie` 参数。所以登录了也还是 `purl` 空 —— 必须把这两个参数拆出来单独塞进去，
 *    否则「扫码登录成功、VIP 曲目照样拿不到地址」。
 */
function injectCookie(url) {
  if (url.pathname.indexOf('/login/') === 0) return   // 登录相关请求不能被历史 cookie 干扰
  const plat = url.searchParams.get('platform')
  if (!plat) return
  const fromClient = url.searchParams.get('cookie')
  const saved = cookies[plat] && cookies[plat].cookie
  const cookie = fromClient || saved || ''
  if (!cookie) return
  if (!fromClient && saved) url.searchParams.set('cookie', saved)

  if (plat !== 'qqmusic') return
  // QQ 的 song_url 模块是这么写的：
  //   const songMid = query.mid ? query.mid : (await getSongDetail({id: query.id})).songs[0].mid
  // 也就是说只给 id 时，它会把 id 当成「数字歌曲 id」去反查 mid。我们手上本来就是 mid
  // （14 位字母数字，如 0039MnYb0qxYhV），于是反查落到了别的歌上 —— 出来的地址是 BEYOND
  // 的《想你》，看起来「会员过期」，其实是货不对板。这里把 mid 直接补上，绕开那次反查。
  if (!url.searchParams.get('mid')) {
    const id = url.searchParams.get('id')
    if (id && !/^\d+$/.test(id)) url.searchParams.set('mid', id)
  }
  const val = (name) => {
    const m = new RegExp('(?:^|;\\s*)' + name + '=([^;]+)').exec(cookie)
    return m ? m[1].trim() : ''
  }
  if (!url.searchParams.get('uin')) {
    const uin = val('uin') || val('musicid')
    if (uin) url.searchParams.set('uin', uin.replace(/^o/i, ''))
  }
  if (!url.searchParams.get('qm_keyst')) {
    const keyst = val('qm_keyst') || val('qqmusic_key')
    if (keyst) url.searchParams.set('qm_keyst', keyst)
  }
}

/** 扫码轮询成功时，把上游回下来的 cookie 存起来 —— 这就是网页扫码登录的落地点 */
function captureLoginCookie(url, out) {
  if (url.pathname !== '/login/qr/check' || !out || !out.body) return
  let j
  try { j = JSON.parse(out.body) } catch (e) { return }
  const body = (j && j.body) || j || {}
  const code = body.code != null ? body.code : j.code
  if (code !== 803) return
  const plat = url.searchParams.get('platform') || 'netease'
  const cookie = body.cookie || j.cookie || (body.data && body.data.cookie) || ''
  if (setCookie(plat, cookie)) {
    console.log('[login] ' + plat + ' 扫码成功，登录态已保存（' + cookies[plat].cookie.length + ' 字节）')
  } else {
    console.warn('[login] ' + plat + ' 扫码成功，但没有从响应里取到 cookie：' + JSON.stringify(cookie).slice(0, 200))
  }
}

/* ---------------------------------------------------------------- */
/* HTTP                                                             */
/* ---------------------------------------------------------------- */

function serveConsole(res) {
  fs.readFile(CONSOLE_FILE, (err, buf) => {
    if (err) {
      sendJson(res, 500, { ok: false, msg: '控制台页面缺失：' + err.message })
      return
    }
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-cache' })
    res.end(buf)
  })
}

/* ---------------- 云端歌单 ---------------- */
// 歌单存在服务器上而不是浏览器里，换设备、重装 App 都能拿回来。
// 单用户场景：一个 id 一个文件，列表直接扫目录。
const PL_DIR = process.env.PLAYLIST_DIR || path.join(__dirname, 'playlists')

function plSafeId(id) {
  return String(id || '').replace(/[^A-Za-z0-9_-]/g, '')
}

function plFile(id) {
  return path.join(PL_DIR, plSafeId(id) + '.json')
}

function plRead(id) {
  try { return JSON.parse(fs.readFileSync(plFile(id), 'utf8')) } catch (e) { return null }
}

function plWrite(pl) {
  try {
    fs.mkdirSync(PL_DIR, { recursive: true })
    const tmp = plFile(pl.id) + '.tmp'
    fs.writeFileSync(tmp, JSON.stringify(pl))
    fs.renameSync(tmp, plFile(pl.id))
    return true
  } catch (e) {
    console.warn('[playlist] 写盘失败', e.message)
    return false
  }
}

function plSummary(pl) {
  return {
    id: pl.id,
    name: pl.name || '未命名歌单',
    cover: pl.cover || '',
    kind: pl.kind || 'cloud',
    source: pl.source || '',
    songCount: (pl.songs || []).length,
    updatedAt: pl.updatedAt || 0
  }
}

function plList() {
  let names = []
  try { names = fs.readdirSync(PL_DIR) } catch (e) { return [] }
  const out = []
  names.forEach((n) => {
    if (!/\.json$/.test(n)) return
    const pl = plRead(n.replace(/\.json$/, ''))
    if (pl && pl.id) out.push(plSummary(pl))
  })
  out.sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0))
  return out
}

http.createServer(async (req, res) => {
  let url
  try { url = new URL(req.url, 'http://localhost') } catch (e) { res.writeHead(400).end('bad url'); return }
  if (req.method === 'OPTIONS') { res.writeHead(204, cors()).end(); return }

  // 控制台页面本身不需要口令（里面要填口令才有用），这样手机上点开就能看
  if (req.method === 'GET' && (url.pathname === '/' || url.pathname === '/console' || url.pathname === '/index.html')) {
    serveConsole(res)
    return
  }

  if (url.pathname === '/api/ping') {
    res.writeHead(200, { 'Content-Type': 'application/json' })
      .end(JSON.stringify({ ok: true, service: 'j4-gateway', upstream: TARGET_PORT }))
    return
  }

  if (!authorized(req, url)) {
    res.writeHead(401, { 'Content-Type': 'application/json; charset=utf-8' })
      .end(JSON.stringify({ code: 401, msg: '缺少或错误的 X-J4-Token' }))
    return
  }

  /* ---------------- 控制台接口 ---------------- */

  if (req.method === 'GET' && url.pathname === '/api/state') {
    const out = {}
    PLATFORMS.forEach((p) => { out[p] = cookies[p] ? { cookie: cookies[p].cookie, uid: cookies[p].uid, at: cookies[p].at } : null })
    sendJson(res, 200, { ok: true, uptime: Math.floor((Date.now() - startedAt) / 1000), upstream: '127.0.0.1:' + TARGET_PORT, cookies: out })
    return
  }

  if (req.method === 'POST' && url.pathname === '/api/login/save') {
    const body = await readBody(req)
    const plat = String(body.platform || '')
    if (PLATFORMS.indexOf(plat) < 0) { sendJson(res, 400, { ok: false, msg: '未知平台' }); return }
    if (!setCookie(plat, body.cookie)) { sendJson(res, 400, { ok: false, msg: 'Cookie 看起来不对（没有 name=value）' }); return }
    sendJson(res, 200, { ok: true, platform: plat, uid: cookies[plat].uid })
    return
  }

  if (req.method === 'POST' && url.pathname === '/api/login/clear') {
    const body = await readBody(req)
    const plat = String(body.platform || '')
    if (cookies[plat]) { delete cookies[plat]; saveCookies() }
    sendJson(res, 200, { ok: true })
    return
  }

  /* ---------------- 云端歌单 ---------------- */
  if (url.pathname === '/api/playlists' && req.method === 'GET') {
    sendJson(res, 200, { ok: true, playlists: plList() })
    return
  }

  if (url.pathname === '/api/playlists' && req.method === 'PUT') {
    const body = await readBody(req, 8 * 1024 * 1024)
    const pl = {
      id: plSafeId(body.id),
      name: String(body.name || '未命名歌单').slice(0, 120),
      cover: String(body.cover || ''),
      kind: String(body.kind || 'cloud'),
      source: String(body.source || ''),
      songs: Array.isArray(body.songs) ? body.songs : [],
      updatedAt: Date.now()
    }
    if (!pl.id) { sendJson(res, 400, { ok: false, msg: 'missing id' }); return }
    if (!plWrite(pl)) { sendJson(res, 500, { ok: false, msg: '写盘失败' }); return }
    sendJson(res, 200, { ok: true, playlist: plSummary(pl) })
    return
  }

  const plMatch = url.pathname.match(/^\/api\/playlists\/([A-Za-z0-9_-]+)$/)
  if (plMatch) {
    const id = plMatch[1]
    if (req.method === 'GET') {
      const pl = plRead(id)
      if (!pl) { sendJson(res, 404, { ok: false, msg: '云上没有这个歌单' }); return }
      sendJson(res, 200, { ok: true, playlist: pl })
      return
    }
    if (req.method === 'DELETE') {
      try { fs.unlinkSync(plFile(id)) } catch (e) {}
      sendJson(res, 200, { ok: true })
      return
    }
  }

  /* ---------------- 二维码图片 ---------------- */

  // 开源上游的 /login/qr/key 只返回 unikey（网易），二维码内容要客户端自己拼
  // （https://music.163.com/login?codekey=<unikey>），图片则由这里生成。
  if (req.method === 'GET' && url.pathname === '/qr') {
    const text = url.searchParams.get('text') || ''
    if (!text) { sendJson(res, 400, { ok: false, msg: 'missing text' }); return }
    try {
      const dataUri = await QRCode.toDataURL(text, { errorCorrectionLevel: 'M', margin: 1, width: 480 })
      sendJson(res, 200, { ok: true, text: text, dataUri: dataUri })
    } catch (e) {
      sendJson(res, 500, { ok: false, msg: e.message })
    }
    return
  }

  /* ---------------- 透明转发 ---------------- */
  try {
    injectCookie(url)
    let out
    if (req.method === 'GET' && url.pathname === '/search' && url.searchParams.get('platform') === 'qqmusic') {
      out = await handleSearchQQ(url, req.headers)
    } else {
      out = await fetchUpstream(req.method, url.pathname + (url.search || ''), req.headers, null)
    }
    captureLoginCookie(url, out)
    res.writeHead(out.status, Object.assign({ 'Content-Type': out.type }, cors()))
    res.end(out.body)
  } catch (e) {
    if (!res.headersSent) res.writeHead(502, Object.assign({ 'Content-Type': 'application/json' }, cors()))
    res.end(JSON.stringify({ code: 502, msg: '上游不可用: ' + e.message }))
  }
}).listen(PORT, '0.0.0.0', () => console.log('j4-gateway on ' + PORT + ' -> ' + TARGET_HOST + ':' + TARGET_PORT))
