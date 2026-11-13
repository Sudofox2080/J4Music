/* J4Music · ui/home.js —— 首页 Tab
 * 问候 + 快捷入口 + 推荐歌单 + 每日推荐歌曲（虚拟滚动）
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

  function greeting() {
    var h = new Date().getHours();
    if (h < 5) return '夜深了';
    if (h < 11) return '早上好';
    if (h < 14) return '中午好';
    if (h < 18) return '下午好';
    if (h < 23) return '晚上好';
    return '夜深了';
  }

  function quickRow() {
    var items = [
      { icon: I.search, label: '搜索', act: function () { R.push('search'); } },
      { icon: I.audio, label: '本地音乐', act: function () { R.push('local'); } },
      { icon: I.download, label: '下载', act: function () { R.push('download'); } },
      { icon: I.translate, label: '音乐转译', act: function () { R.push('translate'); } }
    ];
    return el(
      'div',
      { class: 'quick-grid' },
      items.map(function (it) {
        return el('button', { class: 'quick-item', onclick: function () { it.act(); } }, [
          el('span', { class: 'quick-item__icon', html: it.icon }),
          el('span', { class: 'quick-item__label', text: it.label })
        ]);
      })
    );
  }

  function playlistStrip() {
    var list = S.state.playlists || [];
    var head = el('div', { class: 'section-head' }, [
      el('h2', { text: '我的歌单' }),
      el('button', { class: 'link', text: '全部 ›', onclick: function () { R.switchTab('library'); } })
    ]);
    if (!list.length) {
      return el('div', { class: 'home-pl-wrap' }, [
        head,
        UI.emptyState('还没有歌单', '在「我的」里新建一个')
      ]);
    }
    var strip = el(
      'div',
      { class: 'h-scroll' },
      list.map(function (p) {
        return el('div', {
          class: 'pl-tile',
          onclick: function () { R.push('playlist', { id: p.id }); }
        }, [
          UI.coverEl({ id: p.id, title: p.name, coverUrl: p.cover }, 132),
          el('div', { class: 'pl-tile__name ellipsis', text: p.name }),
          el('div', { class: 'pl-tile__meta', text: (p.songCount || 0) + ' 首' })
        ]);
      })
    );
    return el('div', { class: 'home-pl-wrap' }, [head, strip]);
  }

  function dailySection(host) {
    var head = el('div', { class: 'section-head' }, [
      el('h2', { text: '每日推荐' }),
      el('button', {
        class: 'link',
        text: '播放全部',
        onclick: function () {
          var songs = S.state.songs || [];
          if (songs.length) P.playList(songs, 0, { title: '每日推荐' });
        }
      })
    ]);
    var body = el('div', { class: 'list', id: 'homeDailyList' });
    host.appendChild(head);
    host.appendChild(body);

    var songs = S.state.songs || [];
    if (!songs.length) {
      body.appendChild(UI.skeletonRows(6));
      return;
    }
    // 超过 200 项走虚拟滚动
    if (songs.length > 200) {
      UI.virtualList(body, {
        items: songs,
        rowHeight: 64,
        renderItem: function (song, i) {
          return makeRow(song, i, songs);
        }
      });
    } else {
      songs.forEach(function (song, i) {
        body.appendChild(makeRow(song, i, songs));
      });
    }
  }

  function makeRow(song, i, songs) {
    var row = UI.songRow(song, {
      index: i,
      showIndex: true,
      showQuality: false,
      onTap: function () {
        P.playList(songs, i, { title: '每日推荐' });
      },
      onMore: function (e) {
        UI.openMenu(
          [
            { icon: I.plus, label: '加入歌单', act: function () { S.emit('song:add-to-playlist', song); } },
            { icon: I.heart, label: '收藏', act: function () { S.emit('song:like', song); } },
            { icon: I.download, label: '下载', act: function () { S.emit('song:download', song); } },
            { icon: I.list, label: '下一首播放', act: function () { S.emit('song:play-next', song); } }
          ],
          e
        );
      }
    });
    return row;
  }

  function render(ctx) {
    var page = el('div', { class: 'page page--tab' });
    var scroll = el('div', { class: 'page__scroll' });
    var inner = el('div', { class: 'page-pad' });

    inner.appendChild(
      el('div', { class: 'greeting' }, [
        el('div', { class: 'greeting__text' }, [
          el('div', { class: 'greeting__hi', text: greeting() + '，' }),
          el('div', { class: 'greeting__title', text: '今天想听点什么' })
        ]),
        el('button', {
          class: 'greeting__search',
          'aria-label': '搜索',
          onclick: function () { R.push('search'); }
        }, [el('span', { class: 'ico', html: I.search })])
      ])
    );

    inner.appendChild(quickRow());
    inner.appendChild(playlistStrip());

    var dailyWrap = el('div', {});
    inner.appendChild(dailyWrap);
    dailySection(dailyWrap);

    if (global.J4Bridge.mock || !S.state.songs || !S.state.songs.length) {
      loadDaily(inner.querySelector('#homeDailyList'), dailyWrap);
    }

    scroll.appendChild(inner);
    page.appendChild(scroll);

    UI.pullToRefresh(scroll, refreshAll);
    watchPlaylists();
    return page;
  }

  /**
   * 首页下拉刷新：先把云端歌单同步下来，再刷歌单横条与每日推荐。
   * 云端没配置时 cloudPlaylistPullAll 会直接失败，这里吞掉即可，
   * 不能因为服务器连不上就让整个下拉卡住。
   */
  function refreshAll() {
    var jobs = [];

    jobs.push(B.call('cloudPlaylistPullAll')
      .then(function (d) {
        if (d && ((d.added || 0) + (d.updated || 0) > 0)) {
          UI.toast('云端歌单已同步：新增 ' + (d.added || 0) + ' · 更新 ' + (d.updated || 0), 'success', 2000);
        }
      })
      .catch(function () {}));

    jobs.push(B.call('playlistList')
      .then(function (d) { S.setState({ playlists: (d && d.list) || [] }); })
      .catch(function () {}));

    return Promise.all(jobs).then(function () {
      var old = document.querySelector('.page--tab .home-pl-wrap');
      if (old && old.parentNode) old.parentNode.replaceChild(playlistStrip(), old);
      var listEl = document.getElementById('homeDailyList');
      var dailyWrap = listEl && listEl.parentNode ? listEl.parentNode : null;
      return loadDaily(listEl, dailyWrap);
    });
  }

  function loadDaily(listEl, dailyWrap) {
    B.call('search', { keyword: '每日推荐', source: S.state.activeSource || 'all', offset: 0, limit: 30 })
      .then(function (d) {
        var list = (d && d.list) || [];
        S.state.songs = list;
        if (!list.length) return;
        if (listEl) listEl.innerHTML = '';
        var host = listEl || (dailyWrap && dailyWrap.querySelector('.list'));
        if (!host) return;
        host.innerHTML = '';
        if (list.length > 200) {
          UI.virtualList(host, {
            items: list,
            rowHeight: 64,
            renderItem: function (song, i) { return makeRow(song, i, list); }
          });
        } else {
          list.forEach(function (song, i) {
            host.appendChild(makeRow(song, i, list));
          });
        }
      })
      .catch(function (e) {
        B.log('w', 'home daily failed: ' + e.message);
      });
  }

  /**
   * 首页渲染早于 playlistList 返回（启动时两者并发），
   * 所以拿到歌单后要能把「还没有歌单」那块换掉，否则重启进来总是空态。
   */
  function watchPlaylists() {
    if (watchPlaylists.done) return;
    watchPlaylists.done = true;
    S.subscribe(function () {
      var wrap = document.querySelector('.page--tab .home-pl-wrap');
      if (!wrap) return;
      var list = S.state.playlists || [];
      var shown = wrap.querySelectorAll('.pl-tile').length;
      var emptyShown = !!wrap.querySelector('.empty');
      if (list.length && (emptyShown || shown !== list.length)) {
        wrap.parentNode.replaceChild(playlistStrip(), wrap);
      }
    });
  }

  R.register('home', { title: '首页', render: render, enter: watchPlaylists });
})(window);