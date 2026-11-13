/* J4Music · ui/download.js —— 下载管理页
 * 任务列表 + 进度条 + 暂停/取消 + 批量操作
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

  var STATE_LABEL = {
    waiting: '等待中',
    downloading: '下载中',
    done: '已完成',
    failed: '失败',
    canceled: '已取消'
  };

  function render() {
    var page = el('div', { class: 'page page--push' });
    var header = el('div', { class: 'page-header' }, [
      el('button', { class: 'ibtn', 'aria-label': '返回', html: I.back, onclick: function () { R.back(); } }),
      el('div', { class: 'page-header__title', text: '我的下载' }),
      el('button', {
        class: 'ibtn',
        'aria-label': '刷新',
        html: I.refresh,
        onclick: function () { load(true); }
      })
    ]);

    var scroll = el('div', { class: 'page__scroll' });
    var body = el('div', { class: 'page-pad' });
    var hero = el('div', { class: 'pl-hero' }, [
      el('div', { class: 'pl-hero__glow' }),
      el('div', { class: 'cover cover--168' }, [el('div', { class: 'cover__ph' }, [el('span', { html: I.download })])]),
      el('div', { class: 'pl-hero__info' }, [
        el('div', { class: 'pl-hero__name', text: '我的下载' }),
        el('div', { class: 'pl-hero__meta', text: '加载中' })
      ])
    ]);
    var toolbar = el('div', { class: 'pl-toolbar' });
    var summary = el('div', { class: 'search-status', text: '' });
    var listHost = el('div', { class: 'list' });
    var taskHead = el('div', { class: 'section-head' }, [el('h2', { text: '下载任务' })]);
    var taskHost = el('div', { class: 'list' });

    body.appendChild(headerRow());
    body.appendChild(hero);
    body.appendChild(toolbar);
    body.appendChild(summary);
    body.appendChild(listHost);
    body.appendChild(taskHead);
    body.appendChild(taskHost);
    scroll.appendChild(body);
    page.appendChild(header);
    page.appendChild(scroll);

    // 已下载的曲目单独存一份，供「播放全部」用
    var doneSongs = [];

    UI.pullToRefresh(scroll, function () { return reload(); });

    function reload() {
      return B.call('downloadList')
        .then(function (d) {
          var items = (d && d.list) || [];
          S.setState({ downloads: items });
          return paintAll(items);
        })
        .catch(function (e) { UI.toastError(e.message); });
    }

    function headerRow() {
      return el('div', { class: 'row', style: { gap: '10px', marginBottom: '10px' } }, [
        el('button', {
          class: 'btn btn--ghost btn--sm',
          onclick: function () { S.emit('download:to-playlist', {}); }
        }, [el('span', { class: 'ico', html: I.plus }), el('span', { text: '存为本地歌单' })]),
        el('button', {
          class: 'btn btn--ghost btn--sm',
          onclick: function () { S.emit('download:clear-done', {}); }
        }, [el('span', { class: 'ico', html: I.trash }), el('span', { text: '清除已完成' })])
      ]);
    }

    function paintAll(items) {
      return B.call('downloadSongs').then(function (d) {
        doneSongs = (d && d.songs) || [];
        paintSongs();
        paintTasks(items);
        var done = items.filter(function (x) { return x.state === 'done'; }).length;
        var running = items.filter(function (x) { return x.state === 'downloading'; }).length;
        summary.textContent = items.length ? items.length + ' 个任务 · 完成 ' + done + ' · 进行中 ' + running : '';
      });
    }

    function paintSongs() {
      var totalMs = doneSongs.reduce(function (a, s) { return a + (s.durationMs || 0); }, 0);
      hero.querySelector('.pl-hero__meta').textContent =
        doneSongs.length + ' 首' + (totalMs ? ' · ' + D.fmtTime(totalMs).replace(':', ' 分 ') + ' 秒' : '') + ' · 离线可播';
      if (doneSongs[0] && doneSongs[0].coverUrl) {
        var cover = hero.querySelector('.cover');
        if (cover && cover.parentNode) hero.replaceChild(UI.coverEl(doneSongs[0], 168), cover);
      }

      toolbar.innerHTML = '';
      toolbar.appendChild(el('button', {
        class: 'btn btn--primary btn--sm',
        onclick: function () {
          if (!doneSongs.length) return UI.toast('还没有下载完成的歌曲');
          P.playList(doneSongs, 0, { title: '我的下载', playlistId: 'downloads' });
        }
      }, [el('span', { class: 'ico', html: I.play }), el('span', { text: '播放全部' })]));
      toolbar.appendChild(el('button', {
        class: 'ibtn ibtn--sm',
        'aria-label': '上传到云端',
        html: I.cloud,
        onclick: uploadAll
      }));

      listHost.innerHTML = '';
      if (!doneSongs.length) {
        listHost.appendChild(UI.emptyState('还没有下载好的歌', '在任意歌曲菜单里点「下载」，完成后会像歌单一样排在这里'));
        return;
      }
      doneSongs.forEach(function (song, i) {
        var node = UI.songRow(song, {
          index: i,
          showIndex: true,
          showQuality: true,
          onTap: function () {
            P.playList(doneSongs, i, { title: '我的下载', playlistId: 'downloads' });
          },
          onMore: function (e) {
            UI.openMenu([
              { icon: I.plus, label: '加入歌单', act: function () { S.emit('song:add-to-playlist', song); } },
              { icon: I.list, label: '下一首播放', act: function () { S.emit('song:play-next', song); } },
              { icon: I.heart, label: '收藏', act: function () { S.emit('song:like', song); } }
            ], e);
          }
        });
        UI.attachSwipe(node, {
          onSwipeLeft: function () { S.emit('song:play-next', song); },
          onSwipeRight: function () { S.emit('song:like', song); }
        });
        listHost.appendChild(node);
      });
    }

    function uploadAll() {
      if (!doneSongs.length) return UI.toast('还没有下载完成的歌曲');
      B.call('playlistCreateFromSongs', { name: '我的下载', songs: doneSongs, kind: 'cloud' })
        .then(function () {
          UI.toast('已上传「我的下载」到云端', 'success');
          S.emit('playlists:changed', {});
        })
        .catch(function (e) { UI.toastError('上传失败：' + e.message); });
    }

    function paintTasks(items) {
      taskHost.innerHTML = '';
      var pending = items.filter(function (x) { return x.state !== 'done'; });
      taskHead.style.display = pending.length ? '' : 'none';
      if (!pending.length) return;
      pending.forEach(function (t) { taskHost.appendChild(taskRow(t)); });
    }

    function taskRow(t) {
      var pct = Math.max(0, Math.min(1, (t.progress || 0) / (t.progress > 1 ? 100 : 1)));
      var row = el('div', { class: 'task-row', dataset: { task: t.taskId || t.songId } }, [
        UI.coverEl({ id: t.songId, title: t.title }, 48),
        el('div', { class: 'task-row__body' }, [
          el('div', { class: 'task-row__title ellipsis', text: t.title || '未知歌曲' }),
          el('div', { class: 'task-progress' }, [el('i', { style: { transform: 'scaleX(' + pct + ')' } })]),
          el('div', { class: 'task-state' }, [
            el('span', { text: STATE_LABEL[t.state] || t.state || '' }),
            el('span', { class: 'mono', text: Math.round(pct * 100) + '%' })
          ])
        ]),
        el('button', {
          class: 'song-row__more',
          html: I.close,
          'aria-label': '取消',
          onclick: function (e) {
            e.stopPropagation();
            if (t.state === 'done') {
              UI.toast('已在本地：' + (t.path || '下载目录'));
              return;
            }
            B.call('downloadCancel', { taskId: t.taskId })
              .then(function () {
                t.state = 'canceled';
                load(false);
                UI.toast('已取消');
              })
              .catch(function (err) { UI.toastError(err.message); });
          }
        })
      ]);
      return row;
    }

    function load(notify) {
      B.call('downloadList')
        .then(function (d) {
          var items = (d && d.list) || [];
          S.setState({ downloads: items });
          return paintAll(items).then(function () { if (notify) UI.toast('已刷新'); });
        })
        .catch(function (e) {
          summary.textContent = '';
          if (notify) UI.toastError(e.message);
        });
    }

    load(false);

    // 原生/模拟进度推送：只更新进度条，不重建 DOM
    // UI.onDownloadTick 是一个可被 app.js 替换的钩子（默认 null），这里做容错注册
    var tickHandler = function (tick) {
      if (!tick) return;
      var node = taskHost.querySelector('.task-row[data-task="' + (tick.taskId || tick.songId) + '"]');
      if (!node) {
        load(false);
        return;
      }
      var pct = Math.max(0, Math.min(1, (tick.progress || 0) / 100));
      var bar = node.querySelector('.task-progress i');
      if (bar) bar.style.transform = 'scaleX(' + pct + ')';
      var st = node.querySelector('.task-state span');
      if (st) st.textContent = (STATE_LABEL[tick.state] || tick.state || '') + ' · ' + Math.round(pct * 100) + '%';
    };
    var tickSubs = window.__J4DownloadTickSubs || (window.__J4DownloadTickSubs = []);
    tickSubs.push(tickHandler);
    var unsub = function () {
      var i = tickSubs.indexOf(tickHandler);
      if (i >= 0) tickSubs.splice(i, 1);
    };

    page.addEventListener('j4:leave', function () {
      if (unsub) unsub();
    });

    return page;
  }

  R.register('download', { title: '我的下载', render: render });
})(window);
