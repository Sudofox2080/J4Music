/* J4Music · store.js
 * 极简响应式状态：手写 subscribe / emit，无框架、无依赖。
 * 用法：
 *   J4Store.subscribe(fn)           返回 unsubscribe
 *   J4Store.setState(partial)       浅合并并通知
 *   J4Store.state                   只读访问（别直接改嵌套对象，用 setState）
 *   J4Store.computed(key, fn)       （可选）派生态，按需计算
 * 为了性能，通知用 rAF 合并；单个 tick 内多次 setState 只触发一次。
 */
(function (global) {
  'use strict';

  var listeners = [];
  var computedFns = Object.create(null);
  var computedCache = Object.create(null);
  var squeued = false;
  var raf = global.requestAnimationFrame || function (fn) { return setTimeout(fn, 16); };

  var state = {
    // 路由
    tab: 'home',
    stack: [], // [{name, params}]

    // 播放
    playing: false,
    index: -1,
    positionMs: 0,
    durationMs: 0,
    loopMode: 'list',
    queue: [],
    playlistId: null,
    currentSong: null,
    queueDirty: 0,

    // 歌词
    lyricSongId: null,
    lyricLines: [],
    lyricRich: false,
    lyricIndex: -1,
    lyricLoading: false,

    // 数据
    songs: [], // 搜索结果等
    playlists: [],
    sources: [],
    downloads: [],
    localSongs: [],
    favorites: {},

    // 设置
    settings: { quality: 'exhigh', theme: 'aurora', downloadDir: '', cacheSize: 0, cacheLimit: 0, sleepTimer: 0 },

    // UI
    sheet: null, // {type, ...}
    toast: null,
    bridgeMock: false,
    ready: false,
    loading: {},

    // 发现页
    activeSource: 'all'
  };

  function notify() {
    if (squeued) return;
    squeued = true;
    raf(function () {
      squeued = false;
      for (var i = 0; i < listeners.length; i++) {
        try {
          listeners[i](state);
        } catch (e) {
          console.error('[J4Store] listener error', e);
        }
      }
    });
  }

  function setState(partial) {
    if (!partial) return;
    var changed = false;
    for (var k in partial) {
      if (!Object.prototype.hasOwnProperty.call(partial, k)) continue;
      if (state[k] !== partial[k]) {
        state[k] = partial[k];
        changed = true;
      }
      delete computedCache[k];
    }
    if (changed) notify();
  }

  function subscribe(fn) {
    listeners.push(fn);
    return function () {
      var i = listeners.indexOf(fn);
      if (i >= 0) listeners.splice(i, 1);
    };
  }

  function computed(key, fn) {
    computedFns[key] = fn;
    delete computedCache[key];
  }

  function get(key) {
    if (computedFns[key]) {
      if (!(key in computedCache)) computedCache[key] = computedFns[key](state);
      return computedCache[key];
    }
    return state[key];
  }

  function emit(name, payload) {
    // 轻量事件总线，用于跨模块的一次性交互（不参与重渲染）
    var ev = events[name] || (events[name] = []);
    for (var i = 0; i < ev.length; i++) {
      try {
        ev[i](payload);
      } catch (e) {
        console.error('[J4Store] event error', name, e);
      }
    }
  }

  var events = Object.create(null);

  function on(name, fn) {
    var ev = events[name] || (events[name] = []);
    ev.push(fn);
    return function () {
      var i = ev.indexOf(fn);
      if (i >= 0) ev.splice(i, 1);
    };
  }

  global.J4Store = {
    state: state,
    setState: setState,
    subscribe: subscribe,
    computed: computed,
    get: get,
    emit: emit,
    on: on,
    notify: notify
  };
})(window);
