/* J4Music · ui/login.js —— 账号与会员（音源登录 / 会员分发说明 / 二维码）
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

  function render() {
    var page = el('div', { class: 'page page--push' });
    var header = el('div', { class: 'page-header' }, [
      el('button', { class: 'ibtn', 'aria-label': '返回', html: I.back, onclick: function () { R.back(); } }),
      el('div', { class: 'page-header__title', text: '账号与会员' })
    ]);

    var scroll = el('div', { class: 'page__scroll' });
    var body = el('div', { class: 'page-pad' });

    body.appendChild(
      el('div', { class: 'vip-card' }, [
        el('div', { class: 'vip-card__sheen' }),
        el('div', { class: 'vip-card__title' }, [el('span', { class: 'ico', html: I.crown }), el('span', { text: '会员与版权' })]),
        el('div', { class: 'vip-card__desc', text: '会员曲目由服务器上的 VIP 账号代为取流（会员分发），或使用你自己在该音源登录的会员账号播放。两种方式可同时开启，优先使用你自己的账号。' })
      ])
    );

    var srcHead = el('div', { class: 'section-head' }, [el('h2', { text: '音源登录' })]);
    body.appendChild(srcHead);
    var listHost = el('div', { class: 'list' });
    body.appendChild(listHost);

    function paint() {
      listHost.innerHTML = '';
      var srcs = S.state.sources && S.state.sources.length
        ? S.state.sources
        : [
            { id: 'netease', name: '网易云', loggedIn: false, requiresLogin: true },
            { id: 'qq', name: 'QQ 音乐', loggedIn: false, requiresLogin: true },
                  { id: 'huawei', name: '华为音乐', loggedIn: false, requiresLogin: true }
          ];
      srcs.forEach(function (s) {
        var card = el('div', { class: 'login-src' + (s.loggedIn ? ' is-on' : ''), dataset: { src: s.id } }, [
          UI.sourceLogo(s.id, 40),
          el('div', { class: 'login-src__info' }, [
            el('div', { class: 'login-src__name', text: NAMES[s.id] || s.name || s.id }),
            el('div', { class: 'login-src__state', text: s.loggedIn ? '已登录 · 会员曲目可按账号播放' : s.requiresLogin ? '未登录 · 会员曲目受限' : '无需登录' })
          ]),
          el('button', {
            class: 'btn btn--sm ' + (s.loggedIn ? 'btn--ghost' : 'btn--primary'),
            onclick: function (e) {
              e.stopPropagation();
              if (s.loggedIn) logout(s);
              else login(s);
            }
          }, [el('span', { text: s.loggedIn ? '退出' : '登录' })])
        ]);
        listHost.appendChild(card);
      });
    }

    function login(s) {
      // 轮询状态放在一个对象里传给 poll()：以前 pollTimer/stopped 是 login() 的局部变量，
      // 而 poll() 是模块级函数，运行到 setInterval 那一行就 ReferenceError（pollTimer is not defined），
      // 表现成「二维码获取失败」——其实码已经拿到了，只是没人去轮询。
      var ctl = { timer: null, stopped: false, key: '' };
      function stopPoll() {
        ctl.stopped = true;
        if (ctl.timer) {
          clearInterval(ctl.timer);
          ctl.timer = null;
        }
      }

      var sheet = UI.openSheet({
        title: '登录' + (NAMES[s.id] || s.name || s.id),
        sub: '两种方式：扫码，或粘贴 Cookie',
        onClose: stopPoll,
        render: function (ctx) {
          var wrap = el('div', { class: 'col', style: { padding: '0 18px 18px', gap: '14px' } });

          var qrBox = el('div', { class: 'qr-box' }, [
            el('div', { class: 'qr-placeholder', text: '二维码' }),
            el('div', { class: 'qr-scan-line' })
          ]);
          wrap.appendChild(qrBox);

          var status = el('div', { class: 'search-status', text: '正在获取二维码…' });
          wrap.appendChild(status);

          var cookieInput = el('input', { class: 'field__input', placeholder: '或粘贴 Cookie（MUSIC_U=...）' });
          wrap.appendChild(
            el('div', { class: 'field' }, [
              el('label', { class: 'field__label', text: 'Cookie 登录' }),
              cookieInput
            ])
          );

          wrap.appendChild(
            el('button', {
              class: 'btn btn--primary btn--block',
              onclick: function () {
                var c = cookieInput.value.trim();
                if (!c) {
                  UI.toast('请先粘贴 Cookie');
                  return;
                }
                B.call('login', { source: s.id, cookie: c })
                  .then(function () {
                    UI.toast('登录成功', 'success');
                    s.loggedIn = true;
                    paint();
                    ctx.close();
                  })
                  .catch(function (e) {
                    UI.toastError('登录失败：' + e.message);
                  });
              }
            }, [el('span', { text: '用 Cookie 登录' })])
          );

          B.call('login', { source: s.id })
            .then(function (d) {
              if (d && d.qr) {
                ctl.key = d.qr.key || '';
                var img = d.qr.image || d.qr.url || '';
                if (img && img.indexOf('data:image') === 0) {
                  // 上游只给 unikey（网易）时图片由网关现画，浏览器直接当 <img> 用
                  var holder = qrBox.querySelector('.qr-placeholder');
                  if (holder) holder.parentNode.removeChild(holder);
                  var im = el('img', { class: 'qr-img', src: img, alt: '登录二维码' });
                  qrBox.appendChild(im);
                }
                qrBox.classList.add('is-ready');
                status.textContent = '请用 ' + (NAMES[s.id] || s.id) + ' App 扫码，完成后自动生效';
                poll(s, ctx, status, ctl);
              } else if (d && d.cookie) {
                s.loggedIn = true;
                paint();
                UI.toast('已用已保存的登录态', 'success');
                ctx.close();
              }
            })
            .catch(function (e) {
              status.textContent = '二维码获取失败：' + e.message + '（可直接粘贴 Cookie）';
            });

          return wrap;
        }
      });
      return sheet;
    }

    function poll(s, ctx, status, ctl) {
      var tries = 0;
      ctl.timer = setInterval(function () {
        if (ctl.stopped) return;
        tries++;
        if (tries > 100) {
          ctl.stopped = true;
          if (ctl.timer) clearInterval(ctl.timer);
          status.textContent = '二维码已过期，请重新打开';
          return;
        }
        B.call('loginQrPoll', { source: s.id, key: ctl.key })
          .then(function (d) {
            if (ctl.stopped) return;
            if (d && d.ok) {
              ctl.stopped = true;
              if (ctl.timer) clearInterval(ctl.timer);
              s.loggedIn = true;
              paint();
              UI.toast('登录成功', 'success');
              ctx.close();
            } else if (d && d.scanned) {
              status.textContent = '已扫码，请在手机上确认';
            }
          })
          .catch(function () {});
      }, 1500);
    }

    function logout(s) {
      UI.confirm({ title: '退出登录', message: '退出 ' + (NAMES[s.id] || s.id) + ' 后会员曲目将回退到会员分发。', okText: '退出' }).then(function (ok) {
        if (!ok) return;
        B.call('logout', { source: s.id })
          .then(function () {
            s.loggedIn = false;
            paint();
            UI.toast('已退出登录');
          })
          .catch(function (e) { UI.toastError(e.message); });
      });
    }

    scroll.appendChild(body);
    page.appendChild(header);
    page.appendChild(scroll);

    // 法律条款：正文是 assets/web/legal 下的独立 HTML，用系统浏览器/内置浏览页打开。
    // 不走 router（它们不是应用内的「页」，退回时不该进栈）。
    function openLegal(file, title) {
      B.call('openUrl', { url: 'file:///android_asset/web/legal/' + file, title: title })
        .catch(function (e) { UI.toastError('打开失败：' + (e && e.message ? e.message : '未知错误')); });
    }

    var legal = el('div', { class: 'legal-links' }, [
      el('button', { class: 'legal-links__item', onclick: function () { openLegal('terms.html', '用户协议'); } }, [
        el('span', { text: '用户协议' })
      ]),
      el('button', { class: 'legal-links__item', onclick: function () { openLegal('privacy.html', '隐私政策'); } }, [
        el('span', { text: '隐私政策' })
      ]),
      el('button', { class: 'legal-links__item', onclick: function () { openLegal('licenses.html', '第三方组件与开源许可'); } }, [
        el('span', { text: '第三方组件与开源许可' })
      ])
    ]);
    body.appendChild(legal);
    body.appendChild(
      el('div', { class: 'legal-links__foot', text: 'J4Music ' + ((S.state.ping && S.state.ping.appVersion) || '1.0.0') + ' · 数据只留在本机' })
    );

    paint();
    B.call('sourceList')
      .then(function (d) {
        if (d && d.list) {
          S.setState({ sources: d.list });
          paint();
        }
      })
      .catch(function (e) { B.log('w', 'sourceList ' + e.message); });

    return page;
  }

  R.register('login', { title: '账号与会员', render: render });
})(window);
