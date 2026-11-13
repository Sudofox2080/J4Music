/* J4Music · dom.js
 * 极小的 DOM 助手 + 全局图标集（内联 SVG，不依赖任何图标字体/CDN）。
 * 全局：window.J4DOM, window.J4Icons
 */
(function (global) {
  'use strict';

  function el(tag, attrs, children) {
    var node = document.createElement(tag);
    if (attrs) {
      for (var k in attrs) {
        if (!Object.prototype.hasOwnProperty.call(attrs, k)) continue;
        var v = attrs[k];
        if (v == null || v === false) continue;
        if (k === 'class') node.className = v;
        else if (k === 'text') node.textContent = v;
        else if (k === 'html') node.innerHTML = v;
        else if (k === 'style' && typeof v === 'object') {
          for (var s in v) if (Object.prototype.hasOwnProperty.call(v, s)) node.style.setProperty(s, v[s]);
        } else if (k.indexOf('on') === 0 && typeof v === 'function') {
          node.addEventListener(k.slice(2).toLowerCase(), v);
        } else if (k === 'dataset' && typeof v === 'object') {
          for (var d in v) if (Object.prototype.hasOwnProperty.call(v, d)) node.dataset[d] = v[d];
        } else {
          node.setAttribute(k, v === true ? '' : v);
        }
      }
    }
    if (children != null) {
      var list = Array.isArray(children) ? children : [children];
      for (var i = 0; i < list.length; i++) {
        var c = list[i];
        if (c == null || c === false) continue;
        node.appendChild(typeof c === 'string' || typeof c === 'number' ? document.createTextNode(String(c)) : c);
      }
    }
    return node;
  }

  function qs(sel, root) {
    return (root || document).querySelector(sel);
  }

  function qsa(sel, root) {
    return Array.prototype.slice.call((root || document).querySelectorAll(sel));
  }

  function clear(node) {
    while (node && node.firstChild) node.removeChild(node.firstChild);
    return node;
  }

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function fmtTime(ms) {
    if (!isFinite(ms) || ms < 0) ms = 0;
    var total = Math.floor(ms / 1000);
    var m = Math.floor(total / 60);
    var s = total % 60;
    return m + ':' + (s < 10 ? '0' : '') + s;
  }

  function fmtSize(bytes) {
    if (!bytes) return '0 MB';
    var mb = bytes / 1024 / 1024;
    if (mb < 1024) return mb.toFixed(mb < 10 ? 1 : 0) + ' MB';
    return (mb / 1024).toFixed(2) + ' GB';
  }

  // 相对时间
  function fmtAgo(ts) {
    if (!ts) return '';
    var d = Date.now() - ts;
    if (d < 60000) return '刚刚';
    if (d < 3600000) return Math.floor(d / 60000) + ' 分钟前';
    if (d < 86400000) return Math.floor(d / 3600000) + ' 小时前';
    if (d < 86400000 * 30) return Math.floor(d / 86400000) + ' 天前';
    var dt = new Date(ts);
    return dt.getMonth() + 1 + '月' + dt.getDate() + '日';
  }

  function firstChar(name) {
    var s = String(name || '?').trim();
    if (!s) return '♪';
    var c = s.charAt(0);
    // 英文取首字母大写，中文取首字
    return /[a-z]/.test(c) ? c.toUpperCase() : c;
  }

  // 根据字符串稳定地取一个渐变色（用于占位封面）
  function hashHue(str) {
    var h = 0;
    for (var i = 0; i < String(str).length; i++) h = (h * 31 + String(str).charCodeAt(i)) % 360;
    return h;
  }

  function placeholderGradient(key) {
    var h = hashHue(key);
    return 'linear-gradient(135deg, hsl(' + h + ' 70% 42%) 0%, hsl(' + ((h + 48) % 360) + ' 78% 32%) 100%)';
  }

  function throttle(fn, wait) {
    var last = 0;
    var timer = null;
    return function () {
      var args = arguments;
      var self = this;
      var now = Date.now();
      var remain = wait - (now - last);
      if (remain <= 0) {
        last = now;
        fn.apply(self, args);
      } else if (!timer) {
        timer = setTimeout(function () {
          timer = null;
          last = Date.now();
          fn.apply(self, args);
        }, remain);
      }
    };
  }

  function debounce(fn, wait) {
    var t = null;
    return function () {
      var args = arguments;
      var self = this;
      clearTimeout(t);
      t = setTimeout(function () {
        fn.apply(self, args);
      }, wait);
    };
  }

  // ---------- 涟漪 ----------
  function attachRipple(root) {
    if (!root || root.__rippleBound) return;
    root.__rippleBound = true;
    root.classList.add('ripple-host');
    root.addEventListener('pointerdown', function (e) {
      if (root.__noRipple) return;
      var rect = root.getBoundingClientRect();
      var size = Math.max(rect.width, rect.height) * 1.2;
      var ink = document.createElement('span');
      ink.className = 'ripple-ink';
      ink.style.width = size + 'px';
      ink.style.height = size + 'px';
      ink.style.left = e.clientX - rect.left - size / 2 + 'px';
      ink.style.top = e.clientY - rect.top - size / 2 + 'px';
      ink.style.transformOrigin = 'center';
      root.appendChild(ink);
      setTimeout(function () {
        if (ink.parentNode) ink.parentNode.removeChild(ink);
      }, 540);
    });
    // 全局：任何 .btn / .ibtn 都自动涟漪（动态插入的元素靠委托）
    if (!document.__rippleDelegate) {
      document.__rippleDelegate = true;
      document.addEventListener(
        'pointerdown',
        function (e) {
          var t = e.target.closest && e.target.closest('.btn, .ibtn, .play-fab, .chip, .sheet-action, .menu__item');
          if (!t || t.__rippleBound) return;
          attachRipple(t);
        },
        true
      );
    }
  }

  global.J4DOM = {
    el: el,
    qs: qs,
    qsa: qsa,
    clear: clear,
    esc: esc,
    fmtTime: fmtTime,
    fmtSize: fmtSize,
    fmtAgo: fmtAgo,
    firstChar: firstChar,
    placeholderGradient: placeholderGradient,
    throttle: throttle,
    debounce: debounce,
    attachRipple: attachRipple
  };

  // ---------- 图标集（24x24 viewBox，stroke 风格） ----------
  function svg(paths, extra) {
    return (
      '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" ' +
      (extra || '') +
      '>' +
      paths +
      '</svg>'
    );
  }

  var I = {
    play: svg('<path d="M7 4.5v15l13-7.5-13-7.5z" fill="currentColor" stroke="none"/>'),
    pause: svg('<rect x="6.5" y="4.5" width="4" height="15" rx="1.4" fill="currentColor" stroke="none"/><rect x="13.5" y="4.5" width="4" height="15" rx="1.4" fill="currentColor" stroke="none"/>'),
    next: svg('<path d="M5 5.5v13l10-6.5-10-6.5z" fill="currentColor" stroke="none"/><rect x="16.5" y="5" width="2.6" height="14" rx="1.3" fill="currentColor" stroke="none"/>'),
    prev: svg('<path d="M19 5.5v13l-10-6.5 10-6.5z" fill="currentColor" stroke="none"/><rect x="4.9" y="5" width="2.6" height="14" rx="1.3" fill="currentColor" stroke="none"/>'),
    loopList: svg('<path d="M4 9.5A5.5 5.5 0 0 1 9.5 4H18"/><path d="M15 1.6 18.4 4 15 6.4"/><path d="M20 14.5A5.5 5.5 0 0 1 14.5 20H6"/><path d="M9 17.6 5.6 20 9 22.4"/>'),
    loopOne: svg('<path d="M4 9.5A5.5 5.5 0 0 1 9.5 4H18"/><path d="M15 1.6 18.4 4 15 6.4"/><path d="M20 14.5A5.5 5.5 0 0 1 14.5 20H6"/><path d="M9 17.6 5.6 20 9 22.4"/><text x="12" y="14.6" font-size="7.5" font-family="sans-serif" font-weight="700" fill="currentColor" stroke="none" text-anchor="middle">1</text>'),
    shuffle: svg('<path d="M3 7h3.5c1.4 0 2.6.7 3.4 1.9l4.2 6.2c.8 1.2 2 1.9 3.4 1.9H21"/><path d="M3 17h3.5c1.4 0 2.6-.7 3.4-1.9l1-1.5"/><path d="M14 9.4l.6-.9c.8-1.2 2-1.9 3.4-1.9H21"/><path d="M18 3.6 21 6.6 18 9.6"/><path d="M18 14.4 21 17.4 18 20.4"/>'),
    list: svg('<path d="M4 6.5h11"/><path d="M4 12h11"/><path d="M4 17.5h7"/><circle cx="19" cy="6.5" r="1.3" fill="currentColor" stroke="none"/><circle cx="19" cy="12" r="1.3" fill="currentColor" stroke="none"/>'),
    heart: svg('<path d="M12 20s-7.5-4.6-7.5-9.6A4.4 4.4 0 0 1 12 7.2 4.4 4.4 0 0 1 19.5 10.4C19.5 15.4 12 20 12 20z"/>'),
    heartFill: svg('<path d="M12 20s-7.5-4.6-7.5-9.6A4.4 4.4 0 0 1 12 7.2 4.4 4.4 0 0 1 19.5 10.4C19.5 15.4 12 20 12 20z" fill="currentColor"/>'),
    search: svg('<circle cx="11" cy="11" r="6.5"/><path d="M16 16 21 21"/>'),
    home: svg('<path d="M4 10.5 12 4l8 6.5V19a1.6 1.6 0 0 1-1.6 1.6H5.6A1.6 1.6 0 0 1 4 19z"/><path d="M9.4 20.6v-6.2h5.2v6.2"/>'),
    compass: svg('<circle cx="12" cy="12" r="8.4"/><path d="m15.2 8.8-1.9 4.5-4.5 1.9 1.9-4.5z" fill="currentColor" stroke="none"/>'),
    library: svg('<path d="M5 4.5v15"/><path d="M9.6 4.5v15"/><path d="M14.2 5.6l4.6 13.2"/>'),
    settings: svg('<circle cx="12" cy="12" r="3"/><path d="M12 2.8v2.4M12 18.8v2.4M4.5 12H2.1M21.9 12h-2.4M6.7 6.7 5 5M19 19l-1.7-1.7M6.7 17.3 5 19M19 5l-1.7 1.7"/>'),
    back: svg('<path d="M15 5 8 12l7 7"/>'),
    down: svg('<path d="M6 9.5 12 15.5 18 9.5"/>'),
    up: svg('<path d="M6 14.5 12 8.5 18 14.5"/>'),
    chevron: svg('<path d="M9.5 6 15.5 12 9.5 18"/>'),
    more: svg('<circle cx="5.5" cy="12" r="1.6" fill="currentColor" stroke="none"/><circle cx="12" cy="12" r="1.6" fill="currentColor" stroke="none"/><circle cx="18.5" cy="12" r="1.6" fill="currentColor" stroke="none"/>'),
    moreV: svg('<circle cx="12" cy="5.5" r="1.6" fill="currentColor" stroke="none"/><circle cx="12" cy="12" r="1.6" fill="currentColor" stroke="none"/><circle cx="12" cy="18.5" r="1.6" fill="currentColor" stroke="none"/>'),
    plus: svg('<path d="M12 5v14M5 12h14"/>'),
    close: svg('<path d="M6 6l12 12M18 6 6 18"/>'),
    check: svg('<path d="M4.5 12.5 9.5 17.5 19.5 6.5"/>'),
    download: svg('<path d="M12 3.5v11"/><path d="m7.5 10.5 4.5 4.5 4.5-4.5"/><path d="M4.5 18.5h15"/>'),
    trash: svg('<path d="M4.8 7h14.4"/><path d="M9.5 7V4.8h5V7"/><path d="M6.6 7l.9 12.2h9l.9-12.2"/><path d="M10.4 10.6v5.6M13.6 10.6v5.6"/>'),
    edit: svg('<path d="M4.5 19.5h4l10-10-4-4-10 10z"/><path d="m13.8 6.2 4 4"/>'),
    share: svg('<circle cx="17.5" cy="5.8" r="2.6"/><circle cx="6.2" cy="12" r="2.6"/><circle cx="17.5" cy="18.2" r="2.6"/><path d="m8.6 10.7 6.6-3.6M8.6 13.3l6.6 3.6"/>'),
    folder: svg('<path d="M3.6 7.2A1.7 1.7 0 0 1 5.3 5.5h3.9l1.9 2.2h7.6a1.7 1.7 0 0 1 1.7 1.7v8.9a1.7 1.7 0 0 1-1.7 1.7H5.3a1.7 1.7 0 0 1-1.7-1.7z"/>'),
    audio: svg('<path d="M9 18V6.2l10-2v11.4"/><circle cx="6.6" cy="18.4" r="2.6"/><circle cx="16.6" cy="15.6" r="2.6"/>'),
    wave: svg('<path d="M3 12h2M7 8v8M11 5v14M15 8.5v7M19 10.5v3M21.5 12h.5"/>'),
    moon: svg('<path d="M20 14.5A8.4 8.4 0 0 1 9.5 4a8.5 8.5 0 1 0 10.5 10.5z"/>'),
    sun: svg('<circle cx="12" cy="12" r="4"/><path d="M12 2.6v2M12 19.4v2M4.5 12h-2M21.5 12h-2M6.5 6.5 5 5M19 19l-1.5-1.5M6.5 17.5 5 19M19 5l-1.5 1.5"/>'),
    cloud: svg('<path d="M7.5 18.5h9a3.8 3.8 0 0 0 .4-7.6A5.4 5.4 0 0 0 6.6 10 3.9 3.9 0 0 0 7.5 18.5z"/>'),
    volume: svg('<path d="M4 9.5h3.5L12 5.5v13l-4.5-4H4z"/><path d="M15.5 9.2a4 4 0 0 1 0 5.6M18 6.8a7.4 7.4 0 0 1 0 10.4"/>'),
    mute: svg('<path d="M4 9.5h3.5L12 5.5v13l-4.5-4H4z"/><path d="m16 9.5 5 5M21 9.5l-5 5"/>'),
    timer: svg('<circle cx="12" cy="13.5" r="7.5"/><path d="M12 9.8v3.9l2.4 1.6"/><path d="M9.5 2.6h5"/>'),
    info: svg('<circle cx="12" cy="12" r="8.5"/><path d="M12 11v5.4"/><circle cx="12" cy="7.9" r="1" fill="currentColor" stroke="none"/>'),
    user: svg('<circle cx="12" cy="8.2" r="3.6"/><path d="M4.8 20.2a7.2 7.2 0 0 1 14.4 0"/>'),
    login: svg('<path d="M14 4.5H6.5A1.5 1.5 0 0 0 5 6v12a1.5 1.5 0 0 0 1.5 1.5H14"/><path d="M17 8.5 20.5 12 17 15.5"/><path d="M10.5 12h10"/>'),
    logout: svg('<path d="M10 4.5H5.5A1.5 1.5 0 0 0 4 6v12a1.5 1.5 0 0 0 1.5 1.5H10"/><path d="M15 8.5 18.5 12 15 15.5"/><path d="M8.5 12h10"/>'),
    translate: svg('<path d="M4 6.5h9"/><path d="M8.5 4v2.5"/><path d="M6 6.5c0 4 3 7 6.5 8"/><path d="M10.5 6.5c0 4.5-2.5 7-5.5 8"/><path d="m13.5 20 4-10.5L21.5 20"/><path d="M15 16.5h5"/>'),
    lock: svg('<rect x="5" y="10.5" width="14" height="9.5" rx="2.2"/><path d="M8.2 10.5V8a3.8 3.8 0 0 1 7.6 0v2.5"/>'),
    unlock: svg('<rect x="5" y="10.5" width="14" height="9.5" rx="2.2"/><path d="M8.2 10.5V8a3.8 3.8 0 0 1 7-2"/>'),
    crown: svg('<path d="m3.5 17.5 1.6-9 4.4 3.4L12 5.5l2.5 6.4 4.4-3.4 1.6 9z"/><path d="M4 20.4h16"/>'),
    qr: svg('<rect x="4" y="4" width="6" height="6" rx="1"/><rect x="14" y="4" width="6" height="6" rx="1"/><rect x="4" y="14" width="6" height="6" rx="1"/><path d="M14 14h2.5v2.5H14zM18.5 14H20v2.5M14 19h3M19.5 18.5V20"/>'),
    refresh: svg('<path d="M20 12a8 8 0 1 1-2.6-5.9"/><path d="M20.5 4.5V10h-5.4"/>'),
    folderOpen: svg('<path d="M3.5 8.2h17l-2 11H5.5z"/><path d="M3.5 8.2 5.6 4.5h4l1.9 2.4"/>'),
    equalizer: svg('<path d="M5 20V9M12 20V4M19 20v-7"/>'),
    comment: svg('<path d="M5 5.5h14a1.6 1.6 0 0 1 1.6 1.6v8.4a1.6 1.6 0 0 1-1.6 1.6h-7.5L6 21v-3.9H5a1.6 1.6 0 0 1-1.6-1.6V7.1A1.6 1.6 0 0 1 5 5.5z"/>'),
    sync: svg('<path d="M4 9.5A5 5 0 0 1 9 4.5h7"/><path d="m13.5 2 2.5 2.5L13.5 7"/><path d="M20 14.5a5 5 0 0 1-5 5H8"/><path d="m10.5 22-2.5-2.5L10.5 17"/>'),
    logo: svg('<path d="M9 18.5V6.4l10.2-2.1v11.9" fill="none"/><circle cx="6.4" cy="18.6" r="2.7" fill="currentColor" stroke="none"/><circle cx="16.6" cy="16.2" r="2.7" fill="currentColor" stroke="none"/>'),
    star: svg('<path d="m12 4 2.5 5.3 5.5.7-4 3.9 1 5.6-5-2.8-5 2.8 1-5.6-4-3.9 5.5-.7z"/>')
  };

  global.J4Icons = I;
})(window);
