/* J4Music · ui/discover.js —— 发现 Tab
 * 多音源选择 + 歌单广场 + 精选推荐
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

  function sourceCards() {
    var srcs = S.state.sources && S.state.sources.length ? S.state.sources : [
      { id: 'netease', name: '网易云', loggedIn: false },
      { id: 'qq', name: 'QQ 音乐', loggedIn: false },
        { id: 'huawei', name: '华为音乐', loggedIn: false }
    ];
    var grid = el('div', { class: 'source-grid' });
    srcs.forEach(function (s) {
      var active = (S.state.activeSource || 'all') === s.id;
      var card = el('button', { class: 'source-card' + (active ? ' is-active' : ''), dataset: { src: s.id } }, [
        UI.sourceLogo(s.id, 34),
        el('div', { class: 'source-card__name', text: s.name }),
        el('div', { class: 'source-card__state', text: s.loggedIn ? '已登录' : '未登录' }),
        active ? el('div', { class: 'source-card__check', html: I.check }) : null
      ]);
      card.addEventListener('click', function () {
        S.setState({ activeSource: s.id === 'all' ? 'all' : s.id });
        UI.toast('音源：' + (s.name || '全部'), 'info', 1100);
        rerender();
      });
      var long = function () {
        R.push('login', { source: s.id });
      };
      card.addEventListener('contextmenu', function (e) {
        e.preventDefault();
        long();
      });
      grid.appendChild(card);
    });
    return grid;
  }

  function render() {
    var page = el('div', { class: 'page page--tab' });
    var scroll = el('div', { class: 'page__scroll' });
    var inner = el('div', { class: 'page-pad' });

    var hero = el('div', { class: 'discover-hero' }, [
      el('div', { class: 'discover-hero__glow' }),
      el('div', { class: 'discover-hero__text' }, [
        el('div', { class: 'discover-hero__title', text: '发现好音乐' }),
        el('div', { class: 'discover-hero__sub', text: '四个音源随便挑，会员自动分发' })
      ]),
      el('button', {
        class: 'btn btn--ghost btn--sm',
        onclick: function () { R.push('login', {}); }
      }, [el('span', { class: 'ico', html: I.crown }), el('span', { text: '会员登录' })])
    ]);
    inner.appendChild(hero);

    var srcHead = el('div', { class: 'section-head' }, [
      el('h2', { text: '音源' }),
      el('button', {
        class: 'link',
        text: '全部音源 ›',
        onclick: function () {
          S.setState({ activeSource: 'all' });
          rerender();
        }
      })
    ]);
    inner.appendChild(srcHead);
    inner.appendChild(sourceCards());

    var plHead = el('div', { class: 'section-head' }, [
      el('h2', { text: '歌单广场' }),
      el('button', {
        class: 'link',
        text: '导入 ›',
        onclick: function () { importFlow(); }
      })
    ]);
    inner.appendChild(plHead);
    inner.appendChild(playlistTiles());

    var trendHead = el('div', { class: 'section-head' }, [el('h2', { text: '为你精选' })]);
    inner.appendChild(trendHead);
    var trendList = el('div', { class: 'list' });
    inner.appendChild(trendList);
    loadTrend(trendList);

    scroll.appendChild(inner);
    page.appendChild(scroll);
    return page;
  }

  function rerender() {
    // discover 是 Tab 页：R.top() 只返回推入栈顶部（Tab 页恒为 null），
    // 正确做法是用 switchTab 的 force 选项重建当前 Tab。
    if (R.tab === 'discover') R.switchTab('discover', { force: true });
  }

  function playlistTiles() {
    var pls = [
      { id: 'pl-1', name: '深夜通勤', desc: '低速的城市与车窗', tag: 'netease' },
      { id: 'pl-2', name: '雨天与键盘', desc: '不吵的专注背景', tag: 'qq' },
      { id: 'pl-3', name: '会员歌单·云音乐', desc: 'VIP 专属分发', tag: 'netease' },
      { id: 'pl-4', name: '旧胶片', desc: '老磁带质感', tag: 'netease' }
    ];
    return el(
      'div',
      { class: 'h-scroll' },
      pls.map(function (p) {
        return el('div', {
          class: 'pl-tile',
          onclick: function () {
            R.push('playlist', { id: p.id, remote: true, name: p.name });
          }
        }, [
          UI.coverEl({ id: p.id, title: p.name }, 132),
          el('div', { class: 'pl-tile__name ellipsis', text: p.name }),
          el('div', { class: 'pl-tile__meta ellipsis', text: p.desc })
        ]);
      })
    );
  }

  function loadTrend(host) {
    host.appendChild(UI.skeletonRows(5));
    B.call('search', { keyword: '热门', source: S.state.activeSource || 'all', offset: 0, limit: 12 })
      .then(function (d) {
        var list = (d && d.list) || [];
        host.innerHTML = '';
        if (!list.length) {
          host.appendChild(UI.emptyState('暂无推荐', '换个音源试试'));
          return;
        }
        list.forEach(function (song, i) {
          host.appendChild(
            UI.songRow(song, {
              index: i,
              showSrc: true,
              onTap: function () {
                P.playList(list, i, { title: '为你精选' });
              },
              onMore: function (e) {
                UI.openMenu(
                  [
                    { icon: I.plus, label: '加入歌单', act: function () { S.emit('song:add-to-playlist', song); } },
                    { icon: I.download, label: '下载', act: function () { S.emit('song:download', song); } }
                  ],
                  e
                );
              }
            })
          );
        });
      })
      .catch(function (e) {
        host.innerHTML = '';
        host.appendChild(UI.emptyState('加载失败', e.message));
      });
  }

  function importFlow() {
    UI.prompt({
      title: '导入歌单',
      sub: '粘贴 J4 歌单文本或歌单链接',
      placeholder: 'J4PLAYLIST ... / https://music.163.com/playlist?id=...',
      multiline: true,
      okText: '导入',
      onSubmit: function (text) {
        if (!text || !text.trim()) throw new Error('内容为空');
        return B.call('playlistImport', { text: text.trim() }).then(function (d) {
          UI.toast('已导入「' + (d.name || '歌单') + '」' + (d.songCount || 0) + ' 首', 'success');
          S.emit('playlists:changed', {});
          if (d.id) R.push('playlist', { id: d.id });
          return true;
        });
      }
    });
  }

  R.register('discover', { title: '发现', render: render });
})(window);
