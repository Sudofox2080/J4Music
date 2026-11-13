/* J4Music · ui/favorites.js —— 我的收藏（做成歌单的样子）
 * 与歌单页同一套结构：头图 + 播放全部 + 行列表 + 左滑取消收藏 + 下拉刷新
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

  var songs = [];

  function render() {
    var page = el('div', { class: 'page page--push' });
    var scroll = el('div', { class: 'page__scroll' });

    var header = el('div', { class: 'page-header page-header--float' }, [
      el('button', { class: 'ibtn ibtn--glass', 'aria-label': '返回', html: I.back, onclick: function () { R.back(); } }),
      el('div', { class: 'page-header__title', text: '我的收藏' }),
      el('button', {
        class: 'ibtn ibtn--glass',
        'aria-label': '更多',
        html: I.more,
        onclick: function (e) { menu(e); }
      })
    ]);

    var hero = el('div', { class: 'pl-hero' }, [
      el('div', { class: 'pl-hero__glow' }),
      el('div', { class: 'cover cover--168' }, [el('div', { class: 'cover__ph' }, [el('span', { text: '♥' })])]),
      el('div', { class: 'pl-hero__info' }, [
        el('div', { class: 'pl-hero__name', text: '我的收藏' }),
        el('div', { class: 'pl-hero__meta', text: '加载中' })
      ])
    ]);

    var toolbar = el('div', { class: 'pl-toolbar' });
    var listHost = el('div', { class: 'list', style: { paddingBottom: '24px' } });

    scroll.appendChild(hero);
    scroll.appendChild(toolbar);
    scroll.appendChild(listHost);
    page.appendChild(header);
    page.appendChild(scroll);

    UI.pullToRefresh(scroll, function () { return load(true); });

    load(false);

    function load(fromPull) {
      return B.call('favoritesGet')
        .then(function (d) {
          songs = (d && d.songs) || [];
          syncStore();
          paint();
          if (fromPull) UI.toast('已刷新收藏', 'info', 1200);
        })
        .catch(function (e) {
          listHost.innerHTML = '';
          listHost.appendChild(UI.emptyState('读取收藏失败', e.message));
          if (fromPull) UI.toastError(e.message);
        });
    }

    function syncStore() {
      var map = {};
      songs.forEach(function (s) { map[s.id] = s; });
      S.setState({ favorites: map });
    }

    function paint() {
      var totalMs = songs.reduce(function (a, s) { return a + (s.durationMs || 0); }, 0);
      hero.querySelector('.pl-hero__meta').textContent =
        songs.length + ' 首 · ' + (totalMs ? D.fmtTime(totalMs).replace(':', ' 分 ') + ' 秒' : '还没有时长') + ' · 本地';
      var first = songs[0];
      var cover = hero.querySelector('.cover');
      if (first && first.coverUrl) {
        var fresh = UI.coverEl(first, 168);
        if (cover && cover.parentNode) hero.replaceChild(fresh, cover);
      }

      toolbar.innerHTML = '';
      toolbar.appendChild(el('button', {
        class: 'btn btn--primary btn--sm',
        onclick: function () {
          if (!songs.length) return UI.toast('还没有收藏的歌曲');
          P.playList(songs, 0, { title: '我的收藏', playlistId: 'favorites' });
        }
      }, [el('span', { class: 'ico', html: I.play }), el('span', { text: '播放全部' })]));
      toolbar.appendChild(el('button', {
        class: 'btn btn--ghost btn--sm',
        onclick: function () {
          if (!songs.length) return UI.toast('还没有收藏的歌曲');
          P.playList(shuffle(songs.slice()), 0, { title: '我的收藏 · 随机', playlistId: 'favorites' });
        }
      }, [el('span', { class: 'ico', html: I.shuffle }), el('span', { text: '随机' })]));
      toolbar.appendChild(el('button', {
        class: 'ibtn ibtn--sm',
        'aria-label': '上传到云端',
        html: I.cloud,
        onclick: function () { uploadToCloud(); }
      }));
      toolbar.appendChild(el('button', {
        class: 'ibtn ibtn--sm',
        'aria-label': '更多操作',
        html: I.moreV,
        onclick: function (e) { menu(e); }
      }));

      listHost.innerHTML = '';
      if (!songs.length) {
        listHost.appendChild(UI.emptyState('还没有收藏', '在任意歌曲的菜单里点「收藏」，这里会像歌单一样排好'));
        return;
      }
      songs.forEach(function (song, i) { listHost.appendChild(row(song, i)); });
    }

    function row(song, i) {
      var node = UI.songRow(song, {
        index: i,
        showIndex: true,
        showQuality: true,
        onTap: function () {
          P.playList(songs, i, { title: '我的收藏', playlistId: 'favorites' });
        },
        onMore: function (e) {
          UI.openMenu([
            { icon: I.plus, label: '加入歌单', act: function () { S.emit('song:add-to-playlist', song); } },
            { icon: I.list, label: '下一首播放', act: function () { S.emit('song:play-next', song); } },
            { icon: I.download, label: '下载', act: function () { S.emit('song:download', song); } },
            { icon: I.heart, label: '取消收藏', danger: true, act: function () { unfavorite(song); } }
          ], e);
        }
      });
      UI.attachSwipe(node, {
        onSwipeLeft: function () { unfavorite(song); },
        onSwipeRight: function () { S.emit('song:play-next', song); }
      });
      return node;
    }

    function unfavorite(song) {
      B.call('favoritesToggle', { song: song })
        .then(function (d) {
          songs = songs.filter(function (s) { return s.id !== song.id; });
          syncStore();
          paint();
          UI.toast(d && d.liked ? '已收藏' : '已取消收藏');
        })
        .catch(function (e) { UI.toastError(e.message); });
    }

    function uploadToCloud() {
      if (!songs.length) return UI.toast('还没有收藏的歌曲');
      B.call('playlistCreateFromSongs', { name: '我的收藏', songs: songs, kind: 'cloud' })
        .then(function (d) {
          UI.toast('已上传到云端「我的收藏」', 'success');
          S.emit('playlists:changed', {});
          return d;
        })
        .catch(function (e) { UI.toastError('上传失败：' + e.message); });
    }

    function menu(e) {
      UI.openMenu([
        { icon: I.cloud, label: '上传到云端（变成云歌单）', act: uploadToCloud },
        { icon: I.plus, label: '全部加入某个歌单', act: function () { S.emit('playlists:import-songs', { songs: songs }); } },
        { icon: I.trash, label: '清空收藏', danger: true, act: clearAll }
      ], e);
    }

    function clearAll() {
      UI.confirm({ title: '清空收藏', message: '把收藏的 ' + songs.length + ' 首歌全部移除？', okText: '清空', danger: true })
        .then(function (ok) {
          if (!ok) return;
          B.call('favoritesClear').then(function () {
            songs = [];
            syncStore();
            paint();
            UI.toast('已清空收藏');
          });
        });
    }

    function shuffle(arr) {
      for (var i = arr.length - 1; i > 0; i--) {
        var j = Math.floor(Math.random() * (i + 1));
        var t = arr[i]; arr[i] = arr[j]; arr[j] = t;
      }
      return arr;
    }

    return page;
  }

  R.register('favorites', { title: '我的收藏', render: render });
})(window);
