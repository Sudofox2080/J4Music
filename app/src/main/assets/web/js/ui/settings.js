/* J4Music · ui/settings.js —— 设置 Tab
 * 主题切换 / 音质 / 缓存管理 / 睡眠定时 / 播放行为 / 关于
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

  var THEMES = [
    { id: 'aurora', name: 'Aurora', desc: '紫粉 · 默认' },
    { id: 'deep', name: 'Deep', desc: '青蓝 · 冷静' },
    { id: 'sunset', name: 'Sunset', desc: '橙红 · 热烈' }
  ];
  var QUALITIES = [
    { id: 'standard', name: '标准 128k' },
    { id: 'high', name: '高品 320k' },
    { id: 'lossless', name: '无损 FLAC' },
    { id: 'hires', name: 'Hi-Res' }
  ];
  var SLEEP = [
    { id: 0, name: '关闭' },
    { id: 15, name: '15 分钟' },
    { id: 30, name: '30 分钟' },
    { id: 60, name: '60 分钟' },
    { id: 90, name: '90 分钟' }
  ];

  var sleepTimer = { handle: null, endAt: 0 };

  /** 令牌/地址打码：只留头尾，避免截图泄露 */
  function mask(s) {
    var v = String(s || '');
    if (v.length <= 8) return v;
    return v.slice(0, 4) + '…' + v.slice(-4);
  }

  function themeGrid() {
    var cur = (S.state.settings && S.state.settings.theme) || 'aurora';
    var grid = el('div', { class: 'theme-grid' });
    THEMES.forEach(function (t) {
      var card = el('button', { class: 'theme-card theme-card--' + t.id + (cur === t.id ? ' is-active' : ''), dataset: { theme: t.id } }, [
        el('span', { class: 'theme-card__swatch' }),
        el('span', { class: 'theme-card__name', text: t.name }),
        el('span', { class: 'theme-card__desc', text: t.desc }),
        cur === t.id ? el('span', { class: 'theme-card__check', html: I.check }) : null
      ]);
      card.addEventListener('click', function () {
        applyTheme(t.id);
      });
      grid.appendChild(card);
    });
    return grid;
  }

  function applyTheme(id) {
    document.documentElement.setAttribute('data-theme', id);
    var s = Object.assign({}, S.state.settings || {}, { theme: id });
    S.setState({ settings: s });
    B.call('settingsSet', { key: 'theme', value: id }).catch(function (e) {
      B.log('w', 'settingsSet theme ' + e.message);
    });
    document.querySelectorAll('.theme-card').forEach(function (c) {
      c.classList.toggle('is-active', c.getAttribute('data-theme') === id || c.dataset.theme === id);
    });
    // 重绘选中勾
    var grid = document.querySelector('.theme-grid');
    if (grid) {
      grid.querySelectorAll('.theme-card__check').forEach(function (n) { n.remove(); });
      var act = grid.querySelector('.theme-card.is-active');
      if (act) act.appendChild(el('span', { class: 'theme-card__check', html: I.check }));
    }
    UI.toast('主题：' + id, 'info', 1000);
  }

  function group(title) {
    return el('div', { class: 'setting-group' }, [el('div', { class: 'setting-group__title', text: title })]);
  }

  function settingRow(opts) {
    var row = el('div', { class: 'setting-row', onclick: function (e) { opts.onClick && opts.onClick(e); } }, [
      opts.icon ? el('span', { class: 'setting-row__icon', html: opts.icon }) : null,
      el('div', { class: 'setting-row__label' }, [
        el('div', { class: 'setting-row__title', text: opts.label }),
        opts.sub ? el('div', { class: 'setting-row__sub', text: opts.sub }) : null
      ]),
      el('div', { class: 'setting-row__value', text: opts.value || '' }),
      opts.arrow ? el('span', { class: 'cell__arrow', html: I.chevron }) : null
    ]);
    return row;
  }

  function pickList(title, items, cur, onPick) {
    UI.actionSheet(
      items.map(function (it) {
        return {
          label: it.name + (String(it.id) === String(cur) ? '  ✓' : ''),
          act: function () { onPick(it.id, it); }
        };
      }),
      { title: title }
    );
  }

  function render() {
    var page = el('div', { class: 'page page--tab' });
    var scroll = el('div', { class: 'page__scroll' });
    var inner = el('div', { class: 'page-pad' });

    inner.appendChild(
      el('div', { class: 'page-header' }, [
        el('h1', { text: '设置' }),
        // 只在 mock（浏览器预览、没有原生桥）时才显示这个标记；
        // 真机上它是纯噪音，还会被 flex 拉伸到标题旁边看着像错字。
        S.state.bridgeMock
          ? el('span', { class: 'mono', style: { fontSize: '11px', color: 'var(--text-3)' }, text: 'MOCK' })
          : null
      ])
    );

    // 主题
    var tg = group('主题');
    tg.appendChild(el('div', { style: { padding: '0 0 12px' } }, [themeGrid()]));
    inner.appendChild(tg);

    // 播放与音质
    var pg = group('播放');
    var st = S.state.settings || {};
    var qName = (QUALITIES.filter(function (q) { return q.id === st.quality; })[0] || QUALITIES[1]).name;
    pg.appendChild(
      settingRow({
        icon: I.equalizer,
        label: '音质选择',
        sub: '无损需要会员音源',
        value: qName,
        arrow: true,
        onClick: function () {
          pickList('音质', QUALITIES, st.quality || 'high', function (id) {
            var s = Object.assign({}, S.state.settings || {}, { quality: id });
            S.setState({ settings: s });
            B.call('settingsSet', { key: 'quality', value: id }).catch(function () {});
            rerender();
            UI.toast('音质：' + (QUALITIES.filter(function (q) { return q.id === id; })[0] || {}).name, 'info');
          });
        }
      })
    );
    pg.appendChild(
      settingRow({
        icon: I.list,
        label: '默认循环模式',
        value: ({ list: '列表循环', one: '单曲循环', shuffle: '随机' })[S.state.loopMode] || '列表循环',
        arrow: true,
        onClick: function () {
          S.emit('loop:cycle', {});
          if (global.J4Player && global.J4Player.LOOP_LABEL) {
            var cur = S.state.loopMode;
            var order = ['list', 'one', 'shuffle'];
            var nx = order[(order.indexOf(cur) + 1) % 3];
            B.call('loopMode', { mode: nx }).then(function (d) {
              S.setState({ loopMode: (d && d.mode) || nx });
              rerender();
            }).catch(function () {});
          }
        }
      })
    );
    pg.appendChild(
      settingRow({
        icon: I.volume,
        label: '播放页背景动效',
        value: '开',
        onClick: function (e) {
          var root = document.documentElement;
          var off = root.classList.toggle('no-fx');
          e.currentTarget.querySelector('.setting-row__value').textContent = off ? '关' : '开';
          UI.toast(off ? '已关闭背景动效' : '已开启动效', 'info', 1000);
        }
      })
    );
    inner.appendChild(pg);

    // 下载与缓存
    var cg = group('存储');
    var cacheSize = st.cacheSize || 0;
    var cacheLimit = st.cacheLimit || 1024 * 1024 * 1024;
    var bar = el('div', { class: 'cache-bar' }, [el('i', { style: { transform: 'scaleX(' + Math.min(1, cacheSize / cacheLimit) + ')' } })]);
    var cacheRow = settingRow({
      icon: I.cloud,
      label: '缓存管理',
      sub: D.fmtSize(cacheSize) + ' / ' + D.fmtSize(cacheLimit) + ' · ' + (st.downloadDir || '默认目录'),
      value: '清理',
      arrow: true,
      onClick: function () {
        UI.confirm({ title: '清理缓存', message: '将清除已下载与缓存的音频文件，歌单保留。', okText: '清理', danger: true }).then(function (ok) {
          if (!ok) return;
          B.call('settingsSet', { key: 'cacheClear', value: true })
            .then(function () {
              var s = Object.assign({}, S.state.settings || {}, { cacheSize: 0 });
              S.setState({ settings: s });
              bar.firstChild.style.transform = 'scaleX(0)';
              UI.toast('缓存已清理', 'success');
            })
            .catch(function (e) { UI.toastError(e.message); });
        });
      }
    });
    cg.appendChild(cacheRow);
    cg.appendChild(el('div', { style: { padding: '0 18px 12px' } }, [bar]));
    cg.appendChild(
      settingRow({
        icon: I.download,
        label: '下载目录',
        sub: st.downloadDir || '/storage/emulated/0/Android/data/.../files/Music',
        value: '更换',
        arrow: true,
        onClick: function () {
          UI.prompt({
            title: '下载目录',
            value: st.downloadDir || '',
            placeholder: '/storage/emulated/0/Music/J4Music',
            onSubmit: function (v) {
              var s = Object.assign({}, S.state.settings || {}, { downloadDir: v });
              S.setState({ settings: s });
              return B.call('settingsSet', { key: 'downloadDir', value: v }).then(function () {
                UI.toast('已保存');
                rerender();
              });
            }
          });
        }
      })
    );
    inner.appendChild(cg);

    // 睡眠定时
    var sg = group('睡眠定时');
    sg.appendChild(
      settingRow({
        icon: I.timer,
        label: '睡眠定时',
        sub: sleepTimer.endAt ? '将在 ' + D.fmtTime(Math.max(0, sleepTimer.endAt - Date.now())) + ' 后暂停' : '到点自动暂停播放',
        value: sleepTimer.endAt ? '计时中' : '关闭',
        arrow: true,
        onClick: function () {
          var cur = sleepTimer.minutes || 0;
          pickList('睡眠定时', SLEEP, cur, function (id) {
            setSleep(id);
            rerender();
          });
        }
      })
    );
    inner.appendChild(sg);

    // 云端（分发会员）
    // 这一组的核心是「服务器上的号」而不是「手机上的号」：
    // 用户在云端登录一次，本机所有 VIP 曲目取址都会带这个会员态。
    var ug = group('云端 · 分发会员');
    ug.appendChild(
      settingRow({
        icon: I.cloud,
        label: '上游服务地址',
        sub: st.upstreamBase || '未配置',
        value: st.upstreamEnabled ? '已连' : '未连',
        arrow: true,
        onClick: function () {
          UI.prompt({
            title: '上游服务地址',
            value: st.upstreamBase || '',
            placeholder: 'http://你的服务器:8080',
            onSubmit: function (v) {
              var s = Object.assign({}, S.state.settings || {}, { upstreamBase: v });
              S.setState({ settings: s });
              return B.call('settingsSet', { key: 'upstreamBase', value: v }).then(function () {
                UI.toast('已保存');
                rerender();
              });
            }
          });
        }
      })
    );
    ug.appendChild(
      settingRow({
        icon: I.lock,
        label: '上游访问令牌',
        sub: st.upstreamToken ? '已设置（' + mask(st.upstreamToken) + '）' : '未设置',
        value: '更换',
        arrow: true,
        onClick: function () {
          UI.prompt({
            title: '上游访问令牌',
            value: '',
            placeholder: 'X-J4-Token 的值',
            onSubmit: function (v) {
              return B.call('settingsSet', { key: 'upstreamToken', value: v }).then(function () {
                UI.toast('已保存');
                rerender();
              });
            }
          });
        }
      })
    );
    var healthRow = settingRow({
      icon: I.wave,
      label: '测试云端连通',
      sub: '检查四家音源在服务器上是否可达',
      value: '测试',
      arrow: true,
      onClick: function () {
        healthRow.querySelector('.setting-row__value').textContent = '测试中…';
        B.call('upstreamHealth')
          .then(function (d) {
            healthRow.querySelector('.setting-row__value').textContent = '测试';
            UI.actionSheet([{ label: '知道了' }], { title: '云端连通性', sub: d.text || '' });
          })
          .catch(function (e) {
            healthRow.querySelector('.setting-row__value').textContent = '测试';
            UI.toastError('测试失败：' + e.message);
          });
      }
    });
    ug.appendChild(healthRow);
    ug.appendChild(
      settingRow({
        icon: I.login,
        label: '云端账号登录',
        sub: '网页控制台里扫码或粘贴 Cookie，登一次全端生效',
        value: '去登录',
        arrow: true,
        onClick: function () { R.push('cloud'); }
      })
    );
    // 网页控制台：扫码 / 粘贴 Cookie 都在浏览器里完成，服务器记住登录态，
    // 手机端就不用再登一次 —— 这就是「分发会员」的入口。
    ug.appendChild(
      settingRow({
        icon: I.qr,
        label: '打开网页控制台',
        sub: st.upstreamBase ? st.upstreamBase : '先填上游服务地址',
        value: '打开',
        arrow: true,
        onClick: function () {
          B.call('authWebLogin', {})
            .then(function (d) {
              if (d && d.opened) UI.toast('已在浏览器打开控制台', 'info', 2200);
            })
            .catch(function (e) { UI.toastError(e, { key: 'authWebLogin' }); });
        }
      })
    );
    inner.appendChild(ug);

    // 其它入口
    var og = group('其它');
    og.appendChild(settingRow({ icon: I.login, label: '账号与会员', sub: '自行登录会员 / 服务器分发', value: '管理', arrow: true, onClick: function () { R.push('login', {}); } }));
    og.appendChild(settingRow({ icon: I.audio, label: '本地音乐扫描', value: '去扫描', arrow: true, onClick: function () { R.push('local'); } }));
    og.appendChild(settingRow({ icon: I.translate, label: '音乐转译（.ncm 等）', value: '打开', arrow: true, onClick: function () { R.push('translate'); } }));
    // 自检：.ncm / .qmc / .qy2 的解密逻辑有几处极易写错（XOR 下标、zlib 头、TEA 跨块混写），
    // 给用户一个能自己确认「是这个版本的问题还是文件的问题」的入口。
    og.appendChild(
      settingRow({
        icon: I.audio,
        label: '解码器自检',
        sub: '确认加密格式解密逻辑在本机是否正常',
        value: '运行',
        arrow: true,
        onClick: function () {
          UI.toast('正在自检…', 'info', 1200);
          B.call('cryptoSelfTest')
            .then(function (d) {
              var list = (d && d.list) || [];
              var bad = list.filter(function (x) { return !x.ok; });
              var sub = (d ? d.pass + '/' + d.total + ' 通过' : '未知') +
                (bad.length ? '\n\n失败项：\n' + bad.map(function (x) { return '· ' + x.name + ' — ' + (x.error || ''); }).join('\n') : '');
              UI.actionSheet([{ label: '知道了' }], { title: '解码器自检', sub: sub });
            })
            .catch(function (e) { UI.toastError(e, { key: 'cryptoSelfTest' }); });
        }
      })
    );
    og.appendChild(settingRow({ icon: I.info, label: '使用说明', sub: '设置在哪、Cookie 怎么拿、连不上怎么办', value: '查看', arrow: true, onClick: function () { R.push('help', {}); } }));
    og.appendChild(
      settingRow({
        icon: I.info,
        label: '关于 J4Music',
        sub: 'WebView 前端 · 离线可用',
        value: 'v1.0.0',
        onClick: function () {
          B.call('ping').then(function (d) {
            UI.actionSheet([{ label: '关闭' }], { title: 'J4Music', sub: '原生版本 ' + ((d && d.appVersion) || 'unknown') });
          });
        }
      })
    );
    inner.appendChild(og);

    scroll.appendChild(inner);
    page.appendChild(scroll);
    return page;
  }

  function setSleep(minutes) {
    sleepTimer.minutes = minutes;
    if (sleepTimer.handle) {
      clearTimeout(sleepTimer.handle);
      sleepTimer.handle = null;
    }
    if (!minutes) {
      sleepTimer.endAt = 0;
      UI.toast('睡眠定时已关闭');
      return;
    }
    sleepTimer.endAt = Date.now() + minutes * 60000;
    sleepTimer.handle = setTimeout(function () {
      if (S.state.playing) B.call('pause').catch(function () {});
      S.setState({ playing: false });
      UI.toast('睡眠定时结束，已暂停', 'info', 3000);
      sleepTimer.endAt = 0;
      sleepTimer.handle = null;
      rerender();
    }, minutes * 60000);
    UI.toast(minutes + ' 分钟后暂停播放', 'info');
  }

  function rerender() {
    // settings 也是 Tab 页，R.top() 恒为 null；用 force 重建当前 Tab
    if (R.tab === 'settings') R.switchTab('settings', { force: true });
  }

  R.register('settings', {
    title: '设置',
    render: render,
    enter: function () {
      B.call('settingsGet')
        .then(function (d) {
          if (!d) return;
          var s = Object.assign({}, S.state.settings || {}, d);
          S.setState({ settings: s });
          if (d.theme) document.documentElement.setAttribute('data-theme', d.theme);
        })
        .catch(function (e) { B.log('w', 'settingsGet ' + e.message); });
    }
  });

  global.J4Settings = { applyTheme: applyTheme, setSleep: setSleep, THEMES: THEMES, QUALITIES: QUALITIES };
})(window);
