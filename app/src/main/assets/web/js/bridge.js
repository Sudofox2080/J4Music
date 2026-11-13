/* J4Music · bridge.js
 * J4Native 封装 + 浏览器 mock 回退 + Promise 化调用。
 * 原生约定：window.J4Native.call(method, argsJson) 同步返回 JSON 字符串，
 * 形如 {"ok":true,"data":...} 或 {"ok":false,"error":"..."}。
 * 若 window.J4Native 不存在（浏览器预览），自动落到 mock（见 js/mock.js）。
 *
 * 全局：window.J4Bridge
 *   J4Bridge.call(method, args)  -> Promise<any>         已解包 data
 *   J4Bridge.raw(method, args)   -> {ok,data|error}      不解包
 *   J4Bridge.mock                -> boolean              是否 mock 模式
 *   J4Bridge.log(level, msg)     -> void
 *   J4Bridge.toast(msg, kind)    -> void（优先走 UI toast）
 */
(function (global) {
  'use strict';

  var J4Native = global.J4Native;
  var hasNative = !!(J4Native && typeof J4Native.call === 'function');

  // 异步回填：原生如果需要跨线程拿数据，可先返回纯字符串 id，
  // 之后调用 window.__J4BridgeResolve(id, jsonString) 回填。
  var pendingId = 0;
  var pendingMap = Object.create(null);

  global.__J4BridgeResolve = function (id, json) {
    var entry = pendingMap[id];
    if (!entry) return;
    delete pendingMap[id];
    var parsed;
    try {
      parsed = JSON.parse(json);
    } catch (e) {
      parsed = { ok: false, error: 'bad json: ' + String(json).slice(0, 100) };
    }
    entry(parsed);
  };

  function safeParse(ret) {
    if (ret && typeof ret === 'object') return ret;
    if (typeof ret !== 'string') return { ok: false, error: 'empty native return' };
    var s = ret.trim();
    if (s.charAt(0) !== '{') return { ok: false, error: 'unexpected native payload: ' + s.slice(0, 100) };
    try {
      return JSON.parse(s);
    } catch (e) {
      return { ok: false, error: 'bad json: ' + s.slice(0, 100) };
    }
  }

  function raw(method, args) {
    var argsJson;
    try {
      argsJson = JSON.stringify(args == null ? {} : args);
    } catch (e) {
      argsJson = '{}';
    }
    if (!hasNative) return global.J4Mock.invoke(method, args == null ? {} : args);
    try {
      var ret = global.J4Native.call(method, argsJson);
      var shaped = safeParse(ret);
      if (shaped && shaped.__async && typeof shaped.id === 'string') {
        // 原生声明异步：登记 resolver，稍后由 __J4BridgeResolve 回填
        return { __pending: shaped.id };
      }
      return shaped;
    } catch (e) {
      return { ok: false, error: 'native call failed: ' + (e && e.message ? e.message : e) };
    }
  }

  function call(method, args) {
    var r = raw(method, args);
    if (r && r.__pending) {
      var id = r.__pending;
      return new Promise(function (resolve, reject) {
        // 超时保护：原生协程被系统杀掉或 Activity 回收时，回填永远不会来，
        // 不能让 await 永久悬挂（会让整个页面的 loading 状态卡死）。
        var timer = setTimeout(function () {
          if (!pendingMap[id]) return;
          delete pendingMap[id];
          reject(makeErr(method, { ok: false, error: 'timeout: ' + method }));
        }, PENDING_TIMEOUT_MS);
        pendingMap[id] = function (res) {
          clearTimeout(timer);
          resolve(res);
        };
      }).then(function (res) {
        if (res && res.ok) return res.data;
        throw makeErr(method, res);
      });
    }
    if (r && r.ok) return Promise.resolve(r.data);
    return Promise.reject(makeErr(method, r));
  }

  function makeErr(method, r) {
    var err = new Error((r && r.error) || method + ' failed');
    err.method = method;
    err.payload = r;
    return err;
  }

  function log(level, msg) {
    if (hasNative && typeof global.J4Native.log === 'function') {
      try {
        global.J4Native.log(level, String(msg));
        if (level !== 'e') return;
      } catch (e) {
        /* ignore */
      }
    }
    var fn = level === 'e' ? console.error : level === 'w' ? console.warn : console.log;
    fn('[J4]', msg);
  }

  function toast(msg, kind) {
    if (global.J4UI && global.J4UI.toast) {
      global.J4UI.toast(msg, kind);
      return;
    }
    if (hasNative && typeof global.J4Native.toast === 'function') {
      try {
        global.J4Native.toast(String(msg));
        return;
      } catch (e) {
        /* fallthrough */
      }
    }
    console.log('[toast]', msg);
  }

  // 原生 → JS 的单向通知通道（原生可调用 window.__J4NativeEvent(type, json)）
  // ---------------------------------------------------------------- 原生 → JS 通道
  //
  // 原生 JsBridge.emitEvent 实际发出的是 __J4NativeEvent('<类型>', '<JSON 字符串>')，
  // 其中 <JSON 字符串> 形如 {"type":"state","req":"语音请求id","data":{...},"ts":123}。
  //
  // 这里做三件事：
  //   1) 拆包：把内层 JSON 解成对象，并把 type 统一放进 payload.type；
  //   2) 转交 window.J4.onPlayer：播放器状态（state/track/queue/error）由 player.js 消化；
  //   3) 交给 window.J4.onNativeEvent：其余业务事件（search/lyric/songUrl/download...）由 app.js 分发。
  // 任何一条通道抛异常都不能影响后面的通道 —— 一个页面写坏了不能把播放条一起带崩。
  function dispatch(kind, json) {
    var payload;
    if (json && typeof json === 'object') {
      payload = json;
    } else {
      try {
        payload = JSON.parse(String(json));
      } catch (e) {
        log('e', 'native event parse error: ' + e);
        return;
      }
    }
    if (!payload || typeof payload !== 'object') return;
    // 原生把类型放在外层参数上；内层 payload 里的 type 若也有就以内层为准
    if (!payload.type) payload.type = kind;

    var J4 = global.J4 || (global.J4 = {});
    var t = payload.type;
    var isPlayer = t === 'state' || t === 'track' || t === 'queue' || t === 'error' ||
      t === 'player' || t === 'buffered' || t === 'duration';
    if (isPlayer && typeof J4.onPlayer === 'function') {
      try {
        J4.onPlayer(payload);
      } catch (e) {
        log('e', 'onPlayer failed: ' + (e && e.message));
      }
    }
    if (typeof J4.onNativeEvent === 'function') {
      try {
        J4.onNativeEvent(payload);
      } catch (e) {
        log('e', 'onNativeEvent(' + t + ') failed: ' + (e && e.message));
      }
    }
    if (typeof J4.onEvent === 'function' && J4.onEvent !== J4.onPlayer) {
      // 兼容旧调用点：onEvent 只在没人监听新通道时兜底
      if (!isPlayer && typeof J4.onNativeEvent !== 'function') {
        try {
          J4.onEvent(payload);
        } catch (e) {}
      }
    }
  }

  global.__J4NativeEvent = dispatch;

  // 原生 MainActivity 在分享/深链时调用：window.J4.onShare(text)
  // 在前端还没定义 onShare 时先排队，等 app.js 起来再补发。
  var pendingShare = [];
  global.__J4Share = function (text) {
    var J4 = global.J4 || (global.J4 = {});
    if (typeof J4.onShare === 'function') {
      try {
        J4.onShare(String(text));
        return;
      } catch (e) {
        log('e', 'onShare failed: ' + (e && e.message));
      }
    }
    pendingShare.push(String(text));
  };
  global.__J4FlushShare = function () {
    var J4 = global.J4 || {};
    if (typeof J4.onShare !== 'function') return;
    while (pendingShare.length) {
      var t = pendingShare.shift();
      try {
        J4.onShare(t);
      } catch (e) {}
    }
  };

  // 异步回填的超时保护：原生崩溃时 Promise 不能永远挂着
  var PENDING_TIMEOUT_MS = 60000;
  global.J4Bridge = {
    get mock() {
      return !hasNative;
    },
    get native() {
      return hasNative;
    },
    call: call,
    raw: raw,
    log: log,
    toast: toast
  };

  if (!hasNative) log('w', 'window.J4Native 不存在，已启用 mock 模式');
})(window);
