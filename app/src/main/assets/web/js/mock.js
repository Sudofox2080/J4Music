/* J4Music · mock.js
 * 浏览器预览用的假原生层。只在 window.J4Native 不存在时由 bridge.js 调用。
 * 数据：8 首歌、3 个歌单、带时间轴的假歌词（含翻译与逐字 rich）。
 * 模拟延迟：全部通过 setTimeout(0~220ms) 返回，便于看到骨架屏与加载态。
 */
(function (global) {
  'use strict';

  var SRC_NAME = { netease: '网易云', qq: 'QQ音乐', local: '本地' };

  var SONGS = [
    { id: 'netease:1901371647', source: 'netease', title: '晚风与信', artist: '林栖', album: '夜航船', durationMs: 232000, vip: false },
    { id: 'netease:1974443814', source: 'netease', title: 'Violet Hour', artist: 'Mono Lake', album: 'Afterglow', durationMs: 198000, vip: true },
    { id: 'qq:0039MnYb0qxYhV', source: 'qq', title: '深海回声', artist: '陈亦晴', album: '潜行', durationMs: 264000, vip: false },
    { id: 'qq:001Qu4I30eVFYb', source: 'qq', title: '霓虹慢跑', artist: '夜行列车', album: '城市夜游', durationMs: 215000, vip: true },
    { id: 'huawei:HV0001', source: 'huawei', title: 'Glass Rain', artist: 'Nova Chen', album: 'Prism', durationMs: 205000, vip: false },
    { id: 'local:LOCAL0001', source: 'local', title: 'Demo · 深夜版本', artist: '未知艺术家', album: '本地文件', durationMs: 176000, vip: false }
  ];

  function cover(i) {
    // 不用外链图：返回空字符串，前端会用渐变+首字母占位
    return '';
  }

  function song(i, over) {
    var s = SONGS[i % SONGS.length];
    var o = {
      id: s.id,
      source: s.source,
      title: s.title,
      artist: s.artist,
      album: s.album,
      durationMs: s.durationMs,
      coverUrl: cover(i),
      vip: s.vip,
      localPath: s.source === 'local' ? '/sdcard/Music/demo.m4a' : null,
      quality: 'exhigh',
      extra: { song_id: s.id.split(':')[1] }
    };
    if (over) for (var k in over) if (Object.prototype.hasOwnProperty.call(over, k)) o[k] = over[k];
    return o;
  }

  // 额外造一批同构歌曲，用来验证虚拟滚动 / 分批渲染
  function bulk(n) {
    var out = [];
    for (var i = 0; i < n; i++) {
      var t = song(i % 8);
      out.push({
        id: t.id + '#' + (1000 + i),
        source: t.source,
        title: t.title + ' · 第' + (i + 1) + '版',
        artist: t.artist,
        album: t.album,
        durationMs: t.durationMs - (i % 30) * 1000,
        coverUrl: '',
        vip: t.vip,
        localPath: null,
        quality: 'standard',
        extra: t.extra
      });
    }
    return out;
  }

  // ---- 假歌词：带 ms 时间轴 + 中文翻译 + rich（逐字时间）----
  var LRC_LINES = [
    { t: 1500, tx: '夜色在窗外慢慢下沉' },
    { t: 6200, tx: '我把灯留到最后一盏' },
    { t: 11200, tx: '晚风翻动那封没寄出的信' },
    { t: 16800, tx: '字迹被时间泡得发亮' },
    { t: 22400, tx: '你说过要去看海' },
    { t: 26200, tx: '在冬天结束之前' },
    { t: 31200, tx: '于是我把所有路都走成了出口' },
    { t: 37400, tx: '每一步都踩在月光上' },
    { t: 43200, tx: '如果声音也有颜色' },
    { t: 48200, tx: '它一定是你离开时那片晚霞' },
    { t: 54600, tx: '海浪把我推回岸边' },
    { t: 60000, tx: '又把你的名字退回来' },
    { t: 66200, tx: '我在这里，等你听完整首歌' },
    { t: 73000, tx: '直到天亮，直到风停' }
  ];
  var LRC_TR = [
    'The night is sinking outside the window',
    'I leave one lamp burning',
    'The breeze turns that unsent letter',
    'Ink swollen by time',
    'You said we would go see the sea',
    'Before winter ends',
    'So I turned every road into an exit',
    'Every step on moonlight',
    'If a voice had color',
    'It would be the dusk you left in',
    'The waves push me back to shore',
    'And return your name again',
    'I am here, waiting for you to finish the song',
    'Until dawn, until the wind stops'
  ];

  function buildLyric(seed) {
    var lines = LRC_LINES.map(function (l, i) {
      var end = i + 1 < LRC_LINES.length ? LRC_LINES[i + 1].t - 120 : l.t + 5000;
      var text = l.tx;
      var span = end - l.t;
      var per = Math.max(90, Math.floor(span / Math.max(1, text.length)));
      var chars = [];
      for (var c = 0; c < text.length; c++) chars.push({ c: text.charAt(c), t: l.t + c * per });
      return {
        ms: l.t + seed,
        text: text,
        tr: LRC_TR[i],
        dur: span,
        chars: chars
      };
    });
    // 标准 LRC 文本
    var lrc = lines
      .map(function (l) {
        var s = Math.floor(l.ms / 1000);
        var mm = String(Math.floor(s / 60)).padStart(2, '0');
        var ss = String(s % 60).padStart(2, '0');
        return '[' + mm + ':' + ss + ']' + l.text;
      })
      .join('\n');
    var translated = lines
      .map(function (l) {
        var s = Math.floor(l.ms / 1000);
        var mm = String(Math.floor(s / 60)).padStart(2, '0');
        var ss = String(s % 60).padStart(2, '0');
        return '[' + mm + ':' + ss + ']' + l.tr;
      })
      .join('\n');
    return { lrc: lrc, translated: translated, romanized: '', rich: lines, richKind: 'krc' };
  }

  // ---- 状态 ----
  var state = {
    logged: { netease: true, qq: false },
    quality: 'exhigh',
    theme: 'aurora',
    downloadDir: '/sdcard/J4Music/Download',
    sleepTimer: 0,
    cacheSize: 348 * 1024 * 1024,
    cacheLimit: 1024 * 1024 * 1024,
    queue: [song(0), song(1), song(2), song(3), song(4)],
    index: 0,
    playing: false,
    pos: 0,
    loop: 'list',
    playlistId: null,
    playlists: [
      { id: 'pl-1', name: '深夜通勤', cover: '', kind: 'user', source: 'local', remoteId: null, songCount: 5, updatedAt: Date.now() - 86400000 },
      { id: 'pl-2', name: '雨天与键盘', cover: '', kind: 'user', source: 'local', remoteId: null, songCount: 8, updatedAt: Date.now() - 3600000 * 5 },
      { id: 'pl-3', name: '会员歌单 · 云音乐', cover: '', kind: 'remote', source: 'netease', remoteId: '3778678', songCount: 6, updatedAt: Date.now() - 3600000 * 30 }
    ],
    plSongs: {},
    downloads: [
      { songId: 'netease:1974443814', title: 'Violet Hour', progress: 0.42, state: 'running' },
      { songId: 'qq:0039MnYb0qxYhV', title: '深海回声', progress: 1, state: 'done' }
    ],
    favorites: {}
  };

  for (var pi = 0; pi < state.playlists.length; pi++) {
    var p = state.playlists[pi];
    var arr = [];
    for (var k = 0; k < p.songCount; k++) arr.push(song((pi * 3 + k) % 8));
    state.plSongs[p.id] = arr;
  }

  var tickTimer = null;

  function ok(data) {
    return { ok: true, data: data == null ? {} : data };
  }

  function fail(error) {
    return { ok: false, error: error || 'mock error' };
  }

  function emitPlayer(ev) {
    if (global.J4 && typeof global.J4.onPlayer === 'function') {
      try {
        global.J4.onPlayer(ev);
      } catch (e) {
        console.error(e);
      }
    }
  }

  function statusData() {
    return {
      playing: state.playing,
      index: state.index,
      positionMs: state.pos,
      durationMs: state.queue[state.index] ? state.queue[state.index].durationMs : 0,
      loopMode: state.loop,
      queue: state.queue,
      playlistId: state.playlistId
    };
  }

  function startTick() {
    if (tickTimer) return;
    tickTimer = setInterval(function () {
      if (!state.playing) return;
      var cur = state.queue[state.index];
      if (!cur) return;
      state.pos += 250;
      if (state.pos >= cur.durationMs) {
        state.pos = 0;
        if (state.loop === 'one') {
          emitPlayer(Object.assign({ type: 'state' }, statusData()));
        } else {
          var next = state.loop === 'shuffle' ? Math.floor(Math.random() * state.queue.length) : (state.index + 1) % state.queue.length;
          state.index = next;
          emitPlayer(Object.assign({ type: 'state' }, statusData()));
        }
        return;
      }
      emitPlayer({
        type: 'state',
        playing: state.playing,
        index: state.index,
        positionMs: state.pos,
        durationMs: cur.durationMs,
        loopMode: state.loop
      });
    }, 250);
  }

  function stopTick() {
    if (tickTimer) {
      clearInterval(tickTimer);
      tickTimer = null;
    }
  }

  var handlers = {
    ping: function () {
      return ok({ appVersion: '1.0.0-mock', plan: 'preview' });
    },

    search: function (a) {
      var kw = (a.keyword || '').trim();
      var src = a.source || 'all';
      var offset = a.offset || 0;
      var limit = a.limit || 20;
      var pool;
      if (!kw) pool = [];
      else if (kw.length > 3) pool = bulk(240); // 压力测试用：搜索长词返回 240 条
      else pool = [song(0), song(1), song(2), song(3), song(4), song(5), song(6), song(7)];
      pool = pool.filter(function (s) {
        return src === 'all' ? true : s.source === src;
      });
      return ok({ list: pool.slice(offset, offset + limit), total: pool.length });
    },

    songUrl: function (a) {
      var q = a.quality || state.quality;
      var downgraded = a.songId && a.songId.indexOf('vip') >= 0;
      return ok({
        url: 'mock://audio/' + encodeURIComponent(a.songId || 'x') + '/' + q + '.mp3',
        quality: downgraded ? 'standard' : q,
        downgraded: !!downgraded,
        preview: 0
      });
    },

    lyric: function (a) {
      var seed = 0;
      var s = String(a.songId || '');
      for (var i = 0; i < s.length; i++) seed += s.charCodeAt(i);
      return ok(buildLyric(seed % 500));
    },

    playlistList: function () {
      return ok({ list: state.playlists });
    },

    playlistGet: function (a) {
      var meta = state.playlists.filter(function (p) {
        return p.id === a.id;
      })[0];
      if (!meta) return fail('歌单不存在');
      return ok({ playlist: meta, songs: state.plSongs[a.id] || [] });
    },

    playlistCreate: function (a) {
      var id = 'pl-' + Date.now();
      state.playlists.unshift({
        id: id,
        name: a.name || '新建歌单',
        cover: '',
        kind: 'user',
        source: 'local',
        remoteId: null,
        songCount: 0,
        updatedAt: Date.now()
      });
      state.plSongs[id] = [];
      return ok({ id: id });
    },

    playlistRename: function (a) {
      var meta = state.playlists.filter(function (p) {
        return p.id === a.id;
      })[0];
      if (!meta) return fail('歌单不存在');
      meta.name = a.name;
      meta.updatedAt = Date.now();
      return ok({});
    },

    playlistDelete: function (a) {
      state.playlists = state.playlists.filter(function (p) {
        return p.id !== a.id;
      });
      delete state.plSongs[a.id];
      return ok({});
    },

    playlistAddSongs: function (a) {
      var arr = state.plSongs[a.id];
      if (!arr) return fail('歌单不存在');
      var songs = a.songs || [];
      var added = 0;
      songs.forEach(function (s) {
        var dup = arr.some(function (x) {
          return x.id === s.id;
        });
        if (!dup) {
          arr.push(s);
          added++;
        }
      });
      var meta = state.playlists.filter(function (p) {
        return p.id === a.id;
      })[0];
      if (meta) {
        meta.songCount = arr.length;
        meta.updatedAt = Date.now();
      }
      return ok({ added: added });
    },

    playlistRemoveSong: function (a) {
      var arr = state.plSongs[a.id];
      if (!arr) return fail('歌单不存在');
      state.plSongs[a.id] = arr.filter(function (s) {
        return s.id !== a.songId;
      });
      var meta = state.playlists.filter(function (p) {
        return p.id === a.id;
      })[0];
      if (meta) meta.songCount = state.plSongs[a.id].length;
      return ok({});
    },

    playlistReorder: function (a) {
      var arr = state.plSongs[a.id];
      if (!arr) return fail('歌单不存在');
      if (a.from < 0 || a.to < 0 || a.from >= arr.length || a.to >= arr.length) return fail('索引越界');
      var item = arr.splice(a.from, 1)[0];
      arr.splice(a.to, 0, item);
      return ok({});
    },

    playlistExport: function (a) {
      var meta = state.playlists.filter(function (p) {
        return p.id === a.id;
      })[0];
      if (!meta) return fail('歌单不存在');
      var songs = state.plSongs[a.id] || [];
      var text =
        '# J4 歌单 v1\n' +
        'name=' + meta.name + '\n' +
        'count=' + songs.length + '\n' +
        songs
          .map(function (s) {
            return 'song\t' + s.id + '\t' + s.title + '\t' + s.artist + '\t' + s.album + '\t' + s.durationMs;
          })
          .join('\n');
      return ok({ text: text });
    },

    playlistImport: function (a) {
      var text = a.text || '';
      var name = (text.match(/name=(.+)/) || [])[1] || '导入的歌单';
      var songs = [];
      text.split('\n').forEach(function (line) {
        if (line.indexOf('song\t') !== 0) return;
        var p = line.split('\t');
        songs.push({
          id: p[1],
          source: (p[1] || '').split(':')[0] || 'local',
          title: p[2] || '未知',
          artist: p[3] || '未知',
          album: p[4] || '',
          durationMs: parseInt(p[5], 10) || 0,
          coverUrl: '',
          vip: false,
          localPath: null,
          quality: 'standard',
          extra: {}
        });
      });
      var id = 'pl-' + Date.now();
      state.playlists.unshift({
        id: id,
        name: name,
        cover: '',
        kind: 'user',
        source: 'local',
        remoteId: null,
        songCount: songs.length,
        updatedAt: Date.now()
      });
      state.plSongs[id] = songs;
      return ok({ id: id, name: name, songCount: songs.length });
    },

    playFavorites: function (a) {
      state.queue = (a.songs || []).slice();
      state.index = 0;
      state.pos = 0;
      state.playing = true;
      state.playlistId = 'favorites';
      emitPlayer({ type: 'queue', queue: state.queue, index: 0 });
      emitPlayer(Object.assign({ type: 'state' }, statusData()));
      startTick();
      return ok({});
    },

    downloadList: function () {
      return ok({ list: state.downloads });
    },

    download: function (a) {
      var s = a.song || {};
      var taskId = 'dl-' + Date.now();
      state.downloads.unshift({ songId: s.id, title: s.title || '未知歌曲', progress: 0.02, state: 'running' });
      var rec = state.downloads[0];
      setTimeout(function () {
        var t = setInterval(function () {
          rec.progress = Math.min(1, rec.progress + 0.07);
          if (rec.progress >= 1) {
            rec.state = 'done';
            clearInterval(t);
          }
          if (global.J4UI && global.J4UI.onDownloadTick) global.J4UI.onDownloadTick();
        }, 420);
      }, 300);
      return ok({ taskId: taskId });
    },

    downloadCancel: function (a) {
      state.downloads = state.downloads.filter(function (d) {
        if (d.state !== 'running') return true;
        d.state = 'canceled';
        return false;
      });
      return ok({});
    },

    localScan: function () {
      return ok({
        songs: [
          song(7),
          {
            id: 'local:a1', source: 'local', title: '录音 2024-03-11', artist: '本机录音', album: 'Recorder',
            durationMs: 42100, coverUrl: '', vip: false, localPath: '/sdcard/Recordings/rec1.m4a', quality: 'standard', extra: {}
          },
          {
            id: 'local:a2', source: 'local', title: '未命名混音', artist: '未知艺术家', album: 'Download',
            durationMs: 233000, coverUrl: '', vip: false, localPath: '/sdcard/Download/mix.mp3', quality: 'standard', extra: {}
          }
        ]
      });
    },

    localImport: function (a) {
      var files = a.files || [];
      return ok({ songs: files.map(function (f, i) { return Object.assign(song(i), { title: String(f).split('/').pop() }); }) });
    },

    translate: function (a) {
      var list = (a.paths || []).map(function (p, i) {
        var okFlag = i % 4 !== 3;
        return { in: p, out: okFlag ? String(p).replace(/\.(ncm|qmc0|qmc2|qmcflac|mflac|mgg)$/i, '.flac') : '', ok: okFlag, error: okFlag ? '' : '密钥校验失败（mock）' };
      });
      return ok({ list: list });
    },

    setQueue: function (a) {
      state.queue = (a.songs || []).slice();
      state.index = a.index || 0;
      state.pos = 0;
      state.playlistId = a.playlistId || null;
      emitPlayer({ type: 'queue', queue: state.queue, index: state.index });
      return ok({});
    },

    play: function (a) {
      if (typeof a.index === 'number' && state.queue[a.index]) {
        state.index = a.index;
        state.pos = 0;
      }
      state.playing = true;
      startTick();
      emitPlayer(Object.assign({ type: 'state' }, statusData()));
      return ok(statusData());
    },

    pause: function () {
      state.playing = false;
      stopTick();
      emitPlayer(Object.assign({ type: 'state' }, statusData()));
      return ok(statusData());
    },

    resume: function () {
      state.playing = true;
      startTick();
      emitPlayer(Object.assign({ type: 'state' }, statusData()));
      return ok(statusData());
    },

    next: function () {
      if (!state.queue.length) return ok(statusData());
      state.index = state.loop === 'shuffle' ? Math.floor(Math.random() * state.queue.length) : (state.index + 1) % state.queue.length;
      state.pos = 0;
      emitPlayer(Object.assign({ type: 'state' }, statusData()));
      return ok(statusData());
    },

    prev: function () {
      if (!state.queue.length) return ok(statusData());
      state.index = state.loop === 'shuffle' ? Math.floor(Math.random() * state.queue.length) : (state.index - 1 + state.queue.length) % state.queue.length;
      state.pos = 0;
      emitPlayer(Object.assign({ type: 'state' }, statusData()));
      return ok(statusData());
    },

    seek: function (a) {
      state.pos = Math.max(0, a.ms || 0);
      emitPlayer(Object.assign({ type: 'state' }, statusData()));
      return ok(statusData());
    },

    loopMode: function (a) {
      var m = a.mode;
      if (['list', 'one', 'shuffle'].indexOf(m) < 0) return fail('非法循环模式');
      state.loop = m;
      emitPlayer(Object.assign({ type: 'state' }, statusData()));
      return ok({ mode: m });
    },

    getStatus: function () {
      return ok(statusData());
    },

    sourceList: function () {
      return ok({
        list: [
          { id: 'netease', name: '网易云音乐', loggedIn: state.logged.netease, requiresLogin: false },
          { id: 'qq', name: 'QQ 音乐', loggedIn: state.logged.qq, requiresLogin: true },
                { id: 'huawei', name: '华为音乐', loggedIn: state.logged.huawei, requiresLogin: true }
        ]
      });
    },

    login: function (a) {
      state.logged[a.source] = true;
      return ok({ cookie: 'MUSIC_U=mock-' + a.source });
    },

    loginQrPoll: function (a) {
      return ok({ status: state.logged[a.source] ? 'success' : 'waiting' });
    },

    logout: function (a) {
      state.logged[a.source] = false;
      return ok({});
    },

    settingsGet: function () {
      return ok({
        quality: state.quality,
        theme: state.theme,
        downloadDir: state.downloadDir,
        sleepTimer: state.sleepTimer,
        cacheSize: state.cacheSize,
        cacheLimit: state.cacheLimit,
        mock: true
      });
    },

    settingsSet: function (a) {
      var k = a.key;
      var v = a.value;
      if (k === 'cacheClear') state.cacheSize = 0;
      else if (k && k in state) state[k] = v;
      else state[k] = v;
      return ok({ key: k, value: v });
    }
  };

  global.J4Mock = {
    SONGS: SONGS,
    invoke: function (method, args) {
      var fn = handlers[method];
      if (!fn) return fail('mock: 未实现的方法 ' + method);
      try {
        return fn(args || {});
      } catch (e) {
        return fail('mock: ' + (e && e.message ? e.message : e));
      }
    },
    state: state
  };
})(window);
