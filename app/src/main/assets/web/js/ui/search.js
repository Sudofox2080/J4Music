/* J4Music · ui/search.js —— 搜索页
 * 多音源并发搜索 + 历史/热词 + 结果列表（>200 虚拟滚动）+ 来源筛选
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
  var el = D.el;

  var HIST_KEY = 'j4.search.history';
  var HOT = ['夜行', '雨天', 'Violet', '慢跑', '胶片', 'Glass'];

  function readHistory() {
    try {
      return JSON.parse(localStorage.getItem(HIST_KEY) || '[]');
    } catch (e) {
      return [];
    }
  }

  function pushHistory(kw) {
    var h = readHistory().filter(function (x) { return x !== kw; });
    h.unshift(kw);
    try {
      localStorage.setItem(HIST_KEY, JSON.stringify(h.slice(0, 12)));
    } catch (e) {}
  }

  function clearHistory() {
    try {
      localStorage.removeItem(HIST_KEY);
    } catch (e) {}
  }

  function render() {
    var page = el('div', { class: 'page page--push' });
    var header = el('div', { class: 'page-header' }, [
      el('button', { class: 'ibtn', 'aria-label': '返回', html: I.back, onclick: function () { R.back(); } }),
      el('div', { class: 'page-header__title', text: '搜索' })
    ]);

    var input = el('input', {
      class: 'search-bar__input',
      type: 'search',
      placeholder: '搜索歌曲、歌手、专辑',
      enterkeyhint: 'search',
      autocomplete: 'off',
      spellcheck: 'false'
    });
    var clearBtn = el('button', { class: 'search-bar__clear', 'aria-label': '清空', html: I.close });
    var bar = el('div', { class: 'search-bar' }, [
      el('span', { class: 'search-bar__icon', html: I.search }),
      input,
      clearBtn
    ]);

    var scroll = el('div', { class: 'page__scroll' });
    var body = el('div', { class: 'page-pad' });
    scroll.appendChild(body);
    page.appendChild(header);
    page.appendChild(bar);
    page.appendChild(scroll);

    var state = {
      keyword: '',
      source: S.state.activeSource && S.state.activeSource !== 'all' ? S.state.activeSource : 'all',
      list: [],
      loading: false,
      total: 0
    };

    function sourcePicker() {
      var wrap = el('div', { class: 'chip-wrap' });
      var all = [{ id: 'all', name: '全部' }].concat(
        S.state.sources && S.state.sources.length
          ? S.state.sources
          : [
              { id: 'netease', name: '网易云' },
              { id: 'qq', name: 'QQ' },
                      { id: 'huawei', name: '华为' }
            ]
      );
      all.forEach(function (s) {
        var b = el('button', { class: 'chip' + (state.source === s.id ? ' is-active' : ''), dataset: { src: s.id } }, [el('span', { text: s.name })]);
        b.addEventListener('click', function () {
          state.source = s.id;
          wrap.querySelectorAll('.chip').forEach(function (c) {
            c.classList.toggle('is-active', c.dataset.src === s.id);
          });
          if (state.keyword) doSearch(state.keyword);
        });
        wrap.appendChild(b);
      });
      return wrap;
    }

    function showIdle() {
      body.innerHTML = '';
      var hist = readHistory();
      if (hist.length) {
        body.appendChild(
          el('div', { class: 'section-head' }, [
            el('h2', { text: '搜索历史' }),
            el('button', {
              class: 'link',
              text: '清空',
              onclick: function () { clearHistory(); showIdle(); }
            })
          ])
        );
        var chips = el('div', { class: 'chip-wrap' });
        hist.forEach(function (k) {
          chips.appendChild(
            el('button', { class: 'chip', onclick: function () { doSearch(k); } }, [el('span', { text: k })])
          );
        });
        body.appendChild(chips);
      }

      body.appendChild(el('div', { class: 'section-head' }, [el('h2', { text: '热门搜索' })]));
      var hotChips = el('div', { class: 'chip-wrap' });
      HOT.forEach(function (k, i) {
        hotChips.appendChild(
          el('button', { class: 'chip' + (i === 0 ? ' chip--hot' : ''), onclick: function () { doSearch(k); } }, [
            el('span', { class: 'chip__n', text: String(i + 1) }),
            el('span', { text: k })
          ])
        );
      });
      body.appendChild(hotChips);

      body.appendChild(el('div', { class: 'section-head' }, [el('h2', { text: '音源' })]));
      body.appendChild(sourcePicker());
    }

    function row(song, i) {
      var node = UI.songRow(song, {
        index: i,
        showSrc: true,
        onTap: function () {
          P.playList(state.list, i, { title: '搜索：' + state.keyword });
        },
        onMore: function (e) {
          UI.openMenu(
            [
              { icon: I.plus, label: '加入歌单', act: function () { S.emit('song:add-to-playlist', song); } },
              { icon: I.list, label: '下一首播放', act: function () { S.emit('song:play-next', song); } },
              { icon: I.heart, label: '收藏', act: function () { S.emit('song:like', song); } },
              { icon: I.download, label: '下载', act: function () { S.emit('song:download', song); } }
            ],
            e
          );
        }
      });
      UI.attachSwipe(node, {
        onSwipeLeft: function () { S.emit('song:download', song); },
        onSwipeRight: function () { S.emit('song:play-next', song); }
      });
      return node;
    }

    function doSearch(kw) {
      kw = String(kw || '').trim();
      if (!kw) {
        showIdle();
        return;
      }
      state.keyword = kw;
      input.value = kw;
      clearBtn.classList.add('is-visible');
      pushHistory(kw);
      body.innerHTML = '';
      body.appendChild(el('div', { class: 'section-head' }, [el('h2', { text: '“' + D.esc(kw) + '” 的结果' })]));
      var sub = el('div', { class: 'search-status', text: '搜索中…' });
      body.appendChild(sub);
      body.appendChild(sourcePicker());
      var listHost = el('div', { class: 'list' });
      body.appendChild(listHost);
      listHost.appendChild(UI.skeletonRows(6));

      state.loading = true;
      B.call('search', { keyword: kw, source: state.source, offset: 0, limit: 60 })
        .then(function (d) {
          state.loading = false;
          state.list = (d && d.list) || [];
          state.total = (d && d.total) || state.list.length;
          sub.textContent = state.total + ' 首 · 来源 ' + (state.source === 'all' ? '全部音源' : state.source);
          listHost.innerHTML = '';
          if (!state.list.length) {
            listHost.appendChild(UI.emptyState('没找到结果', '换个关键词或切换音源'));
            return;
          }
          if (state.list.length > 200) {
            UI.virtualList(listHost, {
              items: state.list,
              rowHeight: 64,
              renderItem: function (song, i) { return row(song, i); }
            });
          } else {
            state.list.forEach(function (song, i) {
              listHost.appendChild(row(song, i));
            });
          }
        })
        .catch(function (e) {
          state.loading = false;
          sub.textContent = '';
          listHost.innerHTML = '';
          listHost.appendChild(UI.emptyState('搜索失败', e.message));
        });
    }

    input.addEventListener('input', function () {
      clearBtn.classList.toggle('is-visible', !!input.value);
      if (!input.value) showIdle();
    });
    input.addEventListener('keydown', function (e) {
      if (e.key === 'Enter') {
        e.preventDefault();
        input.blur();
        doSearch(input.value);
      }
    });
    clearBtn.addEventListener('click', function (e) {
      e.stopPropagation();
      input.value = '';
      clearBtn.classList.remove('is-visible');
      showIdle();
      try {
        input.focus();
      } catch (err) {}
    });

    showIdle();
    page.__search = doSearch;
    // 页面终于挂载了：消费一次别处（原生分享 / 通知栏跳转）挂起的待搜关键词
    var pending = S.state.pendingSearch;
    if (pending && String(pending).trim()) {
      S.setState({ pendingSearch: '' });
      input.value = String(pending);
      clearBtn.classList.add('is-visible');
      setTimeout(function () { doSearch(String(pending)); }, 0);
    }
    return page;
  }

  R.register('search', {
    title: '搜索',
    render: render,
    enter: function () {
      var i = document.querySelector('.search-bar__input');
      if (i) {
        try {
          i.focus();
        } catch (e) {}
      }
    }
  });

  /**
   * 外部入口（原生分享 intent / app.js 的 J4.onShare 都走这里）。
   * 先把关键词写进输入框再真正发起搜索 —— 只调 doSearch 的话输入框是空的，
   * 用户看到结果却不知道搜的是什么，也没法在结果上加限定词。
   * 也没用 document.querySelector('.page--push')：搜索页在 tab 里时没有这个类。
   */
  global.J4Search = {
    doSearch: function (kw) {
      var input = document.querySelector('.search-bar__input');
      if (input) {
        input.value = kw;
        var clear = document.querySelector('.search-bar__clear');
        if (clear) clear.classList.add('is-visible');
      }
      var p = document.querySelector('.page--push');
      if (p && p.__search) {
        p.__search(kw);
        return;
      }
      // 搜索页还没挂载（在别的 tab 上）：先在 store 里挂一个待办，页面渲染时消费
      if (global.J4Store) global.J4Store.setState({ pendingSearch: kw });
    },
    setKeyword: function (kw) {
      var input = document.querySelector('.search-bar__input');
      if (input) {
        input.value = kw;
        var clear = document.querySelector('.search-bar__clear');
        if (clear) clear.classList.add('is-visible');
      }
      if (global.J4Store) global.J4Store.setState({ pendingSearch: kw });
    },
    run: function (kw) {
      var p = document.querySelector('.page--push');
      if (p && p.__search) p.__search(kw);
      else if (global.J4Search) global.J4Search.doSearch(kw);
    }
  };
})(window);
