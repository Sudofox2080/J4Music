/* J4Music · ui.js
 * 通用 UI 原语：toast / bottom sheet / 操作菜单 / 弹窗输入 / 封面元素 /
 * 歌曲行 / 歌单卡 / 滑动手势。所有页面共享这一层的视觉与交互。
 * 全局：window.J4UI, window.J4C (组件构造)
 */
(function (global) {
  'use strict';

  var D = global.J4DOM;
  var I = global.J4Icons;
  var el = D.el;

  var layers = { toast: null, sheet: null, menu: null };
  var openSheets = [];

  function ensureToastLayer() {
    if (!layers.toast) {
      layers.toast = el('div', { class: 'toast-layer', id: 'toastLayer' });
      document.body.appendChild(layers.toast);
    }
    return layers.toast;
  }

  // ---------------- Toast ----------------
  //
  // 三条硬约束（都是线上截图暴露出来的形态）：
  //   1) 全局同一时刻最多 1 条可见。以前每次 toast 都 append 一个 .toast，列表渲染
  //      里每首歌失败一次就叠一层，最后糊成一堵红墙；现在新 toast 直接顶掉旧的。
  //   2) timeout 句柄必须能被新 toast 取消。老节点的 setTimeout(1900) 不会因为节点被
  //      移除就消失，不取消的话它会在稍后把 laterNode 的标志位改掉，把新 toast 提前
  //      送走（表现为「闪一下就没了」）。
  //   3) 错误去重：同一 key（歌曲 id / 事件 key / 文案）在 DEDUP_MS 内只弹一次。
  //      批量预取失败走 UI.toastError 的 silent 分支不弹，剩下的用户主动操作也可能
  //      因为连点/重试重复触发，去重表兜住这一层。
  var TOAST_DEDUP_MS = 60000;
  var toastSeq = 0;
  var activeToast = null; // { node, kind, key, at, seq }
  var dedup = Object.create(null); // key -> 最后弹出时间戳
  var dedupSeq = 0;

  function toastKeyOf(key) {
    if (key === null || key === undefined || key === '') return '';
    if (typeof key === 'object') {
      if (key.songId) return 'id::' + key.songId;
      if (key.id) return 'id::' + key.id;
      if (key.key) return 'key::' + key.key;
      return 'msg::' + normalizeMsg(key.message || key.msg || '');
    }
    var s = String(key);
    // 纯文案做精确去重；显式传 'id::xxx' / 'key::xxx' 这类命名空间键则原样使用
    if (s.indexOf('id::') === 0 || s.indexOf('key::') === 0 || s.indexOf('msg::') === 0) return s;
    return 'msg::' + normalizeMsg(s);
  }

  // 原生文案形如「暂无可用音源：在你的身边」，冒号两侧是约束和歌名。
  // 这里只做空白归一，不做截断 —— 截断会把「播放失败」和「播放失败：xx」误合并。
  function normalizeMsg(s) {
    return String(s == null ? '' : s).replace(/\s+/g, ' ').trim();
  }

  function dedupHit(key, now, windowMs) {
    var k = toastKeyOf(key);
    if (!k) return false;
    var last = dedup[k];
    return !!(last && now - last < (windowMs || TOAST_DEDUP_MS));
  }

  function dedupMark(key, now) {
    var k = toastKeyOf(key);
    dedup[k] = now;
    // 定期清理，避免 Map 无限长（桌面浏览器预览会跑很久）
    if (++dedupSeq % 40 === 0) {
      for (var kk in dedup) {
        if (Object.prototype.hasOwnProperty.call(dedup, kk) && now - dedup[kk] > TOAST_DEDUP_MS) delete dedup[kk];
      }
    }
  }

  function clearActiveToast() {
    var cur = activeToast;
    activeToast = null;
    if (!cur) return;
    if (cur.timer) {
      clearTimeout(cur.timer);
      cur.timer = null;
    }
    if (cur.node) {
      if (cur.node.parentNode) cur.node.parentNode.removeChild(cur.node);
    }
  }

  /* 原始 toast：不做去重，但保证全局只有 1 条。
   *   msg  文案
   *   kind success | error | info
   *   ms   停留毫秒（默认 1900）
   *   key  去重键（可选，字符串或 {id}）
   */
  function toast(msg, kind, ms, key) {
    if (msg === null || msg === undefined || msg === '') return null;
    var layer = ensureToastLayer();
    var id = ++toastSeq;

    // 新 toast 直接替换旧的（不排队、不堆叠）
    clearActiveToast();

    var node = el('div', { class: 'toast' + (kind ? ' is-' + kind : '') }, [
      el('span', { html: kind === 'error' ? I.info : kind === 'success' ? I.check : I.info, class: 'toast__ic' }),
      el('span', { text: String(msg) })
    ]);
    layer.appendChild(node);
    activeToast = { node: node, kind: kind, key: key, at: Date.now(), seq: id, timer: null };

    activeToast.timer = setTimeout(function () {
      // 只有「自己还是当前那条」才有权收走节点。
      // 不校验的话，被顶替的旧节点会在自己的 1900ms 到点后把新节点的兄弟节点删掉。
      if (!activeToast || activeToast.seq !== id) return;
      node.classList.add('is-leaving');
      activeToast.timer = setTimeout(function () {
        if (!activeToast || activeToast.seq !== id) return;
        if (node.parentNode) node.parentNode.removeChild(node);
        activeToast = null;
      }, 240);
    }, ms || 1900);
    // 同时给原生发一份（真机可以走系统 toast）
    if (global.J4Bridge && global.J4Bridge.native && global.J4Native && global.J4Native.toast) {
      try {
        global.J4Native.toast(String(msg));
      } catch (e) {
        /* ignore */
      }
    }
    return node;
  }

  /* 错误 toast。opts：
   *   key      去重键，默认用文案
   *   dedupMs  去重窗口，默认 60s
   *   silent   为 true 时完全不弹，只在 console.warn 留痕（批量/渲染路径用这个）
   *   scope    'user' | 'batch'，'batch' 等价于 silent，纯语义标记，便于排查
   * 返回值：true 表示「确实弹出了」，false 表示被吞（silent / 去重）。
   */
  function toastError(msg, opts) {
    opts = opts || {};
    var text = msg && msg.message ? msg.message : msg;
    if (text === null || text === undefined || text === '') text = '未知错误';
    text = String(text);
    if (opts.silent || opts.scope === 'batch') {
      // 列表渲染 / 批量预取的失败：不打扰用户，但必须在控制台留痕
      if (global.console && console.warn) console.warn('[J4Music] 取址/加载失败（静默）：' + text);
      return false;
    }
    var now = Date.now();
    var key = opts.key === undefined ? text : opts.key;
    var windowMs = typeof opts.dedupMs === 'number' ? opts.dedupMs : TOAST_DEDUP_MS;
    if (dedupHit(key, now, windowMs)) {
      if (global.console && console.warn) console.warn('[J4Music] 错误已去重（' + Math.round(windowMs / 1000) + 's 内同一 key）：' + text);
      return false;
    }
    dedupMark(key, now);
    toast(text, 'error', opts.ms, key);
    return true;
  }

  function toastReset() {
    clearActiveToast();
    dedup = Object.create(null);
  }

  // ---------------- Bottom sheet ----------------
  function openSheet(opts) {
    var root = el('div', { class: 'sheet-root' });
    var scrim = el('div', { class: 'sheet__scrim' });
    var panel = el('div', { class: 'sheet' });
    var body = el('div', { class: 'sheet__body' });
    panel.appendChild(el('div', { class: 'sheet__handle' }));
    if (opts.render || opts.title || opts.sub) {
      panel.appendChild(
        el('div', { class: 'sheet__head' }, [
          el('div', { class: 'col' }, [
            el('div', { class: 'sheet__title', text: opts.title || '' }),
            opts.sub ? el('div', { class: 'sheet__sub', text: opts.sub }) : null
          ]),
          opts.closeBtn !== false
            ? el('button', { class: 'ibtn', 'aria-label': '关闭', html: I.close, onclick: close })
            : null
        ])
      );
    }
    panel.appendChild(body);
    root.appendChild(scrim);
    root.appendChild(panel);
    document.body.appendChild(root);
    openSheets.push(root);
    if (global.J4NotifyBackState) global.J4NotifyBackState();

    // 进场
    requestAnimationFrame(function () {
      root.classList.add('is-open');
    });

    // 下拉关闭
    var startY = 0;
    var dragging = false;
    var dy = 0;
    panel.addEventListener(
      'touchstart',
      function (e) {
        if (body.scrollTop > 0) return;
        startY = e.touches[0].clientY;
        dragging = true;
        dy = 0;
        panel.style.transition = 'none';
      },
      { passive: true }
    );
    panel.addEventListener(
      'touchmove',
      function (e) {
        if (!dragging) return;
        dy = Math.max(0, e.touches[0].clientY - startY);
        panel.style.transform = 'translate3d(0,' + dy + 'px,0)';
      },
      { passive: true }
    );
    panel.addEventListener(
      'touchend',
      function () {
        if (!dragging) return;
        dragging = false;
        panel.style.transition = '';
        panel.style.transform = '';
        if (dy > 90) close();
        dy = 0;
      },
      { passive: true }
    );

    scrim.addEventListener('click', close);

    function close() {
      var i = openSheets.indexOf(root);
      if (i >= 0) openSheets.splice(i, 1);
      root.classList.remove('is-open');
      setTimeout(function () {
        if (root.parentNode) root.parentNode.removeChild(root);
        if (opts.onClose) opts.onClose();
      }, 340);
    }

    if (typeof opts.render === 'function') {
      var content = opts.render({ close: close, body: body, panel: panel, root: root });
      if (content) body.appendChild(content);
    }
    return { root: root, body: body, panel: panel, close: close };
  }

  // 动作列表 sheet
  function actionSheet(items, opts) {
    opts = opts || {};
    return openSheet({
      title: opts.title,
      sub: opts.sub,
      render: function (ctx) {
        var wrap = el('div', { class: 'col' });
        items.forEach(function (it, idx) {
          if (it.divider) {
            wrap.appendChild(el('div', { class: 'sheet-divider' }));
            return;
          }
          wrap.appendChild(
            el(
              'button',
              {
                class: 'sheet-action' + (it.danger ? ' sheet-action--danger' : ''),
                onclick: function () {
                  ctx.close();
                  setTimeout(function () {
                    if (it.onSelect) it.onSelect();
                  }, 60);
                }
              },
              [el('span', { html: it.icon || '' }), el('span', { text: it.label })]
            )
          );
        });
        return wrap;
      }
    });
  }

  /* 文本输入。兼容两种风格：
   *   UI.prompt({ onOk(v) })                      → 返回 sheet 控制句柄
   *   UI.prompt({ onSubmit(v) -> Promise|Value }) → 返回 Promise<result|false>
   * 若 onSubmit 抛错/返回被拒绝的 Promise，sheet 保持打开并 toast 错误，值不会丢。
   */
  function prompt(opts) {
    opts = opts || {};
    var input;
    var promiseStyle = typeof opts.onSubmit === 'function';
    var resolveFn = null;
    var done = promiseStyle
      ? new Promise(function (res) {
          resolveFn = res;
        })
      : null;

    function submit(ctx) {
      var v = input.value.trim();
      if (!promiseStyle) {
        ctx.close();
        if (opts.onOk) opts.onOk(v);
        return;
      }
      var r;
      try {
        r = opts.onSubmit(v);
      } catch (err) {
        toastError(err, { key: 'prompt::' + (opts.title || ''), dedupMs: 3000 });
        return;
      }
      if (r && typeof r.then === 'function') {
        var btns = ctx.root.querySelectorAll('.btn--primary');
        btns.forEach(function (b) { b.classList.add('is-loading'); });
        r.then(function (res) {
          btns.forEach(function (b) { b.classList.remove('is-loading'); });
          if (res === false) return;
          ctx.close();
          resolveFn(res === undefined ? v : res);
        }).catch(function (err) {
          btns.forEach(function (b) { b.classList.remove('is-loading'); });
          toastError((err && err.message) || '操作失败', { key: 'prompt::' + (opts.title || ''), dedupMs: 3000 });
        });
      } else {
        ctx.close();
        resolveFn(r === undefined ? v : r);
      }
    }

    var sheet = openSheet({
      title: opts.title || '输入',
      sub: opts.sub,
      onClose: function () {
        if (promiseStyle && resolveFn) resolveFn(false);
      },
      render: function (ctx) {
        var field = el('div', { class: 'field', style: { margin: '4px 18px 14px' } });
        var tag = opts.multiline ? 'textarea' : 'input';
        input = el(tag, {
          type: tag === 'input' ? 'text' : null,
          rows: opts.multiline ? 5 : null,
          placeholder: opts.placeholder || '',
          value: opts.value || '',
          style: { padding: '0 4px' }
        });
        if (tag === 'input') input.setAttribute('type', 'text');
        field.appendChild(input);
        var row = el('div', { class: 'row', style: { gap: '10px', padding: '0 18px 20px' } }, [
          el('button', {
            class: 'btn btn--ghost btn--block',
            text: opts.cancelText || '取消',
            onclick: function () {
              ctx.close();
            }
          }),
          el('button', {
            class: 'btn btn--primary btn--block',
            text: opts.okText || '确定',
            onclick: function () {
              submit(ctx);
            }
          })
        ]);
        var wrap = el('div', { class: 'col' }, [
          opts.desc ? el('p', { class: 't2', style: { padding: '0 18px 12px', 'font-size': '12.5px' }, text: opts.desc }) : null,
          field,
          row
        ]);
        if (tag === 'input') {
          input.addEventListener('keydown', function (e) {
            if (e.key === 'Enter') {
              e.preventDefault();
              submit(ctx);
            }
          });
        }
        setTimeout(function () {
          try { input.focus(); } catch (e) {}
        }, 360);
        return wrap;
      }
    });

    if (promiseStyle) {
      done.sheet = sheet;
      return done;
    }
    return sheet;
  }

  /* 确认对话框。兼容：
   *   UI.confirm({ onOk(){}, onCancel(){} })
   *   UI.confirm({...}).then(function(ok){ ... })   ← 推荐
   */
  function confirm(opts) {
    opts = opts || {};
    var resolveFn = null;
    var done = new Promise(function (res) {
      resolveFn = res;
    });
    var sheet = actionSheet(
      [
        {
          label: opts.okText || '确定',
          danger: opts.danger,
          onSelect: function () {
            resolveFn(true);
            if (opts.onOk) opts.onOk();
          }
        },
        { divider: true },
        {
          label: opts.cancelText || '取消',
          onSelect: function () {
            resolveFn(false);
            if (opts.onCancel) opts.onCancel();
          }
        }
      ],
      {
        title: opts.title,
        sub: opts.message,
        onClose: function () {
          resolveFn(false);
        }
      }
    );
    done.sheet = sheet;
    return done;
  }

  /** 只判断、不关闭：给返回键的「能不能返回」结算用。 */
  function hasOverlay() {
    return (layers.menu != null) || openSheets.length > 0;
  }

  function closeTopOverlay() {
    // 菜单优先
    if (layers.menu) {
      closeMenu();
      return true;
    }
    if (openSheets.length) {
      var root = openSheets[openSheets.length - 1];
      var btn = root.querySelector('.sheet__head .ibtn');
      if (btn) btn.click();
      else {
        var scrim = root.querySelector('.sheet__scrim');
        if (scrim) scrim.click();
      }
      return true;
    }
    return false;
  }

  // ---------------- 操作菜单 ----------------
  /* openMenu(items, anchorEvent)  items: {icon,label,danger,act|onSelect} */
  function openMenu(items, anchorEvent) {
    closeMenu();
    var root = el('div', { class: 'menu-host' });
    var menu = el('div', { class: 'menu' });
    items.forEach(function (it) {
      menu.appendChild(
        el('button', {
          class: 'menu__item' + (it.danger ? ' menu__item--danger' : ''),
          onclick: function () {
            closeMenu();
            setTimeout(function () {
              var fn = it.act || it.onSelect;
              if (fn) fn();
            }, 40);
          }
        }, [el('span', { html: it.icon || '' }), el('span', { text: it.label })])
      );
    });
    root.appendChild(menu);
    document.body.appendChild(root);
    layers.menu = root;

    var x = (anchorEvent && (anchorEvent.clientX || anchorEvent.pageX)) || window.innerWidth / 2;
    var y = (anchorEvent && (anchorEvent.clientY || anchorEvent.pageY)) || window.innerHeight / 2;
    var w = 190;
    menu.style.left = Math.min(Math.max(12, x - w + 8), window.innerWidth - w - 12) + 'px';
    var mh = items.length * 44 + 24;
    var topY = y - mh > 80 ? y - mh : y + 8;
    menu.style.top = Math.max(60, Math.min(topY, window.innerHeight - mh - 20)) + 'px';
    root.addEventListener('click', function (e) {
      if (e.target === root) closeMenu();
    });
  }

  function closeMenu() {
    if (layers.menu && layers.menu.parentNode) {
      layers.menu.parentNode.removeChild(layers.menu);
    }
    layers.menu = null;
    if (global.J4NotifyBackState) global.J4NotifyBackState();
  }

  // ---------------- 图片 / 封面 ----------------
  function coverEl(song, size, extraClass) {
    var cls = 'cover cover--' + (size || 44) + (extraClass ? ' ' + extraClass : '');
    var wrap = el('div', { class: cls });
    var name = (song && (song.title || song.name || song.album)) || '?';
    wrap.style.background = D.placeholderGradient((song && song.id) || name);
    var ph = el('span', { class: 'cover__ph', text: D.firstChar(name) });
    ph.style.background = D.placeholderGradient((song && song.id) || name);
    wrap.appendChild(ph);
    var url = song && (song.coverUrl || song.cover);
    if (url) {
      var img = el('img', { src: url, alt: '', loading: 'lazy', decoding: 'async' });
      img.addEventListener('load', function () {
        img.classList.add('is-loaded');
      });
      img.addEventListener('error', function () {
        if (img.parentNode) img.parentNode.removeChild(img);
      });
      // 缓存命中的图不会再触发 load，补判一次，否则占位字会盖住封面
      if (img.complete && img.naturalWidth > 0) img.classList.add('is-loaded');
      wrap.insertBefore(img, ph);
    }
    if (song && song.vip) wrap.appendChild(el('span', { class: 'cover__vip', text: 'VIP' }));
    return wrap;
  }

  // ---------------- 歌曲行 ----------------
  /* opts: { index, song, onTap, onMore, showIndex, showSrc, showQuality, draggable } */
  function songRow(song, opts) {
    opts = opts || {};
    var row = el('div', { class: 'song-row swipe-body', dataset: { songId: song.id } });
    var main = el('div', { class: 'row', style: { gap: '12px', 'flex': '1 1 auto', 'min-width': '0' } });
    if (opts.showIndex) {
      main.appendChild(el('span', { class: 'song-row__idx', text: opts.index != null ? String(opts.index + 1).padStart(2, '0') : '' }));
    }
    main.appendChild(coverEl(song, 52));
    var body = el('div', { class: 'song-row__body' });
    var titleRow = el('div', { class: 'song-row__title' }, [el('span', { class: 'ellipsis', text: song.title || '未知歌曲' })]);
    if (song.vip) titleRow.appendChild(el('span', { class: 'badge badge--vip', text: 'VIP' }));
    if (song.localPath) titleRow.appendChild(el('span', { class: 'badge badge--local', text: '本地' }));
    body.appendChild(titleRow);
    var sub = el('div', { class: 'song-row__sub' }, [
      el('span', { class: 'ellipsis', text: song.artist || '未知艺术家' }),
      el('span', { text: '·' }),
      el('span', { class: 'ellipsis', text: song.album || '单曲' })
    ]);
    if (opts.showSrc !== false) {
      var srcName = SRC_LABEL[song.source] || song.source || '';
      if (srcName) sub.appendChild(el('span', { class: 'badge badge--src', text: srcName }));
    }
    if (opts.showQuality && song.quality) sub.appendChild(el('span', { text: String(song.quality).toUpperCase() }));
    body.appendChild(sub);
    main.appendChild(body);
    row.appendChild(main);

    var more = el('button', {
      class: 'song-row__more',
      'aria-label': '更多',
      html: I.moreV,
      onclick: function (e) {
        e.stopPropagation();
        if (opts.onMore) opts.onMore(song, e);
      }
    });
    row.appendChild(more);

    row.addEventListener('click', function () {
      if (opts.onTap) opts.onTap(song, opts.index);
    });
    return row;
  }

  var SRC_LABEL = { netease: '网易云', qq: 'QQ', local: '本地', all: '全部' };
  var SRC_COLOR = {
    netease: 'linear-gradient(135deg,#ff4a4a,#c81e1e)',
    qq: 'linear-gradient(135deg,#3ac8ff,#1a7cff)',
    huawei: 'linear-gradient(135deg,#ff5f6d,#ff2d55)',
    local: 'linear-gradient(135deg,#8b8b9e,#4a4a5c)',
    all: 'linear-gradient(135deg,#7c5cff,#ff6fd8)'
  };

  function sourceLogo(src, size) {
    var s = size || 34;
    var node = el('div', {
      class: 'source-logo',
      style: { width: s + 'px', height: s + 'px', background: SRC_COLOR[src] || SRC_COLOR.all },
      text: src === 'netease' ? '云' : src === 'qq' ? 'Q' : 'L'
    });
    return node;
  }

  // ---------------- 长列表 ----------------
  /* 名字还叫 virtualList（调用点很多），但实现改成「老老实实全部渲染、分批追加」。
   * 原来的绝对定位回收方案有两个硬伤，用户直接看得到：
   *   1) 传进来的容器是 .list（overflow:visible，高度等于内容），真正的滚动容器是外层
   *      .page__scroll，所以 scroller.scrollTop 恒为 0、clientHeight 等于整页高度，
   *      可视区算不出来，中途一出异常后面那些行就再也不渲染 —— 表现是「20 首之后全没了」；
   *   2) 节点被复用时会换一份数据，但序号是渲染时写死的，于是出现「04 变成 01」这种错位。
   * 歌单最多几百首，普通行节点很便宜，全部渲染反而更稳。超过 400 条时分批追加，避免一次性卡住。
   * opts: { items, rowHeight, renderItem(item, index), overscan } */
  function virtualList(container, opts) {
    var items = opts.items || [];
    var CHUNK = 80;
    var killed = false;
    var raf = 0;

    D.clear(container);

    function appendFrom(from) {
      if (killed) return;
      var to = Math.min(items.length, from + CHUNK);
      var frag = document.createDocumentFragment();
      for (var i = from; i < to; i++) {
        var node = opts.renderItem(items[i], i);
        if (node) frag.appendChild(node);
      }
      container.appendChild(frag);
      if (to < items.length) raf = (window.requestAnimationFrame || setTimeout)(function () { appendFrom(to); });
    }

    function render() {
      killed = true;
      if (raf && window.cancelAnimationFrame) window.cancelAnimationFrame(raf);
      killed = false;
      D.clear(container);
      appendFrom(0);
    }

    appendFrom(0);
    return { render: render, destroy: function () { killed = true; } };
  }

  // ---------------- 滑动手势（左滑收藏 / 右滑下一首） ----------------
  function attachSwipe(node, opts) {
    var startX = 0;
    var startY = 0;
    var dx = 0;
    var active = false;
    var locked = false;
    var threshold = 74;

    node.addEventListener(
      'touchstart',
      function (e) {
        if (e.touches.length !== 1) return;
        startX = e.touches[0].clientX;
        startY = e.touches[0].clientY;
        dx = 0;
        active = true;
        locked = false;
        node.style.transition = 'none';
      },
      { passive: true }
    );
    node.addEventListener(
      'touchmove',
      function (e) {
        if (!active) return;
        var x = e.touches[0].clientX - startX;
        var y = e.touches[0].clientY - startY;
        if (!locked) {
          if (Math.abs(y) > Math.abs(x) + 4) {
            active = false;
            return;
          }
          if (Math.abs(x) > 8) locked = true;
        }
        if (!locked) return;
        dx = Math.max(-110, Math.min(110, x));
        node.style.transform = 'translate3d(' + dx + 'px,0,0)';
      },
      { passive: true }
    );
    node.addEventListener(
      'touchend',
      function () {
        if (!active) return;
        active = false;
        node.style.transition = '';
        var fired = null;
        if (dx < -threshold && opts.onSwipeLeft) fired = opts.onSwipeLeft;
        else if (dx > threshold && opts.onSwipeRight) fired = opts.onSwipeRight;
        // 不要把整行推到屏幕外再滑回来 —— 那 160ms 里这一行就是一片空白，
        // 用户会以为歌被删了。改成「弹一下再回位」，动作同时触发。
        node.style.transform = fired ? 'translate3d(' + (dx > 0 ? 26 : -26) + 'px,0,0)' : '';
        if (fired) {
          setTimeout(function () { node.style.transform = ''; }, 90);
          fired();
        }
        dx = 0;
        locked = false;
      },
      { passive: true }
    );
  }

  // ---------------- 空态 ----------------
  function emptyState(title, sub, big) {
    return el('div', { class: 'empty anim-fade-in' }, [
      el(
        'div',
        { class: 'note-dots' + (big ? ' note-dots--big' : '') },
        [0, 1, 2, 3, 4].map(function (i) {
          return el('i', { style: { 'animation-delay': i * 0.12 + 's' } });
        })
      ),
      el('div', { class: 'empty__title', text: title || '这里还很安静' }),
      sub ? el('div', { class: 'empty__sub', text: sub }) : null
    ]);
  }

  function skeletonRows(n) {
    var frag = document.createDocumentFragment();
    for (var i = 0; i < n; i++) frag.appendChild(el('div', { class: 'skeleton skeleton--row' }));
    return frag;
  }

  /**
   * 下拉刷新。
   *
   * 只在「已经滚到顶部」时接管向下拖动，用一条高度会变的横条把内容顶下去，
   * 而不是改 scrollTop —— 后者会和 WebView 的惯性滚动互相打架，手指一松就跳回去。
   * 松手超过 44px 就触发 onRefresh，返回 Promise 时等它结束再收起横条。
   */
  function pullToRefresh(scroller, onRefresh) {
    if (!scroller || scroller.__ptr) return null;
    scroller.__ptr = true;
    var bar = el('div', { class: 'ptr' }, [el('div', { class: 'ptr__dot' })]);
    scroller.insertBefore(bar, scroller.firstChild);

    var startY = 0;
    var pulling = false;
    var busy = false;
    var dy = 0;

    function setH(h) {
      bar.style.height = h + 'px';
      bar.classList.toggle('is-armed', h >= 44);
    }
    function finish() {
      busy = false;
      bar.classList.remove('is-busy');
      setH(0);
    }

    scroller.addEventListener('touchstart', function (e) {
      if (busy || scroller.scrollTop > 2) { pulling = false; return; }
      startY = e.touches[0].clientY;
      pulling = true;
      dy = 0;
    }, { passive: true });

    scroller.addEventListener('touchmove', function (e) {
      if (!pulling || busy) return;
      var d = e.touches[0].clientY - startY;
      if (d <= 0 || scroller.scrollTop > 2) {
        if (dy > 0) { dy = 0; setH(0); }
        return;
      }
      dy = Math.min(84, d * 0.5);
      setH(dy);
      if (dy > 6 && e.cancelable) e.preventDefault();
    }, { passive: false });

    function end() {
      if (!pulling) return;
      pulling = false;
      var fire = dy >= 44;
      dy = 0;
      if (!fire) { setH(0); return; }
      busy = true;
      bar.classList.add('is-busy');
      setH(44);
      var p = null;
      try { p = onRefresh && onRefresh(); } catch (err) { finish(); return; }
      if (p && typeof p.then === 'function') p.then(finish, finish);
      else setTimeout(finish, 420);
    }

    scroller.addEventListener('touchend', end, { passive: true });
    scroller.addEventListener('touchcancel', end, { passive: true });
    return { destroy: function () { scroller.__ptr = false; if (bar.parentNode) bar.parentNode.removeChild(bar); } };
  }

  global.J4UI = {
    toast: toast,
    toastError: toastError,
    toastReset: toastReset,
    TOAST_DEDUP_MS: TOAST_DEDUP_MS,
    openSheet: openSheet,
    actionSheet: actionSheet,
    prompt: prompt,
    confirm: confirm,
    openMenu: openMenu,
    closeMenu: closeMenu,
    closeTopOverlay: closeTopOverlay,
    hasOverlay: hasOverlay,
    coverEl: coverEl,
    songRow: songRow,
    sourceLogo: sourceLogo,
    virtualList: virtualList,
    attachSwipe: attachSwipe,
    pullToRefresh: pullToRefresh,
    emptyState: emptyState,
    skeletonRows: skeletonRows,
    SRC_LABEL: SRC_LABEL,
    SRC_COLOR: SRC_COLOR,
    onDownloadTick: null
  };

  global.J4C = {
    el: el,
    icon: function (name, cls) {
      var span = el('span', { class: cls || '', html: I[name] || '' });
      return span;
    }
  };
})(window);
