/* J4Music · app.js —— 启动、Tab 栏、全局事件绑定
 * 1) 恢复设置（主题）  2) 初始化路由/播放器/队列/歌词  3) 加载歌单与音源
 * 4) 实现 window.J4.onPlayer 接收原生事件  5) 处理安卓返回键
 */
(function (global) {
  'use strict';

  var D = global.J4DOM;
  var I = global.J4Icons;
  var B = global.J4Bridge;
  var S = global.J4Store;
  var UI = global.J4UI;
  var R = global.J4Router;
  var P = global.J4Player;
  var Q = global.J4Queue;
  var L = global.J4Lyric;
  var el = D.el;

  var root = null;
  var tabbar = null;

  // J4 命名空间必须在模块顶层就建好。
  // 之前它写在 boot() 里面，而下面的 global.J4.onBack 是顶层立即执行的，
  // 于是启动时先撞上 "Cannot set properties of undefined (setting 'onBack')"，
  // 整个 app.js 后面的顶层代码（包括 global.J4App）全部没执行 —— 表现为黑屏。
  global.J4 = global.J4 || {};

  var TAB_DEFS = [
    { id: 'home', label: '首页', icon: I.home },
    { id: 'discover', label: '发现', icon: I.compass },
    { id: 'library', label: '我的', icon: I.library },
    { id: 'settings', label: '设置', icon: I.settings }
  ];

  function buildTabbar() {
    var bar = el('nav', { class: 'tabbar' });
    TAB_DEFS.forEach(function (t) {
      var btn = el('button', { class: 'tabbar__item', dataset: { tab: t.id } }, [
        el('span', { class: 'tabbar__icon', html: t.icon }),
        el('span', { class: 'tabbar__label', text: t.label })
      ]);
      btn.addEventListener('click', function () {
        if (R.tab === t.id) {
          var sc = document.querySelector('.page--tab.is-active .page__scroll');
          if (sc) sc.scrollTo({ top: 0, behavior: 'smooth' });
          return;
        }
        R.switchTab(t.id);
      });
      bar.appendChild(btn);
    });
    return bar;
  }

  function syncTabbar(tab) {
    if (!tabbar) return;
    tabbar.querySelectorAll('.tabbar__item').forEach(function (b) {
      b.classList.toggle('is-active', b.dataset.tab === tab);
    });
  }

  // ---------------- 全局事件绑定 ----------------
  function bindGlobalEvents() {
    // 收藏：设置/取消（存到原生 KV，重启不丢，也能整份上传成云歌单）
    S.on('song:like', function (song) {
      if (!song) return;
      B.call('favoritesToggle', { song: song })
        .then(function (d) {
          var fav = Object.assign({}, S.state.favorites || {});
          if (d && d.liked) fav[song.id] = song;
          else delete fav[song.id];
          S.setState({ favorites: fav });
          P.refresh();
          UI.toast(d && d.liked ? '已收藏 ♥' : '已取消收藏', d && d.liked ? 'success' : 'info');
          S.emit('favorites:changed', { liked: !!(d && d.liked), song: song });
        })
        .catch(function (e) { UI.toastError(e.message); });
    });

    // 加入歌单
    S.on('song:add-to-playlist', function (song) {
      if (!song) return;
      var pls = S.state.playlists || [];
      if (!pls.length) {
        UI.prompt({
          title: '新建歌单',
          placeholder: '歌单名称',
          okText: '创建并加入',
          onSubmit: function (name) {
            if (!name || !name.trim()) throw new Error('名称不能为空');
            return B.call('playlistCreate', { name: name.trim() }).then(function (d) {
              return B.call('playlistAddSongs', { id: d.id, songs: [song] }).then(function () {
                UI.toast('已加入「' + name.trim() + '」', 'success');
                S.emit('playlists:changed', {});
              });
            });
          }
        });
        return;
      }
      UI.actionSheet(
        pls.map(function (p) {
          return {
            label: p.name + '（' + (p.songCount || 0) + '）',
            act: function () {
              B.call('playlistAddSongs', { id: p.id, songs: [song] })
                .then(function () {
                  UI.toast('已加入「' + p.name + '」', 'success');
                  S.emit('playlists:changed', {});
                })
                .catch(function (e) { UI.toastError(e); });
            }
          };
        }).concat([{ label: '新建歌单…', act: function () {
          S.emit('song:add-to-playlist', song); // 会再次进入空列表分支
        } }]),
        { title: '加入歌单', sub: song.title }
      );
    });

    // 下一首播放
    S.on('song:play-next', function (song) {
      if (!song) return;
      var q = S.state.queue.slice();
      var at = Math.min(q.length, (S.state.index || 0) + 1);
      q.splice(at, 0, song);
      Q.set(q, S.state.index, S.state.playlistId).then(function () {
        UI.toast('已设为下一首：' + song.title, 'success');
      }).catch(function () {});
    });

    // 下载
    S.on('song:download', function (song) {
      if (!song) return;
      UI.toast('已加入下载：' + song.title, 'info', 1200);
      B.call('download', { song: song })
        .then(function () {
          return B.call('downloadList');
        })
        .then(function (d) {
          S.setState({ downloads: (d && d.list) || [] });
        })
        .catch(function (e) {
          UI.toastError('下载失败：' + (e && e.message ? e.message : ''));
        });
    });

    S.on('song:mark-download', function (song) {
      S.emit('song:download', song);
    });

    // 由本地/下载列表创建歌单
    S.on('playlist:create-from-local', function (songs) {
      createPlaylistFrom(songs, '本地歌单');
    });
    S.on('download:to-playlist', function () {
      var done = (S.state.downloads || []).filter(function (t) { return t.state === 'done'; });
      if (!done.length) {
        UI.toast('没有已完成的下载');
        return;
      }
      B.call('localScan').then(function (d) {
        var songs = ((d && d.songs) || []).filter(function (s) {
          return done.some(function (t) { return t.songId === s.id || t.title === s.title; });
        });
        createPlaylistFrom(songs.length ? songs : (d.songs || []), '下载歌单');
      }).catch(function (e) { UI.toastError(e); });
    });
    S.on('download:clear-done', function () {
      var list = (S.state.downloads || []).filter(function (t) { return t.state !== 'done'; });
      S.setState({ downloads: list });
      UI.toast('已清除完成项');
    });

    S.on('playlist:save-queue', function (p) {
      if (!p.songs || !p.songs.length) {
        UI.toast('队列是空的');
        return;
      }
      createPlaylistFrom(p.songs, '队列歌单');
    });

    S.on('playlists:import', function () {
      // 两条路都要给：手机上手边没有可粘贴的文本时，选文件才是常态；
      // 而 .j4.json 动辄上百 KB，让人整段粘贴进输入框本身就不现实。
      UI.actionSheet(
        [
          {
            label: '选择文件（.json / .m3u / .txt）',
            icon: I.folder || '',
            onSelect: function () { importFromFile(); }
          },
          {
            label: '粘贴 J4 歌单文本',
            icon: I.edit || '',
            onSelect: function () { importFromText(); }
          }
        ],
        { title: '导入歌单', sub: '支持 J4 分享文本、M3U、CSV、TXT' }
      );
    });

    function importFromFile() {
      UI.toast('正在读取文件…', 'info', 1200);
      return B.call('pickPlaylist', {}).then(function (d) {
        if (d && d.cancelled) return;
        UI.toast('已导入「' + (d.name || '') + '」' + (d.success ? '，' + d.success + ' 首' : ''), 'success', 2400);
        S.emit('playlists:changed', {});
        if (d.id) R.push('playlist', { id: d.id });
      });
    }

    function importFromText() {
      UI.prompt({
        title: '导入歌单',
        sub: '粘贴 J4 歌单分享文本',
        multiline: true,
        okText: '导入',
        onSubmit: function (text) {
          if (!text || !text.trim()) throw new Error('内容为空');
          return B.call('playlistImport', { text: text.trim() }).then(function (d) {
            UI.toast('已导入「' + (d.name || '') + '」', 'success');
            S.emit('playlists:changed', {});
            if (d.id) R.push('playlist', { id: d.id });
          });
        }
      });
    }

    S.on('playlists:sync', function (pl) {
      if (!pl) return;
      // 这里做的是「导出成一份可粘贴的歌单文本」，不是把歌单写到别的软件的账号里 ——
      // 以前文案写成“同步到云端”，用户会以为对面真的收到了。
      UI.toast('正在生成「' + pl.name + '」的分享文本…', 'info', 1200);
      B.call('playlistExport', { id: pl.id })
        .then(function (d) {
          if (global.J4Lib) global.J4Lib.copyText((d && d.text) || '');
          UI.toast('已复制歌单文本，粘到别的音乐 App 的导入入口即可', 'success', 2600);
        })
        .catch(function (e) { UI.toastError(e); });
    });

    S.on('song:more', function (p) {
      var song = p.song;
      UI.openMenu(
        [
          { icon: I.plus, label: '加入歌单', act: function () { S.emit('song:add-to-playlist', song); } },
          { icon: I.heart, label: '收藏', act: function () { S.emit('song:like', song); } },
          { icon: I.download, label: '下载', act: function () { S.emit('song:download', song); } },
          { icon: I.equalizer, label: '查看歌词', act: function () { P.open(); } }
        ],
        p.event
      );
    });
  }

  function createPlaylistFrom(songs, baseName) {
    UI.prompt({
      title: '存为新歌单',
      value: baseName + ' ' + new Date().toISOString().slice(5, 10).replace('-', '/'),
      okText: '创建',
      onSubmit: function (name) {
        if (!name || !name.trim()) throw new Error('名称不能为空');
        return B.call('playlistCreate', { name: name.trim() }).then(function (d) {
          return B.call('playlistAddSongs', { id: d.id, songs: songs }).then(function (r) {
            UI.toast('已创建「' + name.trim() + '」' + ((r && r.added) || songs.length) + ' 首', 'success');
            S.emit('playlists:changed', {});
            return d.id;
          });
        });
      }
    });
  }

  // ---------------- 数据加载 ----------------
  function loadPlaylists() {
    return B.call('playlistList')
      .then(function (d) {
        S.setState({ playlists: (d && d.list) || [] });
        return S.state.playlists;
      })
      .catch(function (e) {
        B.log('w', 'playlistList ' + e.message);
        return [];
      });
  }

  /** 收藏现在由原生 KV 管，启动时拉一次，界面上的红心才和库里一致 */
  function loadFavorites() {
    return B.call('favoritesGet')
      .then(function (d) {
        var map = {};
        ((d && d.songs) || []).forEach(function (s) { map[s.id] = s; });
        S.setState({ favorites: map });
      })
      .catch(function (e) {
        B.log('w', 'favoritesGet ' + e.message);
      });
  }

  function loadSources() {
    return B.call('sourceList')
      .then(function (d) {
        S.setState({ sources: (d && d.list) || [] });
      })
      .catch(function (e) {
        B.log('w', 'sourceList ' + e.message);
      });
  }

  function loadSettings() {
    return B.call('settingsGet')
      .then(function (d) {
        var s = Object.assign({}, S.state.settings || {}, d || {});
        S.setState({ settings: s });
        if (s.theme) document.documentElement.setAttribute('data-theme', s.theme);
      })
      .catch(function (e) {
        B.log('w', 'settingsGet ' + e.message);
        var saved = null;
        try {
          saved = localStorage.getItem('j4.theme');
        } catch (err) {}
        if (saved) document.documentElement.setAttribute('data-theme', saved);
      });
  }

  // ---------------- 启动 ----------------
  function boot() {
    root = document.getElementById('app');
    if (!root) {
      root = document.createElement('div');
      root.id = 'app';
      document.body.appendChild(root);
    }

    // 主题：先尽量同步设置，避免闪烁
    try {
      var t = localStorage.getItem('j4.theme');
      if (t) document.documentElement.setAttribute('data-theme', t);
    } catch (e) {}

    // 页面宿主 + Tab 栏
    var host = el('div', { class: 'page-host' });
    root.appendChild(host);
    tabbar = buildTabbar();
    root.appendChild(tabbar);

    // 状态提示（mock）
    if (B.mock) {
      root.classList.add('is-mock');
      console.log('[J4Music] mock mode: window.J4Native 不存在，使用内置假数据');
    }

    // 接收原生事件
    global.J4 = global.J4 || {};
    global.J4.onPlayer = function (ev) {
      try {
        P.onNative(ev);
      } catch (e) {
        B.log('e', 'onPlayer failed: ' + e.message);
      }
    };
    global.J4.onEvent = global.J4.onPlayer;

    // 路由
    R.init(host);
    // router 没有 onChange 钩子：它只在 switchTab 时 emit('tab:changed', name)，
    // push/back 通过 store 的 stack 字段变化体现，这里统一订阅。
    S.on('tab:changed', function (name) {
      syncTabbar(name);
      root.classList.toggle('is-push', false);
    });
    S.subscribe(function () {
      var st = S.state.stack || [];
      root.classList.toggle('is-push', st.length > 0);
      var tab = R.tab || S.state.tab;
      if (tab) syncTabbar(tab);
    });

    // 玩家 / 队列 / 歌词
    P.init(root);
    Q.init();
    bindGlobalEvents();

    loadSettings();
    loadPlaylists();
    loadFavorites();
    loadSources();

    R.switchTab('home');

    // 设置变更时写回
    S.subscribe(function () {
      if (S.state.settings && S.state.settings.theme) {
        try {
          localStorage.setItem('j4.theme', S.state.settings.theme);
        } catch (e) {}
      }
    });

    S.setState({ ready: true });
    document.body.classList.add('is-ready');
  }

  // 安卓返回键：原生可调用 window.J4.onBack()
  global.addEventListener('j4:back', function () {
    handleBack();
  });

  function handleBack() {
    if (P.isOpen()) {
      P.close();
      return true;
    }
    if (UI.closeTopOverlay()) return true;
    // depth() 是页面栈长度：tab 页时是 0，push 过一页是 1。
    // 原来写的是 > 1，于是「打开搜索页后按返回」被判成没东西可退，
    // 前端回 false、原生直接 finish —— 表现就是按返回一下把整个 App 关掉。
    if (R.depth() >= 1) {
      // back() 现在会回布尔值：动画还在跑（或宿主没就绪）时为 false，
      // 这时不能把这次返回算作已消费，否则返回键会被吃掉、页面却不动。
      return R.back() === true;
    }
    return false;
  }

  // ------------------------------------------------------------------ 返回键结算
  // 原生 onBackPressed 必须**当场**决定退不退，而从 Kotlin 同步取走 JS 的返回值做不到
  // （evaluateJavascript 天生异步，主线程等它必死锁）。所以反过来：JS 每当「能不能返回」
  // 可能变化时，就用同步桥把结论推给 Kotlin，那边只读缓存。
  var lastBackClaimable = null;

  function canGoBack() {
    try {
      if (P.isOpen && P.isOpen()) return true;
      if (UI && UI.hasOverlay && UI.hasOverlay()) return true;
      if (R.depth() >= 1) return true;
    } catch (e) {
      return false;
    }
    return false;
  }

  function syncBackState(force) {
    var v = canGoBack();
    if (!force && v === lastBackClaimable) return;
    lastBackClaimable = v;
    try {
      if (global.J4Bridge && global.J4Bridge.call) {
        global.J4Bridge.call('backState', { claimable: v });
      }
    } catch (e) {
      // 预览环境没有原生桥，忽略即可
    }
  }

  // 任何可能改变「可返回性」的动作之后都结算一次。
  var backSyncTimer = 0;
  function scheduleBackSync() {
    if (backSyncTimer) return;
    backSyncTimer = setTimeout(function () {
      backSyncTimer = 0;
      syncBackState(false);
    }, 60);
  }

  global.addEventListener('j4:route', scheduleBackSync);
  global.addEventListener('j4:overlay', scheduleBackSync);
  global.J4.syncBackState = syncBackState;

  // 返回 true 表示这次返回已被前端消费；原生走的是 consumeBack() 的缓存位，
  // 这里保留返回值是为了兼容直接调用（例如 j4:back 事件、调试探针）。
  global.J4.onBack = function () {
    var claimed = handleBack() === true;
    if (claimed) syncBackState(false);
    return claimed;
  };

  // 启动后补一次，保证第一次按返回时缓存位是准的。
  setTimeout(function () { syncBackState(true); }, 300);

  // ------------------------------------------------------------------ 事件总线
  // 原生侧的异步推送（搜索进度、歌词、扫码轮询、下载/转译进度）都从这里过一遍。
  // 之前这里只有 J4Bus.emit 而没有 J4Bus 本体，任何一次原生事件都会在这行抛
  // ReferenceError 并被 bridge 的 try/catch 吞掉，等于事件全丢；所以总线必须先于分发定义。
  var busHandlers = {};
  var J4Bus = {
    on: function (type, fn) {
      if (typeof fn !== 'function') return function () {};
      (busHandlers[type] = busHandlers[type] || []).push(fn);
      return function () { J4Bus.off(type, fn); };
    },
    once: function (type, fn) {
      var off = J4Bus.on(type, function (ev) { off(); fn(ev); });
      return off;
    },
    off: function (type, fn) {
      var list = busHandlers[type];
      if (!list) return;
      if (!fn) { delete busHandlers[type]; return; }
      var i = list.indexOf(fn);
      if (i >= 0) list.splice(i, 1);
    },
    emit: function (type, payload) {
      var list = (busHandlers[type] || []).slice();
      var any = (busHandlers['*'] || []).slice();
      for (var i = 0; i < list.length; i++) {
        try { list[i](payload); } catch (e) { if (global.console) console.error('[J4Bus]', type, e); }
      }
      for (var j = 0; j < any.length; j++) {
        try { any[j](type, payload); } catch (e) { if (global.console) console.error('[J4Bus]', e); }
      }
    }
  };
  global.J4Bus = J4Bus;

  // 业务事件的全局分发口（bridge.js 的 __J4NativeEvent 调用它）。
  // 各页面模块可以用 J4Bus.on(type, fn) 订阅，typ 为 search / lyric / songUrl / download
  // / transcodeProgress / syncProgress / error 等。这里只做「广播」，不关心谁在听。
  //
  // ---------------------------------------------------------------- error 事件
  // 这里是「首页每日推荐刷屏」的现场：原生在解析音源时会抛
  //   MediaItemFactory.kt:49  "暂无可用音源：${song.title}"
  // 并以 type=error 推回 JS，而这里无条件 UI.toast(..., 'error')。列表里每一首解析
  // 失败都来一条，重复渲染就重复来一遍 —— 屏幕底部就成了红色跑马灯。
  //
  // 现在的分流规则：
  //   • 用户在等结果（刚刚点过播放/切歌/下载）→ 弹，且同一 key 60s 内只弹一次；
  //   • 后台批量预取（列表渲染、队列预取、自动下一首）→ 一律不弹，只 console.warn；
  //   • 谁都没订阅的杂项错误 → console.warn 留痕，不打扰用户。
  // 判断依据是「等待窗口」：主动操作会先 J4App.expectError() 打开窗口，
  // 真正消费到错误就用掉；超时没人来就自然过期。
  var pendingErrors = Object.create(null); // key -> 过期时间戳
  var PENDING_ERROR_TTL = 8000;

  function expectError(key, ttlMs) {
    if (!key) return;
    pendingErrors[String(key)] = Date.now() + (ttlMs || PENDING_ERROR_TTL);
  }

  function consumeError(key) {
    if (!key) return false;
    var k = String(key);
    var until = pendingErrors[k];
    if (!until) return false;
    delete pendingErrors[k];
    return until > Date.now();
  }

  function songKeyOf(ev) {
    var d = (ev && ev.data) || ev || {};
    var song = d.song || null;
    var id = d.songId || (song && song.id) || (ev && ev.songId) || '';
    return id ? String(id) : '';
  }

  function errorText(ev) {
    var d = (ev && ev.data) || ev || {};
    return String(d.message || (d.error && d.error.message) || d.error || '');
  }

  // 判定一条 error 是否属于「后台批量路径」
  function isBatchError(ev) {
    var d = (ev && ev.data) || ev || {};
    var phase = String(d.phase || d.stage || '').toLowerCase();
    if (phase === 'prefetch' || phase === 'render' || phase === 'list' || phase === 'batch') return true;
    if (d.prefetch === true || d.batch === true || d.auto === true || d.background === true) return true;
    if (d.userInitiated === false) return true;
    return false;
  }

  // 跨源兜底的说明文案。
  // 场景：用户点的是会员曲目，服务端取不到正式地址，改从别家
  // 拿同一首的免费版本（常见是 Live / 翻唱）。不说明的话，用户会以为
  // 「我明明点的是原版」，这也是「歌曲和听到的不一样」的另一种形态。
  var SWITCH_LABEL = { netease: '网易云', qq: 'QQ 音乐', local: '本地' };
  var lastSwitchNotice = Object.create(null); // songId -> 时间戳，避免同一首反复提示

  // 平台的搜索接口返回的标题常把歌手拼在后面（QQ 是「发如雪 - 周杰伦 / 李安」，
  // 平台也常这样），直接塞进提示会变成《发如雪 - 周杰伦 / 李安》这种怪话。
  // 只取 " - " 前面那段，并且只有当它确实还包含原曲名时才这么截。
  function cleanSwitchTitle(raw, originTitle) {
    var s = String(raw || '').trim();
    var dash = s.indexOf(' - ');
    if (dash <= 0) return s;
    var head = s.slice(0, dash).trim();
    if (!head) return s;
    var key = String(originTitle || '').trim();
    // 归一化比较：去掉括号里的版本后缀再比，避免「晴天 (Live) - 周杰伦」被退回整串
    var norm = function (x) { return x.replace(/[（(\[【].*?[)）\]】]/g, '').replace(/\s+/g, ''); };
    if (!key || norm(head).indexOf(norm(key).slice(0, 2)) >= 0) return head;
    return s;
  }

  function onSourceSwitch(ev) {
    var d = (ev && ev.data) || {};
    var via = String(d.via || '');
    if (!via) return;
    var id = String(d.songId || '');
    var now = Date.now();
    if (id && lastSwitchNotice[id] && now - lastSwitchNotice[id] < 120000) return;
    if (id) lastSwitchNotice[id] = now;
    var from = SWITCH_LABEL[String(d.origin || '')] || String(d.origin || '原平台');
    var to = SWITCH_LABEL[via] || via;
    var title = String(d.title || '');
    var got = cleanSwitchTitle(d.viaTitle, title);
    var msg;
    var sameVendor = String(d.origin || '') === via;
    if (sameVendor) {
      // 同一家平台内部换版本：别写成「已改用 QQ 音乐的免费音源」，那样像是换了平台
      msg = from + '的《' + title + '》拿不到正式版，已改用同平台的' + (got && got !== title ? '《' + got + '》' : '免费版本');
    } else if (got && title && got !== title) {
      msg = from + '的《' + title + '》要会员，已改播' + to + '的《' + got + '》';
    } else {
      msg = from + '的《' + title + '》要会员，已改用' + to + '的免费音源';
    }
    if (d.preview === true) msg += '（试听片段）';
    if (UI && UI.toast) UI.toast(msg, 'info', 3200, 'switch::' + id);
    J4Bus.emit('sourceSwitch', ev);
  }

  function onNativeEvent(ev) {
    var t = ev && ev.type;
    if (!t) return;
    if (t === 'sourceSwitch') {
      onSourceSwitch(ev);
      return;
    }
    if (t === 'error') {
      var text = errorText(ev);
      var songKey = songKeyOf(ev);
      var kind = isBatchError(ev) ? 'batch' : 'unknown';
      if (kind === 'unknown' && songKey) {
        // 不知道来路时，看有没有人正在等这条错误：有 → 用户主动；没有 → 后台
        kind = consumeError(songKey) ? 'user' : 'batch';
      }
      if (kind === 'batch') {
        // 列表渲染 / 批量预取的取址失败：静默，只在控制台留痕
        if (global.console && console.warn) {
          console.warn('[J4Music] 取址失败（后台路径，已静默）' + (songKey ? ' song=' + songKey : '') + '：' + (text || '未知错误'));
        }
      } else {
        // 用户主动点击触发的失败：弹一条，同一 song id 60s 内最多一次
        if (UI && UI.toastError) {
          UI.toastError(text || '播放失败', { key: songKey ? 'id::' + songKey : undefined });
        } else if (UI && UI.toast) {
          UI.toast(text || '播放失败', 'error');
        }
      }
      J4Bus.emit('error', ev);
      // 顺手把过期窗口清掉，避免长时间运行后对象变长
      var now = Date.now();
      for (var pk in pendingErrors) {
        if (Object.prototype.hasOwnProperty.call(pendingErrors, pk) && pendingErrors[pk] < now) delete pendingErrors[pk];
      }
      return;
    }
    J4Bus.emit(t, ev);
  }
  global.J4.onNativeEvent = onNativeEvent;
  global.J4.onEvent = onNativeEvent;

  // 分享进 App：直接落到搜索页，把文本作为关键词
  global.J4.onShare = function (text) {
    var kw = String(text || '').trim();
    if (!kw) return;
    R.switchTab('search');
    if (global.J4UI && global.J4UI.search && global.J4UI.search.setKeyword) {
      global.J4UI.search.setKeyword(kw);
      global.J4UI.search.run(kw);
    } else {
      S.setState({ pendingSearch: kw });
    }
  };

  global.J4App = {
    boot: boot,
    handleBack: handleBack,
    onNativeEvent: onNativeEvent,
    // 供 player.js / 各页面在「用户主动操作」时开一个错误等待窗口。
    // 窗口内同一首歌的 error 事件会弹出 toast（并做 60s 去重），
    // 窗口外的一律视为后台批量失败 → 只 console.warn。
    expectError: expectError,
    consumeError: consumeError,
    songKeyOf: songKeyOf,
    isBatchError: isBatchError,
    // 便捷入口：包一层主动操作，成功后窗口自然过期
    allowError: function (songId, ttlMs) {
      var id = songId && songId.id ? songId.id : songId;
      if (id === null || id === undefined || id === '') return;
      expectError(String(id), ttlMs);
    }
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})(window);
