#!/usr/bin/env node
/* J4Music · tools/verify-gateway.js
 *
 * 网关自检：确认「服务器上保存的登录态真的被带到了上游」。
 *
 * 为什么单独写这个：网关曾经只把 cookie 写进 ?cookie= 查询参数，
 * 而上游模块是从请求头 Cookie 里取 MUSIC_U 的，结果服务器明明登录了会员，
 * 取址仍按匿名发（VIP 曲只回 128k 试听或 404），表现成「登录了却用不了」。
 * 这个脚本用会员曲对比「带 cookie / 不带 cookie」的返回差异，一次就能看出来。
 *
 * 用法：
 *   J4_GATEWAY=http://127.0.0.1:8080 J4_TOKEN=<口令> node tools/verify-gateway.js
 *   J4_VIP_ID=108914        # 可选，换成你账号下确认是会员曲的网易 id
 */
'use strict';

const BASE = (process.env.J4_GATEWAY || 'http://127.0.0.1:8080').replace(/\/$/, '');
const TOKEN = process.env.J4_TOKEN || '';
const VIP_ID = process.env.J4_VIP_ID || '108914';   // 江南，网易 fee=1
const FREE_ID = process.env.J4_FREE_ID || '33894312';

if (!TOKEN) {
  console.error('缺少 J4_TOKEN；用法：J4_GATEWAY=http://127.0.0.1:8080 J4_TOKEN=xxx node tools/verify-gateway.js');
  process.exit(2);
}

let pass = 0;
let fail = 0;

function ok(cond, name, detail) {
  if (cond) { pass++; console.log('  ok   ' + name); }
  else { fail++; console.log('  FAIL ' + name + (detail ? '  -> ' + detail : '')); }
}

async function get(path) {
  const r = await fetch(BASE + path, { headers: { 'X-J4-Token': TOKEN } });
  const text = await r.text();
  let json = null;
  try { json = JSON.parse(text); } catch (e) { /* 保持 null */ }
  return { status: r.status, json, text };
}

function first(data) {
  return (data && data.data && data.data[0]) || (data && data.songs && data.songs[0]) || {};
}

(async () => {
  console.log('网关 ' + BASE);

  // 1) 账号态：能读到 uid 说明 Cookie 头确实到了上游
  const acc = await get('/user/account?platform=netease');
  const body = (acc.json && acc.json.body) || {};
  const account = body.account || null;
  ok(acc.status === 200, '账号接口可达', String(acc.status));
  ok(!!account && !!account.id, '服务器登录态已生效（读到 uid）',
    account ? '' : '上游回 account=null，说明 Cookie 没带上');
  if (account) console.log('       uid=' + account.id + ' vipType=' + account.vipType + ' paidFee=' + account.paidFee);

  // 2) 会员曲：应该拿到 flac/320k 的正式地址，而不是 721023 字节的试听壳
  const vip = await get('/song/url/v1?id=' + VIP_ID + '&level=lossless&platform=netease');
  const v = first(vip.json);
  ok(vip.status === 200, '会员曲取址接口可达', String(vip.status));
  ok(!!v.url, '会员曲返回了地址', JSON.stringify(v).slice(0, 120));
  const vipReal = !!v.url && !(v.size === 721023 && v.br === 128008);
  ok(vipReal, '会员曲拿到的是正式版（不是 30 秒试听）',
    'br=' + v.br + ' size=' + v.size + ' type=' + v.type);
  if (v.url) console.log('       type=' + v.type + ' br=' + v.br + ' size=' + v.size);

  // 3) 免费曲：无论登录与否都该有地址，作为对照组
  const free = await get('/song/url/v1?id=' + FREE_ID + '&level=exhigh&platform=netease');
  const f = first(free.json);
  ok(!!f.url, '免费曲仍可播放（对照组）', JSON.stringify(f).slice(0, 120));

  // 4) QQ 回归：只验证有地址且带票据，不判断会员档位
  const qq = await get('/song/url/v1?id=0039MnYb0qxYhV&level=exhigh&platform=qqmusic');
  const q = first(qq.json);
  ok(!!q.url && /vkey=/.test(q.url), 'QQ 取址带上了 vkey', String(q.url || '').slice(0, 80));

  console.log('\n== ' + pass + ' passed, ' + fail + ' failed ==');
  process.exit(fail ? 1 : 0);
})().catch((e) => {
  console.error('运行出错：' + e.message);
  process.exit(2);
});
