/* J4Music · queue.js
 * 播放队列的不可变（写时复制）操作 + 队列/清空/移出事件处理。
 * 维护 S.state.queue 与 S.state.index，并同步给原生 setQueue。
 * 全局：window.J4Queue
 */
(function (global) {
  'use strict';

  var B = global.J4Bridge;
  var S = global.J4Store;
  var UI = global.J4UI;

  function current() {
    var q = S.state.queue;
    var i = S.state.index;
    return q && q[i] ? q[i] : null;
  }

  function set(list, index, playlistId) {
    var songs = Array.isArray(list) ? list.slice() : [];
    var idx = Math.max(0, Math.min(songs.length - 1, index || 0));
    S.setState({ queue: songs, index: idx, playlistId: playlistId || null, queueDirty: (S.state.queueDirty || 0) + 1 });
    return B.call('setQueue', { songs: songs, index: idx, playlistId: playlistId || null });
  }

  function replaceSong(i, song) {
    var q = S.state.queue.slice();
    if (!q[i]) return;
    q[i] = song;
    S.setState({ queue: q });
    return B.call('setQueue', { songs: q, index: S.state.index, playlistId: S.state.playlistId || null });
  }

  function insert(items, at) {
    var q = S.state.queue.slice();
    var pos = Math.max(0, Math.min(q.length, at == null ? q.length : at));
    q.splice.apply(q, [pos, 0].concat(items));
    var curIdx = S.state.index;
    if (pos <= curIdx && items.length) curIdx += items.length;
    S.setState({ queue: q, index: curIdx });
    return B.call('setQueue', { songs: q, index: curIdx, playlistId: S.state.playlistId || null });
  }

  function append(items) {
    return insert(items, null);
  }

  function move(from, to) {
    var q = S.state.queue.slice();
    if (from < 0 || to < 0 || from >= q.length || to >= q.length) return Promise.resolve();
    var item = q.splice(from, 1)[0];
    q.splice(to, 0, item);
    var cur = S.state.index;
    if (cur === from) cur = to;
    else if (from < cur && to >= cur) cur -= 1;
    else if (from > cur && to <= cur) cur += 1;
    S.setState({ queue: q, index: cur });
    return B.call('setQueue', { songs: q, index: cur, playlistId: S.state.playlistId || null });
  }

  function removeAt(i) {
    var q = S.state.queue.slice();
    if (!q[i]) return Promise.resolve();
    q.splice(i, 1);
    var cur = S.state.index;
    if (i < cur) cur -= 1;
    else if (i === cur) cur = Math.min(cur, q.length - 1);
    S.setState({ queue: q, index: Math.max(0, cur) });
    return B.call('setQueue', { songs: q, index: Math.max(0, cur), playlistId: S.state.playlistId || null });
  }

  function clear() {
    S.setState({ queue: [], index: 0, playing: false });
    return B.call('setQueue', { songs: [], index: 0, playlistId: null });
  }

  function shuffle() {
    var q = S.state.queue.slice();
    var cur = q[S.state.index];
    for (var i = q.length - 1; i > 0; i--) {
      var j = Math.floor(Math.random() * (i + 1));
      var t = q[i];
      q[i] = q[j];
      q[j] = t;
    }
    var idx = Math.max(0, q.indexOf(cur));
    S.setState({ queue: q, index: idx });
    return B.call('setQueue', { songs: q, index: idx, playlistId: S.state.playlistId || null });
  }

  function indexOfSong(song) {
    var q = S.state.queue;
    for (var i = 0; i < q.length; i++) if (q[i].id === song.id) return i;
    return -1;
  }

  function init() {
    S.on('queue:remove', function (p) {
      removeAt(p.index).catch(function (e) {
        UI.toastError('移除失败：' + e.message);
      });
    });
    S.on('queue:clear', function () {
      clear().catch(function () {});
      UI.toast('队列已清空');
    });
    S.on('queue:shuffle', function () {
      shuffle().catch(function () {});
      UI.toast('已打乱队列');
    });
  }

  global.J4Queue = {
    init: init,
    current: current,
    set: set,
    replaceSong: replaceSong,
    insert: insert,
    append: append,
    move: move,
    removeAt: removeAt,
    clear: clear,
    shuffle: shuffle,
    indexOfSong: indexOfSong
  };
})(window);
