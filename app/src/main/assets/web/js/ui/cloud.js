/* J4Music · ui/cloud.js —— 云端账号（分发会员）
 *
 * 与 login.js 的分工：
 *   login.js  —— 在**本机**登录音源，Cookie 存在手机上的 CookieJar 里；
 *   cloud.js  —— 在**服务器**登录音源，Cookie 存在上游的 data/accounts.json 里。
 *
 * 为什么两套都要有：会员分发的本意就是「服务器替你持有会员账号」，
 * 这样换设备、重装、清数据都不用重新登录；而本机登录适合只想用自己号的场景。
 * 两边同时存在时优先用本机账号，本机没登录就落到云端。
 */
(function (global) {
  'use strict';

  var D = global.J4DOM;
  var I = global.J4Icons;
  var B = global.J4Bridge;
  var S = global.J4Store;
  var UI = global.J4UI;
  var R = global.J4Router;
  var el = D.el;

  var NAMES = { netease: '网易云音乐', qq: 'QQ 音乐' };
  // 音源层现在只注册了网易云与 QQ（上游开源项目也只有这两家），
  // 只摆上游真正支持的平台，多摆一张点了没反应的卡只会让人以为坏了。
  var ORDER = ['netease', 'qq'];

  function render() {
    var page = el('div', { class: 'page page--push' });
    var header = el('div', { class: 'page-header' }, [
      el('button', { class: 'ibtn', 'aria-label': '返回', html: I.back, onclick: function () { R.back(); } }),
      el('div', { class: 'page-header__title', text: '云端账号' })
    ]);

    var scroll = el('div', { class: 'page__scroll' });
    var body = el('div', { class: 'page-pad' });

    body.appendChild(
      el('div', { class: 'vip-card' }, [
        el('div', { class: 'vip-card__sheen' }),
        el('div', { class: 'vip-card__title' }, [
          el('span', { class: 'ico', html: I.crown }),
          el('span', { text: '分发会员' })
        ]),
        el('div', {
          class: 'vip-card__desc',
          text: '在服务器上登录一次会员账号，之后本机所有设备的会员曲目都走这个账号取流。账号只保存在你的上游服务器上，不会回到手机。'
        })
      ])
    );

    // 网页控制台入口放在最上面：扫码要在浏览器里做，别让用户去猜点哪张卡
    body.appendChild(
      el('button', {
        class: 'btn btn--primary btn--block',
        style: { marginBottom: '12px' },
        onclick: function () { openConsole(); }
      }, [el('span', { text: '打开网页控制台（扫码 / 粘贴 Cookie）' })])
    );

    var stateLine = el('div', { class: 'search-status', text: '正在读取云端状态…' });
    body.appendChild(stateLine);

    var listHost = el('div', { class: 'list' });
    body.appendChild(listHost);

    var accounts = [];

    function accountOf(id) {
      for (var i = 0; i < accounts.length; i++) {
        if (accounts[i].source === id) return accounts[i];
      }
      return null;
    }

    function paint() {
      listHost.innerHTML = '';
      ORDER.forEach(function (id) {
        var a = accountOf(id) || { source: id, loggedIn: false };
        var card = el('div', { class: 'login-src' + (a.loggedIn ? ' is-on' : ''), dataset: { src: id } }, [
          UI.sourceLogo(id, 40),
          el('div', { class: 'login-src__info' }, [
            el('div', { class: 'login-src__name', text: NAMES[id] }),
            el('div', {
              class: 'login-src__state',
              text: a.loggedIn
                ? (a.nickname ? a.nickname : '已登录') + (a.vip === true ? ' · 会员' : a.vip === false ? ' · 非会员' : ' · 会员状态未知')
                : '未登录 · VIP 曲目取不到正式音源'
            })
          ]),
          el('button', {
            class: 'btn btn--sm ' + (a.loggedIn ? 'btn--ghost' : 'btn--primary'),
            onclick: function (e) {
              e.stopPropagation();
              if (a.loggedIn) doLogout(id);
              else doLogin(id);
            }
          }, [el('span', { text: a.loggedIn ? '退出' : '登录' })])
        ]);
        listHost.appendChild(card);
      });
    }

    function refresh() {
      return B.call('upstreamAccount')
        .then(function (d) {
          if (!d || !d.enabled) {
            stateLine.textContent = '上游地址未配置，请回设置页填写';
            accounts = [];
            paint();
            return;
          }
          if (!d.ok) {
            stateLine.textContent = d.message || '读取失败';
            accounts = [];
            paint();
            return;
          }
          // 桥这边回的是 { platforms: {netEase:{loggedIn,...}, qqmusic:{...}}, list:[...] }，
          // 老版本只看 list，字段一换就永远「读取失败」—— 两种形状都吃。
          if (Array.isArray(d.list) && d.list.length) {
            accounts = d.list;
          } else if (d.platforms) {
            accounts = ORDER.map(function (id) {
              var p = d.platforms[id === 'qq' ? 'qqmusic' : id] || d.platforms[id] || {};
              return {
                source: id,
                loggedIn: !!p.loggedIn,
                serverLoggedIn: !!p.serverLoggedIn,
                nickname: p.uid ? ('uid ' + p.uid) : ''
              };
            });
          } else {
            accounts = [];
          }
          var on = accounts.filter(function (x) { return x.loggedIn; }).length;
          var onlyServer = accounts.filter(function (x) { return x.loggedIn && x.serverLoggedIn; }).length;
          stateLine.textContent = on > 0
            ? ('云端已登录 ' + on + ' 个账号' + (onlyServer > 0 ? '（其中 ' + onlyServer + ' 个来自服务器）' : ''))
            : '云端还没有任何账号';
          paint();
        })
        .catch(function (e2) {
          stateLine.textContent = '读取失败：' + e2.message;
          accounts = [];
          paint();
        })
        // 统一收尾成 always-resolve：调用方（「我已完成」按钮）只关心刷新完没完
        .then(function () { return null; }, function () { return null; });
    }

    /* ---------------------------------------------------------------- */

    function doLogin(id) {
      // 手机端不再展示二维码：用户只能拿另一台设备扫自己屏幕，
      // 而且这几家的扫码都要退回自家 App 的「扫一扫」，WebView 调不到相机，
      // 手机上也开不了开发者工具复制 Cookie。登录动作整体外移到服务器网页控制台。
      UI.openSheet({
        title: '云端登录 · ' + NAMES[id],
        sub: '登录在服务器网页上完成，本机自动同步',
        render: function (ctx) {
          var wrap = el('div', { class: 'col', style: { padding: '0 18px 18px', gap: '14px' } });

          var steps = el('div', { class: 'login-src__state' }, [
            el('div', { text: '1. 点下面的按钮，用浏览器打开上游控制台' }),
            el('div', { text: '2. 在里面点「' + NAMES[id] + '」旁边的「登录」，扫码或贴 Cookie' }),
            el('div', { text: '3. 回到这里点「我已完成」，状态会自动刷新' })
          ]);
          wrap.appendChild(steps);

          var urlLine = el('div', { class: 'login-src__state', text: '正在读取控制台地址…', style: { wordBreak: 'break-all' } });
          wrap.appendChild(urlLine);
          // 这一行以前永远停在「正在读取控制台地址…」：没人去取地址，只有点了按钮才写。
          B.call('upstreamAccount')
            .then(function (d) {
              urlLine.textContent = (d && d.base) ? d.base : '上游地址未配置，请回设置页填写';
            })
            .catch(function () { urlLine.textContent = '控制台地址读取失败'; });


          var status = el('div', { class: 'search-status', text: '' });
          wrap.appendChild(status);

          // 手机上没有开发者工具，也复制不到 Cookie，所以主体路径是「去网页登录」。
          // Cookie 输入框只作为兜底（用户从电脑上复制过来贴）放在折叠区里。
          // `.col` 自带 display:flex，会把 [hidden] 的 display:none 顶掉，
          // 所以这里用行内 display 控制展开/收起，别用 hidden 属性。
          var more = el('div', { class: 'col', style: { gap: '10px', display: 'none' } });
          // moreBtn 必须定义在 more 之后：上面的 onclick 闭包会引用它
          var moreBtn = el('button', {
            class: 'btn btn--ghost btn--block',
            onclick: function () {
              more.style.display = '';
              moreBtn.style.display = 'none';
            }
          }, [el('span', { text: '我有 Cookie，手动粘贴' })]);
          wrap.appendChild(moreBtn);
          var cookieInput = el('input', { class: 'field__input', placeholder: id === 'netease' ? 'MUSIC_U=xxxx; ...' : id === 'qq' ? 'qqmusic_key=xxx; uin=123' : 'token=xxx; userid=123' });
          more.appendChild(el('div', { class: 'field' }, [
            el('label', { class: 'field__label', text: '粘贴 Cookie' }),
            cookieInput
          ]));
          more.appendChild(el('button', {
            class: 'btn btn--block',
            onclick: function () {
              var c = cookieInput.value.trim();
              if (!c) { UI.toast('请先粘贴 Cookie'); return; }
              status.textContent = '正在校验…';
              B.call('upstreamLoginCookie', { source: id, cookie: c })
                .then(function () {
                  UI.toast('云端登录成功', 'success');
                  ctx.close();
                  refresh();
                })
                .catch(function (e) { status.textContent = '登录失败：' + e.message; });
            }
          }, [el('span', { text: '用 Cookie 登录' })]));
          wrap.appendChild(more);
          wrap.appendChild(el('button', {
            class: 'btn btn--primary btn--block',
            onclick: function () {
              status.textContent = '正在打开浏览器…';
              B.call('authWebLogin', { source: id })
                .then(function (d) {
                  urlLine.textContent = '已打开：' + ((d && d.url) || '');
                  // 网页那边一登录成功，这里就应该自己变绿，不用用户来回切
                  status.textContent = '在浏览器里完成登录后，这里会自动刷新';
                  watchLogin();
                })
                .catch(function (e) { status.textContent = '打不开：' + e.message; });
            }
          }, [el('span', { text: '在浏览器里登录' })]));
          wrap.appendChild(el('button', {
            class: 'btn btn--ghost btn--block',
            onclick: function () {
              status.textContent = '正在刷新云端状态…';
              refresh().then(function () {
                var a = accountOf(id);
                if (a && a.loggedIn) { UI.toast('已同步到云端登录状态', 'success'); ctx.close(); }
                else { status.textContent = '云端还没看到登录态，确认网页上已经登录成功后再试一次'; }
              });
            }
          }, [el('span', { text: '我已完成，刷新状态' })]));

          return wrap;
        }
      });
    }

    /**
     * 用户去浏览器里登录期间，后台每 4 秒问一次账号状态，最多问 5 分钟。
     *
     * 为什么不做推送：登录是在另一台设备/另一个进程里发生的，App 没有任何
     * 事件源；轮询账号状态是唯一可靠的做法，而且代价很小（一个 200 字节的 JSON）。
     */
    function watchLogin() {
      var rounds = 0;
      function tick() {
        if (rounds++ > 75) return;
        refresh().then(function () {
          var any = accounts.filter(function (x) { return x.loggedIn; }).length;
          if (any > 0) {
            UI.toast('已同步到云端登录状态', 'success');
            return;
          }
          setTimeout(tick, 4000);
        }).catch(function () { setTimeout(tick, 6000); });
      }
      setTimeout(tick, 3000);
    }

    /** 常驻入口：直接把上游网页控制台甩给系统浏览器（不依赖当前选的是哪个源） */
    function openConsole() {
      B.call('authWebLogin', {})
        .then(function () { UI.toast('已打开控制台'); })
        .catch(function (e) { UI.toastError(e, { key: 'authWebLogin' }); });
    }

    function doLogout(id) {
      UI.confirm({
        title: '退出云端登录',
        message: '退出 ' + NAMES[id] + ' 后，云端将不再用这个账号为会员曲目取流。',
        okText: '退出'
      }).then(function (ok) {
        if (!ok) return;
        B.call('upstreamLogout', { source: id })
          .then(function () {
            UI.toast('已退出');
            refresh();
          })
          .catch(function (e) { UI.toastError(e, { key: 'upstreamLogout::' + id }); });
      });
    }

    page.appendChild(header);
    page.appendChild(scroll);
    scroll.appendChild(body);
    paint();
    refresh();

    return page;
  }

  R.register('cloud', { title: '云端账号', render: render });
})(window);
