/* J4Music · ui/local.js —— 本地音乐页
 * 扫描本机音频 / 导入文件 / 存为本地歌单
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

  function render() {
    var page = el('div', { class: 'page page--push' });
    var header = el('div', { class: 'page-header' }, [
      el('button', { class: 'ibtn', 'aria-label': '返回', html: I.back, onclick: function () { R.back(); } }),
      el('div', { class: 'page-header__title', text: '本地音乐' }),
      el('button', {
        class: 'ibtn',
        'aria-label': '扫描',
        html: I.refresh,
        onclick: function () { scan(true); }
      })
    ]);

    var scroll = el('div', { class: 'page__scroll' });
    var body = el('div', { class: 'page-pad' });

    var actions = el('div', { class: 'row', style: { gap: '10px', marginBottom: '14px' } }, [
      el('button', {
        class: 'btn btn--primary',
        style: { flex: '1' },
        onclick: function () { importFiles(); }
      }, [el('span', { class: 'ico', html: I.folderOpen }), el('span', { text: '导入文件' })]),
      el('button', {
        class: 'btn btn--ghost',
        style: { flex: '1' },
        onclick: function () {
          var songs = S.state.localSongs || [];
          if (!songs.length) {
            UI.toast('先扫描一下本地文件');
            return;
          }
          S.emit('playlist:create-from-local', songs);
        }
      }, [el('span', { class: 'ico', html: I.plus }), el('span', { text: '存为歌单' })])
    ]);
    body.appendChild(actions);

    var status = el('div', { class: 'search-status', text: '' });
    body.appendChild(status);
    var listHost = el('div', { class: 'list' });
    body.appendChild(listHost);

    function paint(songs) {
      listHost.innerHTML = '';
      if (!songs || !songs.length) {
        listHost.appendChild(
          el('div', { class: 'empty empty--big' }, [
            el('div', { class: 'note-dots note-dots--big', html: '<i></i><i></i><i></i><i></i>' }),
            el('div', { class: 'empty__title', text: '没有找到本地音乐' }),
            el('div', { class: 'empty__sub', text: '点上面「导入文件」，或让原生扫描音频目录' }),
            el('button', {
              class: 'btn btn--ghost btn--sm',
              style: { marginTop: '14px' },
              onclick: function () { scan(true); }
            }, [el('span', { text: '重新扫描' })])
          ])
        );
        return;
      }
      var totalBytes = songs.reduce(function (a, s) {
        return a + (s.extra && s.extra.size ? s.extra.size : 0);
      }, 0);
      status.textContent = songs.length + ' 首' + (totalBytes ? ' · ' + D.fmtSize(totalBytes) : '');
      songs.forEach(function (song, i) {
        listHost.appendChild(
          UI.songRow(song, {
            index: i,
            showIndex: false,
            showQuality: true,
            onTap: function () {
              P.playList(songs, i, { title: '本地音乐' });
            },
            onMore: function (e) {
              UI.openMenu(
                [
                  { icon: I.plus, label: '加入歌单', act: function () { S.emit('song:add-to-playlist', song); } },
                  { icon: I.list, label: '下一首播放', act: function () { S.emit('song:play-next', song); } },
                  { icon: I.heart, label: '收藏', act: function () { S.emit('song:like', song); } }
                ],
                e
              );
            }
          })
        );
      });
    }

    function scan(force) {
      status.textContent = '扫描中…';
      B.call('localScan')
        .then(function (d) {
          var songs = (d && d.songs) || [];
          S.setState({ localSongs: songs });
          paint(songs);
          if (force) UI.toast('扫描完成：' + songs.length + ' 首', 'success');
        })
        .catch(function (e) {
          status.textContent = '';
          paint([]);
          UI.toastError('扫描失败：' + e.message);
        });
    }

    function importFiles() {
      var inp = el('input', { type: 'file', accept: 'audio/*,.ncm,.qmc,.mflac,.mgg,.qy2', multiple: true });
      inp.style.display = 'none';
      document.body.appendChild(inp);
      inp.addEventListener('change', function () {
        var files = Array.prototype.map.call(inp.files || [], function (f) {
          return f.path || f.name;
        });
        if (inp.parentNode) inp.parentNode.removeChild(inp);
        if (!files.length) return;
        B.call('localImport', { files: files })
          .then(function () {
            UI.toast('导入完成，正在刷新', 'success');
            scan(false);
          })
          .catch(function (e) {
            UI.toastError('导入失败：' + e.message);
          });
      });
      inp.click();
    }

    scroll.appendChild(body);
    page.appendChild(header);
    page.appendChild(scroll);

    paint(S.state.localSongs || []);
    return page;
  }

  R.register('local', { title: '本地音乐', render: render });
})(window);
