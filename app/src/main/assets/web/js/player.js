/* J4Music · player.js
 * 播放器：迷你播放条 + 全屏播放页 + 进度拖动 + 循环模式 + 播放队列。
 * 原生 → JS 事件：window.J4.onPlayer(ev)（由 app.js 定义，这里通过 J4Player.onNative 接收）
 * ev.type: state | queue | error | translated
 *
 * 全局：window.J4Player
 */
(function (global) {
  'use strict';

  var D = global.J4DOM;
  var I = global.J4Icons;
  var B = global.J4Bridge;
  var S = global.J4Store;
  var UI = global.J4UI;
  var el = D.el;

  var LOOP_ORDER = ['list', 'one', 'shuffle'];
  var LOOP_LABEL = { list: '列表循环', one: '单曲循环', shuffle: '随机播放' };
  var LOOP_ICON = { list: I.loopList, one: I.loopOne, shuffle: I.shuffle };

  var local = {
    positionMs: 0,
    durationMs: 0,
    dragging: false,
    dragMs: 0,
    pageOpen: false,
    mounted: false,
    lastSongId: null,
    pendingSeek: null
  };

  var refs = {};

  // ---------------- 迷你播放条 ----------------
  function buildMiniBar() {
    var bar = el('div', { class: 'minibar', id: 'miniBar' }, [
      el('div', { class: 'minibar__progress' }),
      el('div', { class: 'minibar__cover' }, [el('span', { class: 'cover__ph', text: '♪' })]),
      el('div', { class: 'minibar__meta' }, [
        el('div', { class: 'minibar__title', text: '未在播放' }),
        el('div', { class: 'minibar__sub', text: '选一首歌开始' })
      ]),
      el('div', { class: 'minibar__btns' }, [
        el('button', {
          class: 'ibtn',
          'aria-label': '播放/暂停',
          html: I.play,
          dataset: { act: 'toggle' },
          onclick: function (e) {
            e.stopPropagation();
            toggle();
          }
        }),
        el('button', {
          class: 'ibtn',
          'aria-label': '下一首',
          html: I.next,
          dataset: { act: 'next' },
          onclick: function (e) {
            e.stopPropagation();
            next();
          }
        })
      ]),
      el('div', { class: 'minibar__line' }, [el('i')])
    ]);
    bar.addEventListener('click', open);
    // 手势：上滑打开播放页
    var sy = 0;
    bar.addEventListener(
      'touchstart',
      function (e) {
        sy = e.touches[0].clientY;
      },
      { passive: true }
    );
    bar.addEventListener(
      'touchend',
      function (e) {
        var dy = sy - (e.changedTouches[0] ? e.changedTouches[0].clientY : sy);
        if (dy > 26) open();
      },
      { passive: true }
    );
    return bar;
  }

  // ---------------- 播放页 ----------------
  function buildPage() {
    var bg = el('div', { class: 'player-bg' }, [
      el('div', { class: 'player-bg__cover' }),
      el('div', { class: 'player-bg__blob player-bg__blob--a' }),
      el('div', { class: 'player-bg__blob player-bg__blob--b' }),
      el('div', { class: 'player-bg__veil' })
    ]);

    var top = el('div', { class: 'player-top' }, [
      el('button', {
        class: 'ibtn',
        'aria-label': '收起',
        html: I.down,
        onclick: function () {
          close();
        }
      }),
      el('div', { class: 'player-top__label' }, [
        el('b', { text: 'Now Playing' }),
        el('span', { text: 'J4Music' })
      ]),
      el('button', {
        class: 'ibtn',
        'aria-label': '更多',
        html: I.moreV,
        onclick: function (e) {
          var song = currentSong();
          if (!song) return;
          S.emit('song:more', { song: song, event: e });
        }
      })
    ]);

    var disc = el('div', { class: 'disc' }, [
      el('div', { class: 'disc__glow' }),
      el('div', { class: 'disc__art' }, [el('span', { class: 'cover__ph', text: '♪' })]),
      el('div', { class: 'disc__hole' })
    ]);

    var discWrap = el('div', { class: 'disc-wrap' }, [disc]);

    var titleRow = el('div', { class: 'player-title-row' }, [
      el('div', { class: 'meta' }, [
        el('div', { class: 'title', text: '未在播放' }),
        el('div', { class: 'artist', text: '—' }),
        // 实际音质徽标：开了会员要能看见「我拿到的是无损/320k」，
        // 不然屏幕上跟没开会员一模一样，用户当然要问「会员有什么意义」。
        el('div', { class: 'badge badge--quality', text: '' })
      ]),
      el('button', {
        class: 'ibtn',
        'aria-label': '收藏',
        html: I.heart,
        dataset: { act: 'fav' },
        onclick: function () {
          var song = currentSong();
          if (!song) return;
          S.emit('song:like', song);
        }
      })
    ]);

    var lyricWrap = el('div', { class: 'lyric-wrap' });
    var body = el('div', { class: 'player-body' }, [top, discWrap, titleRow, lyricWrap]);

    var curLabel = el('span', { class: 'mono', text: '0:00' });
    var durLabel = el('span', { class: 'mono', text: '0:00' });

    var slider = el('div', { class: 'slider', id: 'playSlider' }, [
      el('div', { class: 'slider__track' }, [el('div', { class: 'slider__fill' })]),
      el('div', { class: 'slider__thumb' })
    ]);

    var loopBtn = el('button', {
      class: 'ibtn ibtn--loop',
      'aria-label': '循环模式',
      html: LOOP_ICON.list,
      dataset: { act: 'loop' },
      onclick: function () {
        cycleLoop();
      }
    });

    var controlRow = el('div', { class: 'player-actions' }, [
      loopBtn,
      el('button', {
        class: 'ibtn',
        'aria-label': '上一首',
        html: I.prev,
        onclick: function () {
          prev();
        }
      }),
      el('button', {
        class: 'play-fab',
        'aria-label': '播放/暂停',
        html: I.play,
        dataset: { act: 'toggle' },
        onclick: function () {
          toggle();
        }
      }),
      el('button', {
        class: 'ibtn',
        'aria-label': '下一首',
        html: I.next,
        onclick: function () {
          next();
        }
      }),
      el('button', {
        class: 'ibtn',
        'aria-label': '播放列表',
        html: I.list,
        onclick: function () {
          openQueue();
        }
      })
    ]);

    var progressRow = el('div', { class: 'progress-row' }, [curLabel, slider, durLabel]);
    var controls = el('div', { class: 'player-controls' }, [
      el('div', { class: 'player-hint', text: '点击歌词跳转 · 上滑查看队列' }),
      progressRow,
      controlRow
    ]);

    var page = el('div', { class: 'player-page', id: 'playerPage' }, [bg, body, controls]);

    refs.page = page;
    refs.bgCover = bg.querySelector('.player-bg__cover');
    refs.disc = disc;
    refs.discArt = disc.querySelector('.disc__art');
    refs.title = titleRow.querySelector('.title');
    refs.artist = titleRow.querySelector('.artist');
    refs.quality = titleRow.querySelector('.badge--quality');
    refs.favBtn = titleRow.querySelector('[data-act="fav"]');
    refs.lyricWrap = lyricWrap;
    refs.slider = slider;
    refs.fill = slider.querySelector('.slider__fill');
    refs.thumb = slider.querySelector('.slider__thumb');
    refs.curLabel = curLabel;
    refs.durLabel = durLabel;
    refs.loopBtn = loopBtn;
    refs.fab = controlRow.querySelector('.play-fab');
    refs.fabIcon = refs.fab;

    bindSlider(slider);

    // 播放页下滑关闭
    var startY = 0;
    var dragging = false;
    page.addEventListener(
      'touchstart',
      function (e) {
        if (e.target.closest('.slider, .lyric-scroll')) return;
        startY = e.touches[0].clientY;
        dragging = true;
      },
      { passive: true }
    );
    page.addEventListener(
      'touchend',
      function (e) {
        if (!dragging) return;
        dragging = false;
        var y = e.changedTouches[0] ? e.changedTouches[0].clientY : startY;
        if (y - startY > 120 && e.target.closest('.player-top, .disc-wrap, .player-title-row')) close();
      },
      { passive: true }
    );

    return page;
  }

  function bindSlider(slider) {
    var rect = null;
    function ratioFromEvent(e) {
      var x = (e.touches ? e.touches[0].clientX : e.clientX) - rect.left;
      return Math.max(0, Math.min(1, x / rect.width));
    }
    function start(e) {
      rect = slider.getBoundingClientRect();
      local.dragging = true;
      slider.classList.add('is-active');
      local.dragMs = ratioFromEvent(e) * local.durationMs;
      paintProgress(local.dragMs, local.durationMs);
    }
    function move(e) {
      if (!local.dragging) return;
      e.preventDefault();
      local.dragMs = ratioFromEvent(e) * local.durationMs;
      paintProgress(local.dragMs, local.durationMs);
    }
    function end() {
      if (!local.dragging) return;
      local.dragging = false;
      slider.classList.remove('is-active');
      var ms = Math.round(local.dragMs);
      local.positionMs = ms;
      if (global.J4Lyric) global.J4Lyric.tick(ms);
      B.call('seek', { ms: ms }).catch(function (e) {
        B.log('w', 'seek failed ' + e.message);
      });
    }

    slider.addEventListener('pointerdown', start);
    // 兼容不支持 pointer 的 WebView
    slider.addEventListener('mousedown', function (e) {
      if (window.PointerEvent) return;
      start(e);
      var up = function () {
        document.removeEventListener('mousemove', mv);
        document.removeEventListener('mouseup', up);
        end();
      };
      var mv = function (ev) {
        move(ev);
      };
      document.addEventListener('mousemove', mv);
      document.addEventListener('mouseup', up);
    });
    slider.addEventListener(
      'touchstart',
      function (e) {
        if (window.PointerEvent) return;
        start(e);
      },
      { passive: true }
    );
    slider.addEventListener(
      'touchmove',
      function (e) {
        if (window.PointerEvent) return;
        move(e);
      },
      { passive: false }
    );
    slider.addEventListener(
      'touchend',
      function () {
        if (window.PointerEvent) return;
        end();
      },
      { passive: true }
    );

    if (window.PointerEvent) {
      slider.addEventListener('pointermove', move);
      slider.addEventListener('pointerup', end);
      slider.addEventListener('pointercancel', end);
    }
  }

  function paintProgress(ms, dur) {
    var r = dur > 0 ? Math.max(0, Math.min(1, ms / dur)) : 0;
    if (refs.fill) refs.fill.style.transform = 'scaleX(' + r + ')';
    if (refs.fill) refs.fill.style.width = '100%';
    if (refs.thumb) refs.thumb.style.left = r * 100 + '%';
    if (refs.curLabel) refs.curLabel.textContent = D.fmtTime(ms);
    if (refs.durLabel) refs.durLabel.textContent = D.fmtTime(dur);
    var mini = refs.miniLine;
    if (mini) mini.style.transform = 'scaleX(' + r + ')';
  }

  // ---------------- 数据同步 ----------------
  function currentSong() {
    var q = S.state.queue;
    var i = S.state.index;
    return q && q[i] ? q[i] : null;
  }

  function position() {
    return local.dragging ? local.dragMs : local.positionMs;
  }

  function hintPosition(ms) {
    local.positionMs = ms;
    paintProgress(ms, local.durationMs);
  }

  function refreshMiniBar() {
    if (!refs.mini) return;
    var song = currentSong();
    var visible = !!song;
    refs.mini.classList.toggle('is-visible', visible);
    refs.mini.classList.toggle('is-hidden', !visible);
    if (!song) return;
    var cover = refs.mini.querySelector('.minibar__cover');
    var ph = cover.querySelector('.cover__ph');
    cover.style.background = D.placeholderGradient(song.id || song.title);
    ph.textContent = D.firstChar(song.title);
    ph.style.background = D.placeholderGradient(song.id || song.title);
    var img = cover.querySelector('img');
    if (img) {
      if (!song.coverUrl) img.remove();
    } else if (song.coverUrl) {
      img = el('img', { src: song.coverUrl, alt: '' });
      img.addEventListener('error', function () {
        img.remove();
      });
      cover.insertBefore(img, ph);
    }
    refs.mini.querySelector('.minibar__title').textContent = song.title || '未知歌曲';
    refs.mini.querySelector('.minibar__sub').textContent = (song.artist || '未知') + (song.vip ? ' · VIP' : '');
    var playing = S.state.playing;
    var btn = refs.mini.querySelector('[data-act="toggle"]');
    if (btn) btn.innerHTML = playing ? I.pause : I.play;
    var eq = refs.mini.querySelector('.minibar__progress');
    if (eq) eq.style.opacity = playing ? '1' : '0.35';
  }

  /*
   * 背景封面：不用 CSS background 直接吃远程地址，因为 background 加载失败没有事件可捕获，
   * 网络断开时会留一块死黑。这里先用 Image 预加载，成功才铺到元素上，失败就退回占位渐变。
   */
  var bgToken = 0;
  var bgApplied = '';   // 已经铺上去的地址：同一首重绘时不必再解一张位图，否则会闪一下
  function applyBgCover(song) {
    if (!refs.bgCover) return;
    var seed = song.id || song.title;
    var key = song.coverUrl || '';
    if (key && key === bgApplied) return;
    if (!key && bgApplied === 'seed:' + seed) return;
    var my = ++bgToken;
    function fallback() {
      if (my !== bgToken) return;
      bgApplied = 'seed:' + seed;
      refs.bgCover.style.backgroundImage = 'none';
      refs.bgCover.style.background = D.placeholderGradient(seed);
    }
    if (!song.coverUrl) {
      fallback();
      return;
    }
    var probe = new Image();
    probe.onload = function () {
      if (my !== bgToken) return;
      bgApplied = key;
      refs.bgCover.style.background = 'url("' + song.coverUrl + '") center / cover no-repeat';
    };
    probe.onerror = fallback;
    probe.src = song.coverUrl;
  }

  /**
   * 音质 → 徽标文案。exhigh 是 QQ 的 320k、网易也叫「极高」，
   * 统一说成 320K，别让用户对着英文猜。
   */
  var QUALITY_LABEL = {
    hires: 'Hi-Res 无损',
    lossless: 'SQ 无损',
    exhigh: 'HQ 320K',
    high: '192K',
    standard: '128K',
    local: '本地文件'
  };

  function paintQuality(song) {
    if (!refs.quality) return;
    var q = song && song.quality ? String(song.quality) : '';
    var label = QUALITY_LABEL[q] || '';
    refs.quality.textContent = label;
    refs.quality.style.display = label ? '' : 'none';
    refs.quality.dataset.q = q;
  }

  function refreshPage() {
    var song = currentSong();
    if (!song) {
      if (refs.title) refs.title.textContent = '未在播放';
      if (refs.artist) refs.artist.textContent = '—';
      paintQuality(null);
      return;
    }
    if (refs.title) refs.title.textContent = song.title || '未知歌曲';
    if (refs.artist) refs.artist.textContent = (song.artist || '未知艺术家') + (song.album ? ' · ' + song.album : '');
    paintQuality(song);
    // 封面
    var art = refs.discArt;
    var ph = art.querySelector('.cover__ph');
    ph.textContent = D.firstChar(song.title);
    ph.style.background = D.placeholderGradient(song.id || song.title);
    var img = art.querySelector('img');
    if (song.coverUrl) {
      if (!img) {
        img = el('img', { src: song.coverUrl, alt: '' });
        img.addEventListener('error', function () {
          img.remove();
          art.classList.remove('has-img');
        });
        img.addEventListener('load', function () {
          art.classList.add('has-img');
        });
        art.insertBefore(img, art.firstChild);
      } else if (img.getAttribute('src') !== song.coverUrl) {
        img.setAttribute('src', song.coverUrl);
      }
      // 已经缓存过的图不会再触发 load 事件，这里补一次判定，避免占位字一直盖着封面
      if (img.complete && img.naturalWidth > 0) art.classList.add('has-img');
    } else if (img) {
      img.remove();
      art.classList.remove('has-img');
    }
    applyBgCover(song);
    if (refs.favBtn) {
      var liked = !!(S.state.favorites && S.state.favorites[song.id]);
      refs.favBtn.innerHTML = liked ? I.heartFill : I.heart;
      refs.favBtn.classList.toggle('ibtn--active', liked);
    }
    // 切歌时拉歌词
    if (local.lastSongId !== song.id) {
      local.lastSongId = song.id;
      local.positionMs = 0;
      S.setState({ currentSong: song, positionMs: 0, durationMs: song.durationMs || 0 });
      if (global.J4Lyric) global.J4Lyric.load(song);
    }
    local.durationMs = S.state.durationMs || song.durationMs || 0;
    paintProgress(position(), local.durationMs);
    setPlayingUI(S.state.playing);
    setLoopUI(S.state.loopMode);
  }

  function setPlayingUI(playing) {
    if (refs.fab) refs.fab.innerHTML = playing ? I.pause : I.play;
    if (refs.fab) refs.fab.classList.toggle('is-paused', !playing);
    if (refs.disc) refs.disc.classList.toggle('is-paused', !playing);
    if (refs.page) refs.page.classList.toggle('is-paused', !playing);
    if (refs.mini) {
      var btn = refs.mini.querySelector('[data-act="toggle"]');
      if (btn) btn.innerHTML = playing ? I.pause : I.play;
    }
  }

  function setLoopUI(mode) {
    if (!refs.loopBtn) return;
    refs.loopBtn.innerHTML = LOOP_ICON[mode] || LOOP_ICON.list;
    refs.loopBtn.classList.toggle('is-loop-one', mode === 'one');
    refs.loopBtn.classList.toggle('ibtn--active', mode !== 'list');
    refs.loopBtn.setAttribute('aria-label', LOOP_LABEL[mode] || '列表循环');
  }

  // ---------------- 控制 ----------------
  function toggle() {
    if (global.J4Bridge.mock && !S.state.queue.length) {
      loadDemoThenPlay();
      return;
    }
    if (S.state.playing) pause();
    else resume();
  }

  function loadDemoThenPlay() {
    B.call('search', { keyword: '夜', source: 'all', offset: 0, limit: 8 })
      .then(function (d) {
        var list = (d && d.list) || [];
        if (!list.length) return;
        playList(list, 0, { title: '为你播放' });
      })
      .catch(function (e) {
        UI.toastError('搜索失败：' + (e && e.message ? e.message : ''), { key: 'demoSearch' });
      });
  }

  function pause() {
    setPlayingUI(false);
    S.setState({ playing: false });
    B.call('pause').catch(function (e) {
      B.log('w', 'pause ' + e.message);
    });
  }

  function resume() {
    setPlayingUI(true);
    S.setState({ playing: true });
    B.call('resume').catch(function (e) {
      B.log('w', 'resume ' + e.message);
    });
  }

  // 切歌护栏：连续误触（或打开页面的幽灵 click）不该连跳好几首
  var skipGuard = 0;
  function skipAllowed() {
    var now = Date.now();
    if (now - skipGuard < 420) return false;
    skipGuard = now;
    return true;
  }

  function next() {
    if (!skipAllowed()) return;
    B.call('next').catch(function (e) {
      B.log('w', 'next ' + e.message);
    });
    if (global.J4Bridge.mock) {
      local.positionMs = 0;
      paintProgress(0, local.durationMs);
    }
  }

  function prev() {
    // 上一首不设护栏：用户点回去通常是明确意图，但同样吃掉页面刚打开时的幽灵点击
    if (Date.now() - openAt < 420) return;
    B.call('prev').catch(function (e) {
      B.log('w', 'prev ' + e.message);
    });
  }

  var openAt = 0;
  // 透明挡板：open() 后短暂吞掉所有点击（幽灵 click 的落点是刚铺开的播放页控件）
  function shieldTaps(ms) {
    if (local.shield) local.shield.remove();
    var s = el('div', { class: 'tap-shield' });
    var kill = function () {
      if (s.parentNode) s.parentNode.removeChild(s);
      if (local.shield === s) local.shield = null;
    };
    s.addEventListener('click', function (e) {
      e.stopPropagation();
      e.preventDefault();
      kill();
    });
    s.addEventListener('touchend', function (e) {
      e.stopPropagation();
      e.preventDefault();
      kill();
    });
    document.body.appendChild(s);
    local.shield = s;
    setTimeout(kill, ms);
  }

  function seek(ms) {
    hintPosition(ms);
    if (global.J4Lyric) global.J4Lyric.tick(ms);
    B.call('seek', { ms: ms }).catch(function () {});
  }

  function cycleLoop() {
    var cur = S.state.loopMode || 'list';
    var nextMode = LOOP_ORDER[(LOOP_ORDER.indexOf(cur) + 1) % LOOP_ORDER.length];
    setLoopUI(nextMode);
    S.setState({ loopMode: nextMode });
    UI.toast(LOOP_LABEL[nextMode], 'info', 1200);
    B.call('loopMode', { mode: nextMode })
      .then(function (d) {
        if (d && d.mode) {
          S.setState({ loopMode: d.mode });
          setLoopUI(d.mode);
        }
      })
      .catch(function (e) {
        UI.toastError('循环模式切换失败：' + (e && e.message ? e.message : ''), { key: 'loopMode' });
      });
  }

  // 播放一个列表（自动 setQueue + play）
  function playList(songs, index, meta) {
    if (!songs || !songs.length) {
      UI.toast('列表是空的');
      return;
    }
    // 越界的 index 会让 native 立刻报错并自动跳过 → 表现成「点一下跳好几首」，
    // 所以这里先夹到合法范围（例如搜索结果还没刷新完就点了一个旧索引）。
    var at = Math.floor(Number(index) || 0);
    if (!isFinite(at) || at < 0) at = 0;
    if (at > songs.length - 1) at = songs.length - 1;
    index = at;
    S.setState({ queue: songs.slice(), index: index, playlistId: (meta && meta.playlistId) || null });
    local.lastSongId = null;
    // 用户点了播放 → 给这首歌开一个 8s 的错误等待窗口。
    // 窗口内 native 推回来的 error 才允许弹 toast（app.js 的 onNativeEvent 判定），
    // 列表渲染/预取阶段的失败没有窗口，一律静默。
    markUserIntent(songs[index || 0]);
    B.call('setQueue', { songs: songs, index: index || 0, playlistId: (meta && meta.playlistId) || null })
      .then(function () {
        return B.call('play', { index: index || 0 });
      })
      .then(function () {
        S.setState({ playing: true, index: index || 0 });
        refreshPage();
        refreshMiniBar();
        setPlayingUI(true);
        if (meta && meta.title) UI.toast('▶ ' + meta.title, 'info', 1400);
      })
      .catch(function (e) {
        // 同一次点击可能同时收到 bridge 的 reject 和 native 的 error 事件，
        // 两条都带同一首歌的 id，靠 toastError 的 id 去重保证只出一条。
        UI.toastError('播放失败：' + (e && e.message ? e.message : ''), { key: errorKeyOf(songs[index || 0]) });
      });
  }

  // 把「这首歌是用户主动要放的」记到 app.js 的等待表里。
  function markUserIntent(song) {
    if (!song || !song.id) return;
    var App = global.J4App;
    if (App && App.expectError) App.expectError(String(song.id), 8000);
  }

  function errorKeyOf(song) {
    var id = song && (song.id || song.songId);
    return id ? 'id::' + id : '播放失败';
  }

  function open() {
    if (!refs.page) return;
    local.pageOpen = true;
    openAt = Date.now();
    refreshPage();
    refs.page.classList.add('is-open');
    if (refs.mini) refs.mini.classList.add('is-hidden');
    // 点迷你条打开播放页时，这一次点击后续派发的 click 会落在刚铺开的播放页上：
    // 迷你条的「下一首」和播放页的「下一首」几乎在同一个坐标，于是「点一下跳好几首」。
    // 用一层透明挡板吃掉打开后 360ms 内的所有点击。
    shieldTaps(360);
    if (global.J4NotifyBackState) global.J4NotifyBackState();
    if (global.J4Lyric) {
      var idx = global.J4Lyric.currentIndex();
      if (idx >= 0) global.J4Lyric.tick(position());
    }
  }

  function close() {
    if (!refs.page) return;
    local.pageOpen = false;
    refs.page.classList.remove('is-open');
    if (refs.mini && S.state.queue.length) refs.mini.classList.remove('is-hidden');
    if (global.J4NotifyBackState) global.J4NotifyBackState();
  }

  function isOpen() {
    return local.pageOpen;
  }

  // ---------------- 队列 sheet ----------------
  function openQueue() {
    var sheet = UI.openSheet({
      title: '播放队列',
      sub: (S.state.queue.length || 0) + ' 首 · ' + (LOOP_LABEL[S.state.loopMode] || ''),
      render: function (ctx) {
        var wrap = el('div', { class: 'col' });
        var head = el('div', { class: 'row', style: { gap: '8px', padding: '4px 18px 10px' } }, [
          el('button', {
            class: 'chip',
            html: '',
            onclick: function () {
              ctx.close();
              S.emit('queue:clear', {});
            }
          }, [el('span', { text: '清空队列' })]),
          el('button', {
            class: 'chip',
            onclick: function () {
              ctx.close();
              S.emit('playlist:save-queue', { songs: S.state.queue.slice() });
            }
          }, [el('span', { text: '存为歌单' })])
        ]);
        wrap.appendChild(head);
        var list = el('div', { class: 'list' });
        S.state.queue.forEach(function (song, i) {
          var row = el('div', { class: 'song-row' + (i === S.state.index ? ' is-current' : ''), dataset: { q: String(i) } }, [
            el('span', { class: 'song-row__idx', text: i === S.state.index && S.state.playing ? '' : String(i + 1).padStart(2, '0') }),
            UI.coverEl(song, 44),
            el('div', { class: 'song-row__body' }, [
              el('div', { class: 'song-row__title' }, [el('span', { class: 'ellipsis', text: song.title }), song.vip ? el('span', { class: 'badge badge--vip', text: 'VIP' }) : null]),
              el('div', { class: 'song-row__sub' }, [el('span', { class: 'ellipsis', text: song.artist || '' })])
            ]),
            el('button', {
              class: 'song-row__more',
              html: I.close,
              'aria-label': '移出队列',
              onclick: function (e) {
                e.stopPropagation();
                S.emit('queue:remove', { index: i });
                ctx.close();
              }
            })
          ]);
          if (i === S.state.index && S.state.playing) {
            row.children[0].appendChild(el('div', { class: 'eq' }, [el('i'), el('i'), el('i'), el('i')]));
          }
          row.addEventListener('click', function () {
            ctx.close();
            B.call('play', { index: i })
              .then(function () {
                S.setState({ index: i, playing: true });
              })
              .catch(function (e) {
                // 队列里点歌也是用户主动行为：开错误窗口，失败给一条（带 id 去重）
                var song = S.state.queue[i];
                markUserIntent(song);
                UI.toastError('播放失败：' + (e && e.message ? e.message : ''), { key: errorKeyOf(song) });
              });
          });
          list.appendChild(row);
        });
        wrap.appendChild(S.state.queue.length ? list : UI.emptyState('队列是空的', '从任意列表点一首歌开始'));
        return wrap;
      }
    });
    return sheet;
  }

  // ---------------- 原生事件 ----------------
  //
  // 原生的 emitEvent 发出来的是四段信封：
  //   { type, req, data:{ ...业务字段... }, ts }
  // 所以业务字段必须从 ev.data 里取。这里做一次摊平：
  // 先把 data 的字段摊到 ev 上（不改 data 本身），再兼容「已经拍平」的旧格式。
  // 之前这里直接读 ev.playing / ev.queue，永远是 undefined，
  // 播放状态和队列就等于从未更新过 —— 进度条不动、切歌不换标题，原因就在这里。
  function flatten(ev) {
    if (!ev || !ev.data || typeof ev.data !== 'object') return ev;
    for (var k in ev.data) {
      if (Object.prototype.hasOwnProperty.call(ev.data, k) && ev[k] === undefined) {
        ev[k] = ev.data[k];
      }
    }
    return ev;
  }

  function onNative(ev) {
    if (!ev || !ev.type) return;
    ev = flatten(ev);
    if (ev.type === 'state') {
      // 原生在播放期间每 250ms 推一次进度，这类包除了 positionMs/durationMs 什么都不变。
      // 如果每个包都 refreshPage()，等于每 250ms 把整个播放页重绘一遍（列表、封面全重排），
      // 既费电又会打断歌词的滚动动画 —— 所以进度包只走「画进度 + 滚歌词」这条轻量路径。
      var progressOnly =
        (typeof ev.playing !== 'boolean' || ev.playing === S.state.playing) &&
        (typeof ev.index !== 'number' || ev.index === S.state.index) &&
        !Array.isArray(ev.queue) && !ev.loopMode && !ev.playlistId;
      var patch = {};
      if (typeof ev.playing === 'boolean') patch.playing = ev.playing;
      if (typeof ev.index === 'number') patch.index = ev.index;
      if (typeof ev.positionMs === 'number') patch.positionMs = ev.positionMs;
      if (typeof ev.durationMs === 'number') patch.durationMs = ev.durationMs;
      if (ev.loopMode) patch.loopMode = ev.loopMode;
      // queue / playlistId 由 stateJson 一并给出，有就收，避免切歌后队列变旧
      if (Array.isArray(ev.queue) && ev.queue.length) patch.queue = ev.queue;
      if (ev.playlistId) patch.playlistId = ev.playlistId;
      S.setState(patch);
      if (typeof ev.positionMs === 'number') local.positionMs = ev.positionMs;
      if (typeof ev.durationMs === 'number') local.durationMs = ev.durationMs;
      if (!progressOnly) {
        refreshPage();
        refreshMiniBar();
      }
      if (global.J4Lyric) global.J4Lyric.tick(local.positionMs);
      if (typeof ev.durationMs === 'number' || typeof ev.positionMs === 'number') {
        paintProgress(local.positionMs, local.durationMs);
      }
    } else if (ev.type === 'queue') {
      var qn = (ev.queue || []).length;
      var qi = Math.floor(Number(ev.index) || 0);
      if (qi < 0) qi = 0;
      if (qn > 0 && qi > qn - 1) qi = qn - 1;
      S.setState({ queue: ev.queue || [], index: qi, queueDirty: (S.state.queueDirty || 0) + 1 });
      refreshPage();
      refreshMiniBar();
      S.emit('queue:changed', ev);
    } else if (ev.type === 'track') {
      // 自动下一首：原生在取到新曲目的地址后会发 track，用它把标题/封面/歌词一起换掉
      if (ev.song) {
        var q = (S.state.queue || []).slice();
        var idx = typeof ev.index === 'number' ? ev.index : S.state.index;
        if (idx >= 0 && idx < q.length) q[idx] = ev.song;
        S.setState({ index: idx, queue: q, playing: true });
        if (global.J4Lyric && global.J4Lyric.load) {
          try { global.J4Lyric.load(ev.song); } catch (e) {}
        }
        refreshPage();
        refreshMiniBar();
      }
    } else if (ev.type === 'error') {
      // 注意：这里的 ev 已经被 flatten 过，ev.message / ev.song 直接可取。
      // 以前这里无条件 UI.toast(…, 'error')，而 app.js 的 onNativeEvent 也会对同一条
      // error 弹一次，于是一次解析失败出现两条一模一样的红条；列表每首失败一次更是刷屏。
      // 现在：后台批量失败静默（只 log），用户主动操作留下的等待窗口内才弹，
      // 且同一首歌 60s 内最多一条（去重表在 ui.js 的 toastError 里）。
      var App = global.J4App;
      var songId = (ev.song && (ev.song.id || ev.song.songId)) || ev.songId;
      var text = ev.message || (ev.error && ev.error.message) || '播放出错';
      if (App && App.isBatchError && App.isBatchError(ev)) {
        B.log('w', '播放错误（后台路径，已静默）：' + text);
      } else {
        var userWaiting = songId && App && App.consumeError ? App.consumeError(String(songId)) : false;
        if (userWaiting) {
          UI.toastError(text, { key: 'id::' + songId });
        } else {
          B.log('w', '播放错误（无用户等待窗口，已静默）：' + text + (songId ? ' song=' + songId : ''));
        }
      }
      // 继续往下抛给订阅方（歌词/页面自绘错误态等），分发由 app.js 负责
    } else if (ev.type === 'translated') {
      UI.toast('转译完成：' + ((ev.list || []).filter(function (x) { return x.ok; }).length) + ' 个文件', 'success');
      S.emit('translate:done', ev);
    }
  }

  // ---------------- 初始化 ----------------
  function init(appRoot) {
    refs.mini = buildMiniBar();
    refs.miniLine = refs.mini.querySelector('.minibar__line i');
    appRoot.appendChild(refs.mini);

    var page = buildPage();
    appRoot.appendChild(page);
    global.J4Lyric.mount(refs.lyricWrap);

    // 订阅 store，刷新 UI
    S.subscribe(function () {
      refreshMiniBar();
      if (local.pageOpen) refreshPage();
      else {
        setPlayingUI(S.state.playing);
        setLoopUI(S.state.loopMode);
      }
    });

    local.mounted = true;
    refreshMiniBar();
    setPlayingUI(false);

    // 启动时同步一次状态
    B.call('getStatus')
      .then(function (st) {
        if (!st) return;
        S.setState({
          playing: !!st.playing,
          index: st.index || 0,
          loopMode: st.loopMode || 'list',
          queue: st.queue || [],
          playlistId: st.playlistId || null,
          durationMs: st.durationMs || 0,
          positionMs: st.positionMs || 0
        });
        local.positionMs = st.positionMs || 0;
        local.durationMs = st.durationMs || 0;
        refreshPage();
        refreshMiniBar();
      })
      .catch(function (e) {
        B.log('w', 'getStatus failed: ' + e.message);
      });

    return refs;
  }

  global.J4Player = {
    init: init,
    onNative: onNative,
    playList: playList,
    open: open,
    close: close,
    isOpen: isOpen,
    toggle: toggle,
    next: next,
    prev: prev,
    seek: seek,
    position: position,
    hintPosition: hintPosition,
    openQueue: openQueue,
    refresh: function () {
      refreshPage();
      refreshMiniBar();
    },
    currentSong: currentSong,
    LOOP_LABEL: LOOP_LABEL
  };
})(window);
