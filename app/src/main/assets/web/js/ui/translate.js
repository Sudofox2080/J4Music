/* J4Music · ui/translate.js —— 音乐转译页（.ncm/.qmc/.mflac/.QY2 → mp3/flac）
 * 选择文件 → 排队 → 进度 → 结果列表 → 存入本地歌单
 */
(function (global) {
  'use strict';

  var D = global.J4DOM;
  var I = global.J4Icons;
  var B = global.J4Bridge;
  var S = global.J4Store;
  var UI = global.J4UI;
  var R = global.J4Router;
  var el = D.el;

  // 与 CipherFactory.EXTENSIONS 对齐：少一个用户就会以为「这格式不能转」。
  // QY2 属于 QMC v2（ekey）家族，QQ 音乐与华为音乐下发的 .qy2 都走这条路径。
  var FORMATS = ['NCM', 'QMC0/QMC3', 'QMC FLAC', 'MFLAC', 'MGG', 'QY2', 'TM0', 'MP3'];

  function render() {
    var page = el('div', { class: 'page page--push' });
    var header = el('div', { class: 'page-header' }, [
      el('button', { class: 'ibtn', 'aria-label': '返回', html: I.back, onclick: function () { R.back(); } }),
      el('div', { class: 'page-header__title', text: '音乐转译' })
    ]);

    var scroll = el('div', { class: 'page__scroll' });
    var body = el('div', { class: 'page-pad' });

    var drop = el('div', { class: 'drop-zone' }, [
      el('div', { class: 'drop-zone__icon', html: I.translate }),
      el('div', { class: 'drop-zone__title', text: '导入加密音频文件' }),
      el('div', { class: 'drop-zone__sub', text: '支持网易云 .ncm、QQ 音乐 .qmc/.mflac、华为 .QY2 等格式，转译后可在本地播放' }),
      el('button', {
        class: 'btn btn--primary',
        style: { marginTop: '16px' },
        onclick: function () { pick(); }
      }, [el('span', { class: 'ico', html: I.folderOpen }), el('span', { text: '选择文件' })])
    ]);

    var fmtHead = el('div', { class: 'section-head' }, [el('h2', { text: '支持的格式' })]);
    var fmtGrid = el('div', { class: 'format-grid' });
    FORMATS.forEach(function (f) {
      fmtGrid.appendChild(el('span', { class: 'format-pill', text: f }));
    });

    var resHead = el('div', { class: 'section-head' }, [el('h2', { text: '转译结果' })]);
    var resList = el('div', { class: 'list' });
    var progressLine = el('div', { class: 'search-status', text: '' });

    body.appendChild(drop);
    body.appendChild(fmtHead);
    body.appendChild(fmtGrid);
    body.appendChild(resHead);
    body.appendChild(progressLine);
    body.appendChild(resList);
    body.appendChild(
      el('div', { class: 'card', style: { padding: '14px 16px', marginTop: '16px' } }, [
        el('div', { class: 'row', style: { gap: '10px', alignItems: 'flex-start' } }, [
          el('span', { class: 'ico', style: { color: 'var(--c-accent-2)' }, html: I.info }),
          el('div', { class: 'col', style: { gap: '4px' } }, [
            el('div', { style: { fontSize: '13px', color: 'var(--text-1)' }, text: '转译说明' }),
            el('div', { style: { fontSize: '12px', color: 'var(--text-3)', lineHeight: '1.6' }, text: '文件只在本机解密处理，输出到下载目录的同名文件夹；失败的文件会保留原路径并给出错误原因。' })
          ])
        ])
      ])
    );

    scroll.appendChild(body);
    page.appendChild(header);
    page.appendChild(scroll);

    var results = [];

    function pick() {
      // WebView 里 <input type=file> 拿不到真实路径（File.path 恒 undefined），
      // 以前把 f.name 传下去，等于让原生去打开一个只有文件名的相对路径 —— 必然失败。
      // 现在改由原生 SAF 选择器直接给出可读的 content://。
      progressLine.textContent = '正在打开文件选择器…';
      B.call('pickTranslate', {})
        .then(function (d) {
          progressLine.textContent = '';
          if (d && d.cancelled) return;
          var list = (d && d.list) || [];
          results = list.concat(results);
          paint(list.length ? list : []);
          var ok = list.filter(function (x) { return x.ok; }).length;
          if (list.length) UI.toast('转译完成：成功 ' + ok + ' / ' + list.length, ok === list.length ? 'success' : 'info', 2200);
        })
        .catch(function (e) {
          progressLine.textContent = '';
          UI.toastError(e, '转译失败');
        });
    }

    function run(paths) {
      progressLine.textContent = '正在转译 ' + paths.length + ' 个文件…';
      B.call('translate', { paths: paths })
        .then(function (d) {
          var list = (d && d.list) || [];
          progressLine.textContent = '';
          results = list.concat(results);
          paint(list.length ? list : []);
          var ok = list.filter(function (x) { return x.ok; }).length;
          UI.toast('转译完成：成功 ' + ok + ' / ' + list.length, ok === list.length ? 'success' : 'info', 2200);
        })
        .catch(function (e) {
          progressLine.textContent = '';
          UI.toastError('转译失败：' + e.message);
        });
    }

    function paint(list) {
      resList.innerHTML = '';
      if (!results.length) {
        resList.appendChild(UI.emptyState('还没有转译记录', '选择 .ncm 等文件开始'));
        return;
      }
      results.forEach(function (r, i) {
        var name = String(r.in || '').split(/[\\/]/).pop();
        var outName = String(r.out || '').split(/[\\/]/).pop();
        var song = {
          id: 'local:trans-' + i,
          title: (name || 'unknown').replace(/\.[^.]+$/, ''),
          artist: r.ok ? '已转译' : '失败',
          source: 'local',
          localPath: r.out || '',
          durationMs: 0
        };
        resList.appendChild(
          el('div', { class: 'song-row' }, [
            UI.coverEl(song, 44),
            el('div', { class: 'song-row__body' }, [
              el('div', { class: 'song-row__title' }, [
                el('span', { class: 'ellipsis', text: song.title }),
                el('span', { class: 'badge ' + (r.ok ? 'badge--local' : 'badge--src'), text: r.ok ? 'OK' : 'ERR' })
              ]),
              el('div', { class: 'song-row__sub' }, [
                el('span', { class: 'ellipsis', text: r.ok ? '→ ' + (outName || '输出完成') : r.error || '未知错误' })
              ])
            ]),
            r.ok
              ? el('button', {
                  class: 'ibtn',
                  html: I.play,
                  'aria-label': '播放',
                  onclick: function () {
                    var queue = results
                      .filter(function (x) { return x.ok; })
                      .map(function (x, k) {
                        return {
                          id: 'local:trans-' + k,
                          title: String(x.out || '').split(/[\\/]/).pop().replace(/\.[^.]+$/, ''),
                          artist: '本地',
                          source: 'local',
                          localPath: x.out,
                          durationMs: 0
                        };
                      });
                    global.J4Player.playList(queue.length ? queue : [song], 0, { title: '转译结果' });
                  }
                })
              : null
          ])
        );
      });
    }

    paint([]);
    return page;
  }

  R.register('translate', { title: '音乐转译', render: render });
})(window);
