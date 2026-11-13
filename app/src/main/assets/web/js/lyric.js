/* J4Music · lyric.js
 * 歌词渲染与动效。
 *  - 有 rich（逐字时间）→ 逐字高亮；否则按行。
 *  - 当前行放大 1.12 + 加粗 + 渐变文字（background-clip:text）。
 *  - 上下行模糊 + 降透明度，transform/filter/opacity 过渡。
 *  - 点击任意行 → bridge.seek({ms})，并乐观地把该行设为当前行。
 *  - 翻译行小字跟随；无歌词时音符点阵空态。
 *  - 滚动区域用 mask-image 上下渐隐（在 pages.css 里）。
 *
 * 全局：window.J4Lyric
 */
(function (global) {
  'use strict';

  var D = global.J4DOM;
  var B = global.J4Bridge;
  var S = global.J4Store;
  var el = D.el;

  /**
   * 逐字高亮（卡拉 OK 那种「一个字一个字亮过来」）开关。
   * 用户明确要求去掉这个效果，只保留整行高亮，所以这里关掉；
   * 逐字时间轴仍然会解析并保存在 lines[i].chars 里，改回 true 即可复原。
   */
  var RICH_ENABLED = false;

  var state = {
    host: null,
    scroll: null,
    lines: [],
    rich: false,
    current: -1,
    songId: null,
    userScrolling: false,
    userScrollTimer: null,
    programmatic: false,
    rafPending: false,
    lastPos: 0
  };

  // ---------- LRC 解析 ----------
  var TIME_RE = /\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]/g;

  function parseLrc(text) {
    if (!text) return [];
    var out = [];
    var rows = String(text).split(/\r?\n/);
    for (var i = 0; i < rows.length; i++) {
      var row = rows[i];
      var times = [];
      var m;
      TIME_RE.lastIndex = 0;
      while ((m = TIME_RE.exec(row))) {
        var min = parseInt(m[1], 10);
        var sec = parseInt(m[2], 10);
        var frac = m[3] ? parseInt((m[3] + '00').slice(0, 3), 10) : 0;
        times.push(min * 60000 + sec * 1000 + frac);
      }
      var content = row.replace(TIME_RE, '').trim();
      for (var t = 0; t < times.length; t++) {
        out.push({ ms: times[t], text: content, tr: '', chars: null });
      }
    }
    out.sort(function (a, b) {
      return a.ms - b.ms;
    });
    return out;
  }

  // 合并翻译（按时间近似匹配）
  function mergeTranslation(lines, trText) {
    var trs = parseLrc(trText);
    if (!trs.length) return lines;
    var j = 0;
    for (var i = 0; i < lines.length; i++) {
      while (j < trs.length - 1 && Math.abs(trs[j + 1].ms - lines[i].ms) < Math.abs(trs[j].ms - lines[i].ms)) j++;
      if (Math.abs(trs[j].ms - lines[i].ms) < 600) lines[i].tr = trs[j].text;
    }
    return lines;
  }

  // ---------- 构建 ----------
  function mount(hostEl) {
    state.host = hostEl;
    hostEl.innerHTML = '';
    state.scroll = el('div', { class: 'lyric-scroll' });
    hostEl.appendChild(state.scroll);
    state.scroll.addEventListener('scroll', onUserScroll, { passive: true });
    return state.scroll;
  }

  function onUserScroll() {
    if (state.programmatic) return;
    state.userScrolling = true;
    clearTimeout(state.userScrollTimer);
    state.userScrollTimer = setTimeout(function () {
      state.userScrolling = false;
      scrollToLine(state.current, true);
    }, 2600);
  }

  function renderLines() {
    var scroll = state.scroll;
    if (!scroll) return;
    scroll.innerHTML = '';
    if (!state.lines.length) {
      scroll.parentNode && state.host && state.host.querySelectorAll('.lyric-empty').forEach(function (n) {
        n.remove();
      });
      if (state.host && !state.host.querySelector('.lyric-empty')) {
        state.host.appendChild(
          el('div', { class: 'lyric-empty anim-fade-in' }, [
            el(
              'div',
              { class: 'note-dots note-dots--big' },
              [0, 1, 2, 3, 4, 5, 6].map(function (i) {
                return el('i', { style: { 'animation-delay': i * 0.1 + 's' } });
              })
            ),
            el('div', { text: '暂无歌词' }),
            el('div', { class: 't3', style: { 'font-size': '11.5px' }, text: '试试在设置里切换音源或重新获取' })
          ])
        );
      }
      return;
    }
    var emptyNode = state.host && state.host.querySelector('.lyric-empty');
    if (emptyNode) emptyNode.remove();

    var frag = document.createDocumentFragment();
    for (var i = 0; i < state.lines.length; i++) {
      var line = state.lines[i];
      var node = el('div', {
        class: 'lyric-line',
        dataset: { index: String(i), ms: String(line.ms) }
      });
      var main = el('span', { class: 'lyric-main' });
      if (state.rich && line.chars && line.chars.length) {
        node.classList.add('is-rich');
        for (var c = 0; c < line.chars.length; c++) {
          var ch = el('span', { class: 'ch', text: line.chars[c].c });
          ch.__t = line.chars[c].t;
          ch.style.animationDelay = Math.min(c * 12, 260) + 'ms';
          main.appendChild(ch);
        }
      } else {
        main.textContent = line.text || '♪';
      }
      node.appendChild(main);
      if (line.tr) node.appendChild(el('span', { class: 'lyric-tr', text: line.tr }));
      node.addEventListener('click', makeTap(i, line));
      frag.appendChild(node);
    }
    scroll.appendChild(frag);
    state.current = -1;
    paintClasses(-1);
  }

  var tapGuard = 0;
  function makeTap(i, line) {
    return function () {
      var now = Date.now();
      if (now - tapGuard < 220) return;
      tapGuard = now;
      // 乐观更新：立刻把该行设为当前
      setCurrent(i, true);
      scrollToLine(i, true);
      // 通知原生 seek
      B.call('seek', { ms: line.ms }).catch(function (e) {
        B.log('e', 'seek failed ' + e.message);
      });
      S.setState({ positionMs: line.ms });
      if (global.J4Player) global.J4Player.hintPosition(line.ms);
      if (global.navigator && navigator.vibrate) {
        try {
          navigator.vibrate(8);
        } catch (e) {
          /* ignore */
        }
      }
    };
  }

  // ---------- 当前行样式 ----------
  function paintClasses(cur) {
    var scroll = state.scroll;
    if (!scroll) return;
    var nodes = scroll.children;
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      var d = i - cur;
      n.classList.remove('is-current', 'is-prev', 'is-next', 'is-far');
      if (cur < 0) {
        if (Math.abs(d) > 2) n.classList.add('is-far');
        continue;
      }
      if (d === 0) n.classList.add('is-current');
      else if (d === -1) n.classList.add('is-prev');
      else if (d === 1) n.classList.add('is-next');
      else if (Math.abs(d) > 2) n.classList.add('is-far');
    }
  }

  function setCurrent(index, force) {
    if (!force && index === state.current) return;
    state.current = index;
    paintClasses(index);
    S.setState({ lyricIndex: index });
  }

  function scrollToLine(index, force) {
    var scroll = state.scroll;
    if (!scroll || index < 0) return;
    var node = scroll.children[index];
    if (!node) return;
    if (!force && state.userScrolling) return;
    var target = node.offsetTop - scroll.clientHeight / 2 + node.offsetHeight / 2;
    state.programmatic = true;
    scroll.scrollTo({ top: Math.max(0, target), behavior: force ? 'auto' : 'smooth' });
    // scrollTo 不支持 behavior 时兜底
    if (Math.abs(scroll.scrollTop - target) > 2) {
      requestAnimationFrame(function () {
        state.programmatic = false;
      });
    }
    setTimeout(function () {
      state.programmatic = false;
    }, force ? 40 : 420);
  }

  // ---------- 毫秒定位 ----------
  function indexAt(ms) {
    var lines = state.lines;
    if (!lines.length) return -1;
    var lo = 0;
    var hi = lines.length - 1;
    var res = 0;
    while (lo <= hi) {
      var mid = (lo + hi) >> 1;
      if (lines[mid].ms <= ms) {
        res = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    return res;
  }

  var lastTick = 0;
  function tick(positionMs) {
    if (!state.lines.length) return;
    // 节流：60ms 一次足够，且只在跨行时重绘
    var now = Date.now();
    if (now - lastTick < 50 && Math.abs(positionMs - state.lastPos) < 120) return;
    lastTick = now;
    state.lastPos = positionMs;
    var idx = indexAt(positionMs);
    if (idx !== state.current) {
      setCurrent(idx);
      scrollToLine(idx, false);
    }
    if (state.rich) paintChars(idx, positionMs);
  }

  function paintChars(index, ms) {
    var scroll = state.scroll;
    if (!scroll) return;
    var node = scroll.children[index];
    if (!node) return;
    var chs = node.querySelectorAll('.ch');
    for (var i = 0; i < chs.length; i++) {
      var on = chs[i].__t <= ms;
      if (on !== chs[i].classList.contains('is-on')) chs[i].classList.toggle('is-on', on);
    }
  }

  // ---------- 对外 API ----------
  function load(song) {
    if (!song || !song.id) {
      state.lines = [];
      state.songId = null;
      renderLines();
      return Promise.resolve();
    }
    state.songId = song.id;
    S.setState({ lyricSongId: song.id, lyricLoading: true });
    if (state.scroll) state.scroll.innerHTML = '<div class="lyric-line is-far" style="text-align:center">歌词加载中…</div>';
    // 同时带上完整 song 对象：只给 songId 时原生侧要回队列里找，
    // 找不到（比如刚点播放还没入队）就只能按 id 重建，会丢掉 extra 里的音源信息。
    return B.call('lyric', { song: song, songId: song.id })
      .then(function (d) {
        if (state.songId !== song.id) return; // 已切歌
        var lines = [];
        var rich = false;
        if (d && Array.isArray(d.rich) && d.rich.length) {
          rich = true;
          lines = d.rich
            .map(function (l) {
              var ms = l.ms != null ? l.ms : l.t != null ? l.t : 0;
              var text = l.text != null ? l.text : l.tx != null ? l.tx : '';
              var chars = Array.isArray(l.chars) ? l.chars.map(function (c) { return { c: c.c != null ? c.c : c.text, t: c.t || c.ms || ms }; }) : null;
              return { ms: ms, text: text, tr: l.tr || '', chars: chars, dur: l.dur || 0 };
            })
            .sort(function (a, b) {
              return a.ms - b.ms;
            });
          if (d.translated && lines.every(function (l) { return !l.tr; })) {
            mergeTranslation(lines, d.translated);
          }
        } else {
          lines = parseLrc(d && d.lrc);
          if (d && d.translated) mergeTranslation(lines, d.translated);
        }
        state.lines = lines;
        // 逐字（卡拉 OK）高亮按用户要求关掉，只保留整行高亮。
        // 想恢复：把 RICH_ENABLED 改成 true，lines 里的 chars 一直都在。
        state.rich = rich && RICH_ENABLED;
        S.setState({ lyricLines: lines, lyricRich: state.rich, lyricLoading: false });
        renderLines();
        var pos = global.J4Player ? global.J4Player.position() : S.state.positionMs;
        var idx = indexAt(pos || 0);
        if (idx >= 0) {
          setCurrent(idx, true);
          requestAnimationFrame(function () {
            scrollToLine(idx, true);
          });
        }
      })
      .catch(function (e) {
        B.log('w', 'lyric load failed: ' + e.message);
        state.lines = [];
        S.setState({ lyricLines: [], lyricLoading: false });
        renderLines();
      });
  }

  function reset() {
    state.lines = [];
    state.rich = false;
    state.current = -1;
    if (state.scroll) state.scroll.innerHTML = '';
    renderLines();
  }

  function currentIndex() {
    return state.current;
  }

  global.J4Lyric = {
    mount: mount,
    load: load,
    reset: reset,
    tick: tick,
    indexAt: indexAt,
    parseLrc: parseLrc,
    currentIndex: currentIndex,
    lines: function () {
      return state.lines;
    }
  };
})(window);
