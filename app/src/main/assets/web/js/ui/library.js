/* J4Music · ui/library.js —— 我的 Tab
 * 头像统计 + 收藏/本地/下载入口 + 歌单管理（新建/重命名/删除/分享/排序）
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

  function favCount() {
    var f = S.state.favorites || {};
    return Object.keys(f).length;
  }

  /** 歌单类型的中文标签：新建的默认是云歌单，列表里要看得出来 */
  function kindLabel(p) {
    if (p.kind === 'cloud') return '云歌单';
    if (p.kind === 'local') return '本地';
    return p.source || '本地';
  }

  function render() {
    var page = el('div', { class: 'page page--tab' });
    var scroll = el('div', { class: 'page__scroll' });
    var inner = el('div', { class: 'page-pad' });

    var head = el('div', { class: 'page-header' }, [
      el('h1', { text: '我的' }),
      el('button', {
        class: 'ibtn',
        'aria-label': '设置',
        html: I.settings,
        onclick: function () { R.switchTab('settings'); }
      })
    ]);
    inner.appendChild(head);

    inner.appendChild(
      el('div', { class: 'profile-head' }, [
        el('div', { class: 'avatar' }, [el('span', { text: 'J4' })]),
        el('div', { class: 'profile-head__info' }, [
          el('div', { class: 'profile-head__name', text: 'J4 Music' }),
          el('div', { class: 'profile-head__sub', text: S.state.bridgeMock ? '预览模式 · mock 数据' : '本地播放器' })
        ]),
        el('button', {
          class: 'btn btn--ghost btn--sm',
          onclick: function () { R.push('login', {}); }
        }, [el('span', { class: 'ico', html: I.crown }), el('span', { text: '会员' })])
      ])
    );

    inner.appendChild(
      el('div', { class: 'stat-row' }, [
        stat(favCount(), '收藏', function () { openFavorites(); }),
        stat((S.state.localSongs || []).length, '本地', function () { R.push('local'); }),
        stat((S.state.downloads || []).length, '下载', function () { R.push('download'); }),
        stat((S.state.playlists || []).length, '歌单', function () { scrollToPlaylists(inner); })
      ])
    );

    inner.appendChild(cellsBlock());

    var plHead = el('div', { class: 'section-head', id: 'libPlaylists' }, [
      el('h2', { text: '我的歌单' }),
      el('div', { class: 'row', style: { gap: '6px' } }, [
        el('button', {
          class: 'ibtn ibtn--sm',
          'aria-label': '从云端拉取歌单',
          html: I.cloud,
          onclick: function () { pullCloud(); }
        }),
        el('button', {
          class: 'ibtn ibtn--sm',
          'aria-label': '导入歌单',
          html: I.sync,
          onclick: function () { S.emit('playlists:import', {}); }
        }),
        el('button', {
          class: 'ibtn ibtn--sm',
          'aria-label': '新建歌单',
          html: I.plus,
          onclick: function () { createPlaylist(); }
        })
      ])
    ]);
    inner.appendChild(plHead);

    var listHost = el('div', { class: 'pl-list' });
    inner.appendChild(listHost);
    renderPlaylists(listHost);

    var createBtn = el('button', {
      class: 'btn btn--ghost btn--block',
      style: { marginTop: '14px' },
      onclick: function () { createPlaylist(); }
    }, [el('span', { class: 'ico', html: I.plus }), el('span', { text: '新建歌单' })]);
    inner.appendChild(createBtn);

    scroll.appendChild(inner);
    page.appendChild(scroll);
    return page;
  }

  function scrollToPlaylists(inner) {
    var t = inner.querySelector('#libPlaylists');
    if (t && t.scrollIntoView) t.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  function stat(n, label, act) {
    return el('button', { class: 'stat', onclick: act }, [
      el('div', { class: 'stat__n', text: String(n) }),
      el('div', { class: 'stat__l', text: label })
    ]);
  }

  function cellsBlock() {
    var items = [
      { icon: I.heart, label: '我的收藏', act: openFavorites },
      { icon: I.folder, label: '本地音乐', act: function () { R.push('local'); } },
      { icon: I.download, label: '下载管理', act: function () { R.push('download'); } },
      { icon: I.translate, label: '音乐转译', act: function () { R.push('translate'); } }
    ];
    var host = el('div', { class: 'card', style: { padding: '4px 0' } });
    items.forEach(function (it) {
      var row = el('div', { class: 'cell', onclick: function () { it.act(); } }, [
        el('span', { class: 'cell__icon', html: it.icon }),
        el('div', { class: 'cell__body' }, [el('div', { class: 'cell__label', text: it.label })]),
        el('span', { class: 'cell__arrow', html: I.chevron })
      ]);
      host.appendChild(row);
    });
    return host;
  }

  function renderPlaylists(host) {
    host.innerHTML = '';
    var list = S.state.playlists || [];
    if (!list.length) {
      host.appendChild(UI.emptyState('还没有歌单', '点右上角 + 新建，或从其它软件导入'));
      return;
    }
    list.forEach(function (p) {
      var row = el('div', { class: 'pl-row' }, [
        UI.coverEl({ id: p.id, title: p.name, coverUrl: p.cover }, 56),
        el('div', { class: 'pl-row__body' }, [
          el('div', { class: 'pl-row__name ellipsis', text: p.name }),
          el('div', { class: 'pl-row__meta', text: (p.songCount || 0) + ' 首 · ' + kindLabel(p) + (p.updatedAt ? ' · ' + D.fmtAgo(p.updatedAt) : '') })
        ]),
        el('button', {
          class: 'song-row__more',
          html: I.more,
          'aria-label': '更多',
          onclick: function (e) {
            e.stopPropagation();
            plMenu(p);
          }
        })
      ]);
      row.addEventListener('click', function () {
        R.push('playlist', { id: p.id });
      });
      host.appendChild(row);
    });
  }

  function plMenu(p) {
    UI.actionSheet(
      [
        { icon: I.play, label: '播放全部', act: function () { playAll(p.id); } },
        { icon: I.edit, label: '重命名', act: function () { renamePlaylist(p); } },
        { icon: I.share, label: '分享歌单', act: function () { sharePlaylist(p); } },
        { icon: I.sync, label: '同步到云端', act: function () { UI.toast('已排队同步：' + p.name, 'info'); } },
        { icon: I.trash, label: '删除歌单', danger: true, act: function () { deletePlaylist(p); } }
      ],
      { title: p.name, sub: (p.songCount || 0) + ' 首' }
    );
  }

  function playAll(id) {
    B.call('playlistGet', { id: id })
      .then(function (d) {
        var songs = (d && d.songs) || [];
        if (!songs.length) {
          UI.toast('歌单是空的');
          return;
        }
        P.playList(songs, 0, { title: (d.playlist && d.playlist.name) || '歌单', playlistId: id });
      })
      .catch(function (e) { UI.toastError(e.message); });
  }

  /** 从云端把歌单拉回来（换设备/重装后一条命令全恢复） */
  function pullCloud() {
    UI.toast('正在从云端拉取…', 'info', 1500);
    B.call('cloudPlaylistPullAll')
      .then(function (d) {
        var added = (d && d.added) || 0;
        var updated = (d && d.updated) || 0;
        var failed = (d && d.failed) || 0;
        if (!added && !updated && !failed) {
          UI.toast('云上还没有歌单');
          return;
        }
        UI.toast('已同步：新增 ' + added + ' · 更新 ' + updated + (failed ? ' · 失败 ' + failed : ''), 'success', 2400);
        S.emit('playlists:changed', {});
      })
      .catch(function (e) { UI.toastError('拉取失败：' + e.message); });
  }

  function createPlaylist() {    UI.prompt({
      title: '新建歌单',
      placeholder: '歌单名称',
      okText: '创建',
      onSubmit: function (name) {
        if (!name || !name.trim()) throw new Error('名称不能为空');
        return B.call('playlistCreate', { name: name.trim() }).then(function (d) {
          UI.toast('已创建「' + name.trim() + '」', 'success');
          S.emit('playlists:changed', {});
          return d.id;
        });
      }
    });
  }

  function renamePlaylist(p) {
    UI.prompt({
      title: '重命名歌单',
      value: p.name,
      okText: '保存',
      onSubmit: function (name) {
        if (!name || !name.trim()) throw new Error('名称不能为空');
        return B.call('playlistRename', { id: p.id, name: name.trim() }).then(function () {
          p.name = name.trim();
          S.emit('playlists:changed', {});
          UI.toast('已重命名');
        });
      }
    });
  }

  function deletePlaylist(p) {
    UI.confirm({
      title: '删除歌单',
      message: '确定删除「' + p.name + '」？该操作只影响歌单，不会删除本地文件。',
      okText: '删除',
      danger: true
    }).then(function (ok) {
      if (!ok) return;
      B.call('playlistDelete', { id: p.id }).then(function () {
        S.state.playlists = (S.state.playlists || []).filter(function (x) { return x.id !== p.id; });
        S.emit('playlists:changed', {});
        UI.toast('已删除');
      }).catch(function (e) { UI.toastError(e.message); });
    });
  }

  function sharePlaylist(p) {
    B.call('playlistExport', { id: p.id })
      .then(function (d) {
        var text = (d && d.text) || '';
        var sheet = UI.openSheet({
          title: '分享歌单',
          sub: '复制下面的文本发给朋友，或导入到其它设备',
          render: function (ctx) {
            var ta = el('textarea', { class: 'field__input', rows: 6, readonly: true, style: { fontFamily: 'var(--font-num)', fontSize: '12px' } });
            ta.value = text;
            var btn = el('button', {
              class: 'btn btn--primary btn--block',
              onclick: function () {
                copyText(text);
                UI.toast('已复制到剪贴板', 'success');
                ctx.close();
              }
            }, [el('span', { text: '复制分享文本' })]);
            return el('div', { class: 'col', style: { padding: '0 18px 18px', gap: '12px' } }, [ta, btn]);
          }
        });
        return sheet;
      })
      .catch(function (e) { UI.toastError('生成分享失败：' + e.message); });
  }

  function copyText(text) {
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text);
        return;
      }
    } catch (e) {}
    var ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    try { document.execCommand('copy'); } catch (e) {}
    document.body.removeChild(ta);
  }

  function openFavorites() {
    // 收藏页现在是一张「歌单」，空的时候也进去（里面有空态提示），不再只是弹个 toast
    R.push('favorites');
  }

  function init() {
    S.on('playlists:changed', function () {
      var host = document.querySelector('.page--tab .pl-list');
      if (host) renderPlaylists(host);
    });
  }

  R.register('library', { title: '我的', render: render, enter: init });
  global.J4Lib = { copyText: copyText, renderPlaylists: renderPlaylists };
})(window);
