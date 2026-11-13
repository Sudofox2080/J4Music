/* J4Music · ui/playlist.js —— 歌单详情页（推入式）
 * 头图 + 播放全部 + 歌曲列表（虚拟滚动）+ 排序 / 移出 / 分享 / 下载
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

  function render(ctx) {
    var id = (ctx.params && ctx.params.id) || '';
    var page = el('div', { class: 'page page--push' });
    var scroll = el('div', { class: 'page__scroll' });

    var header = el('div', { class: 'page-header page-header--float' }, [
      el('button', { class: 'ibtn ibtn--glass', 'aria-label': '返回', html: I.back, onclick: function () { R.back(); } }),
      el('div', { class: 'page-header__title ellipsis', text: (ctx.params && ctx.params.name) || '歌单' }),
      el('button', {
        class: 'ibtn ibtn--glass',
        'aria-label': '更多',
        html: I.more,
        onclick: function (e) {
          if (intro) menu(intro.playlist, e);
        }
      })
    ]);

    var hero = el('div', { class: 'pl-hero' }, [
      el('div', { class: 'pl-hero__glow' }),
      UI.coverEl({ id: id, title: (ctx.params && ctx.params.name) || '歌单' }, 168),
      el('div', { class: 'pl-hero__info' }, [
        el('div', { class: 'pl-hero__name', text: '加载中' }),
        el('div', { class: 'pl-hero__meta', text: '—' })
      ])
    ]);

    var toolbar = el('div', { class: 'pl-toolbar' });
    var listHost = el('div', { class: 'list', style: { paddingBottom: '24px' } });

    scroll.appendChild(hero);
    scroll.appendChild(toolbar);
    scroll.appendChild(listHost);
    page.appendChild(header);
    page.appendChild(scroll);

    var intro = { playlist: { id: id, name: (ctx.params && ctx.params.name) || '歌单' }, songs: [] };

    UI.pullToRefresh(scroll, function () { return reload(); });

    load();

    function reload() {
      return B.call('playlistGet', { id: id })
        .then(function (d) {
          intro.playlist = (d && d.playlist) || intro.playlist;
          intro.songs = (d && d.songs) || [];
          paint();
          UI.toast('已刷新', 'info', 1200);
        })
        .catch(function (e) { UI.toastError(e.message); });
    }

    function load() {
      listHost.appendChild(UI.skeletonRows(6));
      B.call('playlistGet', { id: id })
        .then(function (d) {
          intro.playlist = (d && d.playlist) || intro.playlist;
          intro.songs = (d && d.songs) || [];
          paint();
        })
        .catch(function (e) {
          listHost.innerHTML = '';
          listHost.appendChild(UI.emptyState('加载失败', e.message));
        });
    }

    function paint() {
      var pl = intro.playlist;
      var cover = hero.querySelector('.cover');
      if (cover) {
        hero.replaceChild(UI.coverEl(pl, 168), cover);
      }
      hero.querySelector('.pl-hero__name').textContent = pl.name || '未命名歌单';
      var totalMs = intro.songs.reduce(function (a, s) { return a + (s.durationMs || 0); }, 0);
      var kindText = pl.kind === 'local' ? '本地歌单' : (pl.kind === 'cloud' ? '云歌单' : (pl.source || '本地'));
      hero.querySelector('.pl-hero__meta').textContent =
        intro.songs.length + ' 首 · ' + D.fmtTime(totalMs).replace(':', ' 分 ') + ' 秒 · ' + kindText;

      toolbar.innerHTML = '';
      toolbar.appendChild(
        el('button', {
          class: 'btn btn--primary btn--sm',
          onclick: function () {
            if (!intro.songs.length) return UI.toast('歌单是空的');
            P.playList(intro.songs, 0, { title: pl.name, playlistId: pl.id });
          }
        }, [el('span', { class: 'ico', html: I.play }), el('span', { text: '播放全部' })])
      );
      toolbar.appendChild(
        el('button', {
          class: 'btn btn--ghost btn--sm',
          onclick: function () {
            if (!intro.songs.length) return UI.toast('歌单是空的');
            S.emit('queue:shuffle', {});
            var shuffled = intro.songs.slice();
            for (var i = shuffled.length - 1; i > 0; i--) {
              var j = Math.floor(Math.random() * (i + 1));
              var t = shuffled[i]; shuffled[i] = shuffled[j]; shuffled[j] = t;
            }
            P.playList(shuffled, 0, { title: pl.name + ' · 随机', playlistId: pl.id });
          }
        }, [el('span', { class: 'ico', html: I.shuffle }), el('span', { text: '随机' })])
      );
      toolbar.appendChild(
        el('button', {
          class: 'ibtn ibtn--sm',
          'aria-label': '分享',
          html: I.share,
          onclick: function () { share(pl); }
        })
      );
      toolbar.appendChild(
        el('button', {
          class: 'ibtn ibtn--sm',
          'aria-label': '下载全部',
          html: I.download,
          onclick: function () { downloadAll(pl); }
        })
      );
      toolbar.appendChild(
        el('button', {
          class: 'ibtn ibtn--sm',
          'aria-label': '更多操作',
          html: I.moreV,
          onclick: function (e) { menu(pl, e); }
        })
      );

      listHost.innerHTML = '';
      if (!intro.songs.length) {
        listHost.appendChild(
          el('div', { class: 'empty' }, [
            el('div', { class: 'note-dots', html: '<i></i><i></i><i></i>' }),
            el('div', { class: 'empty__title', text: '歌单里还没有歌' }),
            el('div', { class: 'empty__sub', text: '去搜索，或在歌曲菜单里「加入歌单」' }),
            el('button', { class: 'btn btn--primary btn--sm', style: { marginTop: '14px' }, onclick: function () { R.push('search'); } }, [el('span', { text: '去搜索' })])
          ])
        );
        return;
      }
      if (intro.songs.length > 200) {
        UI.virtualList(listHost, {
          items: intro.songs,
          rowHeight: 64,
          renderItem: function (song, i) { return row(song, i); }
        });
      } else {
        intro.songs.forEach(function (song, i) {
          listHost.appendChild(row(song, i));
        });
      }
    }

    function row(song, i) {
      var node = UI.songRow(song, {
        index: i,
        showIndex: true,
        showQuality: true,
        onTap: function () {
          P.playList(intro.songs, i, { title: intro.playlist.name, playlistId: id });
        },
        onMore: function (e) {
          UI.openMenu(
            [
              { icon: I.plus, label: '加入歌单', act: function () { S.emit('song:add-to-playlist', song); } },
              { icon: I.list, label: '下一首播放', act: function () { S.emit('song:play-next', song); } },
              { icon: I.download, label: '下载', act: function () { S.emit('song:download', song); } },
              { icon: I.heart, label: '收藏', act: function () { S.emit('song:like', song); } },
              { icon: I.trash, label: '从歌单移除', danger: true, act: function () { removeSong(song); } }
            ],
            e
          );
        }
      });
      // 左滑：移出歌单 / 右滑：下一首
      UI.attachSwipe(node, {
        onSwipeLeft: function () { removeSong(song); },
        onSwipeRight: function () { S.emit('song:play-next', song); }
      });
      return node;
    }

    function removeSong(song) {
      B.call('playlistRemoveSong', { id: id, songId: song.id })
        .then(function () {
          intro.songs = intro.songs.filter(function (s) { return s.id !== song.id; });
          paint();
          UI.toast('已移出歌单');
          S.emit('playlists:changed', {});
        })
        .catch(function (e) { UI.toastError(e.message); });
    }

    function menu(pl, e) {
      UI.openMenu(
        [
          { icon: I.cloud, label: '上传到云端', act: function () { upload(pl); } },
          { icon: I.edit, label: '重命名', act: function () { rename(pl); } },
          { icon: I.share, label: '分享歌单', act: function () { share(pl); } },
          { icon: I.download, label: '下载全部', act: function () { downloadAll(pl); } },
          { icon: I.sync, label: '导出文本给其它软件', act: function () { S.emit('playlists:sync', pl); } },
          { icon: I.trash, label: '删除歌单', danger: true, act: function () { del(pl); } }
        ],
        e
      );
    }

    function upload(pl) {
      UI.toast('正在上传…', 'info', 1200);
      B.call('cloudPlaylistPush', { id: pl.id })
        .then(function (d) {
          UI.toast('已上传「' + pl.name + '」' + ((d && d.songCount) || intro.songs.length) + ' 首到云端', 'success', 2200);
          S.emit('playlists:changed', {});
        })
        .catch(function (e) { UI.toastError('上传失败：' + e.message); });
    }

    function rename(pl) {
      UI.prompt({
        title: '重命名',
        value: pl.name,
        okText: '保存',
        onSubmit: function (name) {
          if (!name || !name.trim()) throw new Error('名称不能为空');
          return B.call('playlistRename', { id: pl.id, name: name.trim() }).then(function () {
            pl.name = name.trim();
            paint();
            S.emit('playlists:changed', {});
            UI.toast('已重命名');
          });
        }
      });
    }

    function del(pl) {
      UI.confirm({ title: '删除歌单', message: '删除「' + pl.name + '」后不可恢复（不影响歌曲文件）。', okText: '删除', danger: true }).then(function (ok) {
        if (!ok) return;
        B.call('playlistDelete', { id: pl.id }).then(function () {
          S.emit('playlists:changed', {});
          UI.toast('已删除');
          R.back();
        }).catch(function (e) { UI.toastError(e.message); });
      });
    }

    function share(pl) {
      B.call('playlistExport', { id: pl.id })
        .then(function (d) {
          var text = (d && d.text) || '';
          UI.openSheet({
            title: '分享「' + pl.name + '」',
            sub: '复制文本发给朋友，导入即还原',
            render: function (sheetCtx) {
              var ta = el('textarea', { class: 'field__input', rows: 6, readonly: true });
              ta.value = text;
              return el('div', { class: 'col', style: { padding: '0 18px 18px', gap: '12px' } }, [
                ta,
                el('button', {
                  class: 'btn btn--primary btn--block',
                  onclick: function () {
                    if (global.J4Lib) global.J4Lib.copyText(text);
                    UI.toast('已复制分享文本', 'success');
                    sheetCtx.close();
                  }
                }, [el('span', { text: '复制' })])
              ]);
            }
          });
        })
        .catch(function (e) { UI.toastError(e.message); });
    }

    function downloadAll(pl) {
      if (!intro.songs.length) return UI.toast('歌单是空的');
      B.call('playlistGet', { id: pl.id }).then(function () {
        intro.songs.forEach(function (song) {
          S.emit('song:download', song);
        });
        UI.toast('已加入下载队列：' + intro.songs.length + ' 首', 'success');
      });
    }

    return page;
  }

  R.register('playlist', { title: '歌单', render: render });
})(window);
