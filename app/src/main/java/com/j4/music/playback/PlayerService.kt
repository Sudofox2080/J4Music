package com.j4.music.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.j4.music.MainActivity
import com.j4.music.repo.MediaItemFactory
import com.j4.music.App
import com.j4.music.repo.UrlResolver
import com.j4.music.source.Song
import com.j4.music.source.SourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 后台播放服务。
 *
 * 选 Media3 而不是裸 MediaPlayer 的理由：裸 MediaPlayer 要自己管
 * 通知栏、蓝牙耳机按键、AUDIO_BECOMING_NOISY、音频焦点、媒体键，
 * 且对 FLAC/OGG 的 seek 与 gapless 支持要靠平台实现（很多 ROM 会掉）。
 * Media3 的 ExoPlayer 自带 FLAC/OGG/OPUS 解码，MediaSessionService 自动
 * 处理前台服务与通知，加上自定义 DataSource 就能解加密文件。
 *
 * 这个类同时是 JS 桥的播放后端：bridge 调它的方法，它通过
 * PlayerListener 把状态回推给 WebView。
 */
class PlayerService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 播放期间的进度节拍器，离开播放状态就取消 */
    private var tickJob: kotlinx.coroutines.Job? = null

    /** 连续「播放失败自动跳下一首」的次数，放出声就清零（见 onPlayerError） */
    private var errorSkips = 0
    private var lastErrorIndex = -1
    private var lastErrorAt = 0L

    private lateinit var resolver: UrlResolver
    private lateinit var itemFactory: MediaItemFactory

    /** 当前队列（Song 层，界面展示用全量） */
    private val queue = mutableListOf<Song>()

    /**
     * 与 player 的 mediaItems 一一对应的歌曲列表。
     * 队列是懒解析的：某首解析失败就不会进 player，所以它和 queue 的索引不能混用 ——
     * currentSong / 上一首 / 下一首 一律读这个列表。
     */
    private val playerSongs = mutableListOf<Song>()

    /**
     * 队列构建期间「界面应该显示第几首」。
     * 懒解析时播放器里只有用户点的那一首（索引 0），这时把 currentMediaItemIndex
     * 报给界面会让标题先跳到第 1 首、再一首首往下走 —— 用户看到的就是
     * 「点一下连着变换好几首歌」。构建中是用户点的位置，构建完是 -1（按播放器算）。
     */
    @Volatile
    private var buildIndex: Int = -1

    /**
     * 队列构建的世代号。每调一次 setQueue 就 +1。
     *
     * 为什么需要它：setQueue 是「先播第一首、其余在后台慢慢解析、解析完整队替换」，
     * 一次构建要花好几秒。如果用户在这期间又点了另一首歌，第二次 setQueue 会正常起播，
     * 但**第一次构建的协程还活着**，等它跑完就会拿旧的 items 再去 setMediaItems 一次 ——
     * 表现就是「新点的歌被上一首顶掉」。每步 await 之后比对世代号，过期就直接退出。
     */
    private val queueGen = java.util.concurrent.atomic.AtomicInteger(0)

    private var loopMode: LoopMode = LoopMode.LIST

    /**
     * 播放状态的只读镜像，只由播放器线程在 snapshot() 里写。
     * Activity 的返回键判定跑在主线程、拿不到 ExoPlayer 的访问权（跨线程访问会直接抛
     * "Player is accessed on the wrong thread"），所以必须有一个无锁的旁路值。
     */
    @Volatile
    private var playingFlag: Boolean = false

    /** 状态变化回调，由桥层注册 */
    var onStateChanged: ((PlayerSnapshot) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onTrackResolved: ((Int, Song) -> Unit)? = null
    var onQueueChanged: ((List<Song>) -> Unit)? = null

    /** 跨源兜底：原平台要会员、改由别的平台取到地址时回调（供界面提示） */
    var onSourceSwitched: ((Song, com.j4.music.source.PlayUrl) -> Unit)? = null

    data class PlayerSnapshot(
        val playing: Boolean,
        val index: Int,
        val positionMs: Long,
        val durationMs: Long,
        val loopMode: String,
        val bufferedMs: Long = 0L
    )

    override fun onCreate() {
        super.onCreate()
        resolver = UrlResolver(
            sourceProvider = { id -> SourceRegistry.get(id) },
            qualityOf = { App.db.get("audio.quality") ?: "lossless" },
            // 直连平台拿不到正式地址（会员墙）时，去别家找同一首的免费版本
            fallbackResolver = { song, quality -> SourceRegistry.crossResolve(song, quality) }
        )
        itemFactory = MediaItemFactory(this, resolver) { song, play ->
            // 用 late binding：这个 lambda 捕获的是属性，桥层晚注册也能收到。
            onSourceSwitched?.invoke(song, play)
        }
        restoreLoopMode()
        player = buildPlayer()
        session = buildSession(player!!)
        instance = this
        // Android 8+ 用 startForegroundService 拉起后必须 5 秒内进前台，
        // 否则系统直接抛 ForegroundServiceDidNotStartInTimeException 崩掉进程。
        // 不等第一次播放，onCreate 里立刻挂一条 dormant 通知。
        startForegroundCompat()
    }

    /**
     * 进入前台。
     *
     * MediaSessionService 在真正播放时才由内部 DefaultMediaNotificationProvider
     * 发布通知，但那时已经太晚 —— 冷启动拉起服务的那一刻就必须有前台通知。
     * 所以在 onCreate 先发一条静音的"空闲"通知挂在同样 id 上，播放时
     * media3 会复用这条通知并替换成媒体控件。
     */
    private fun startForegroundCompat() {
        makeChannel()
        startForeground(NOTIFICATION_ID, buildNotification(idle = true))
    }

    /**
     * 通知栏媒体卡片。
     *
     * 为什么不用 media3 自带的 DefaultMediaNotificationProvider：它只在真正开始播放后
     * 才发布通知，而 Android 12+ 用 startForegroundService 拉起服务时 5 秒内必须进前台，
     * 所以这里必须自己发。既然自己发，就顺手把它做成完整的媒体卡片
     * （封面、歌名、上一首/暂停/下一首三个按钮），否则后台听歌时用户只能回 App 里暂停。
     */
    private fun buildNotification(idle: Boolean = false): Notification {
        val cur = if (idle) null else currentSong()
        val playing = player?.isPlaying == true
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(cur?.title?.ifBlank { "J4Music" } ?: "J4Music")
            .setContentText(
                when {
                    idle -> "正在准备播放"
                    cur == null -> ""
                    cur.artist.isBlank() -> "离线播放"
                    else -> cur.artist
                }
            )
            .setContentIntent(openAppIntent())
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(Notification.PRIORITY_LOW)

        if (!idle) {
            b.addAction(action(ACTION_PREV, android.R.drawable.ic_media_previous, "上一首"))
            b.addAction(
                if (playing) action(ACTION_TOGGLE, android.R.drawable.ic_media_pause, "暂停")
                else action(ACTION_TOGGLE, android.R.drawable.ic_media_play, "播放")
            )
            b.addAction(action(ACTION_NEXT, android.R.drawable.ic_media_next, "下一首"))
            // compact view 只能放 3 个，正好是我们要的三个
            b.setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
        }
        return b.build()
    }

    private fun action(name: String, icon: Int, title: String): Notification.Action {
        val pi = PendingIntent.getService(
            this,
            name.hashCode(),
            Intent(this, PlayerService::class.java).setAction(name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon), title, pi).build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** 通知内容跟着当前曲目/播放状态刷新；服务没在前台时忽略（避免 IllegalStateException） */
    private fun refreshNotification() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        runCatching {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.notify(NOTIFICATION_ID, buildNotification())
        }
        runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREV -> {
                prev()
                refreshNotification()
                return START_STICKY
            }
            ACTION_NEXT -> {
                next()
                refreshNotification()
                return START_STICKY
            }
            ACTION_TOGGLE -> {
                if (player?.isPlaying == true) pause() else play()
                refreshNotification()
                return START_STICKY
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun makeChannel() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, "播放状态", NotificationManager.IMPORTANCE_LOW)
        ch.description = "后台播放时的常驻通知"
        ch.setShowBadge(false)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(ch)
    }

    private fun buildPlayer(): ExoPlayer {
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()

        loopMode.applyTo(p)
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                emit()
                syncTicker(isPlaying)
                refreshNotification()
                // 真的放出声了，说明「连续失败」的计数可以清零
                if (isPlaying) errorSkips = 0
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    // 列表循环与随机都靠 repeatMode 兜底，这里只处理异常收尾
                    if (loopMode == LoopMode.ONE) p.seekTo(p.currentMediaItemIndex, 0)
                }
                emit()
                refreshNotification()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                emit()
                refreshNotification()
                // 自动下一首 / 手动切歌：把新曲目告诉前端，标题、封面、歌词一起换。
                // 索引按「队列里的位置」给，前端拿它对齐自己的 queue 数组。
                val s = mediaItem?.let { SongMapper.fromMediaItem(it) }
                if (s != null) {
                    val qi = synchronized(queue) { queue.indexOfFirst { it.id == s.id } }
                    onTrackResolved?.invoke(if (qi >= 0) qi else p.currentMediaItemIndex, s)
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // 一首歌地址失效（会员过期/签名过期）时不要整队列停掉：
                // 跳过到下一首，同时把错误回传给前端提示。
                //
                // 但绝不能无限制地跳：歌单里连着几首都是会员曲又找不到免费替代时，
                // 每个 error 都会立刻 seekToNext → 用户看到标题/封面疯狂往后翻
                // （就是「点一下播放，它自己跳了七八首」）。这里设上限，超了就停住等用户。
                val now = System.currentTimeMillis()
                val sameItem = p.currentMediaItemIndex == lastErrorIndex && now - lastErrorAt < 1500L
                lastErrorIndex = p.currentMediaItemIndex
                lastErrorAt = now
                if (sameItem) return // 同一首的重复错误，只记一次

                onError?.invoke(error.message ?: "播放失败")
                if (p.mediaItemCount > 1 && errorSkips < MAX_ERROR_SKIPS) {
                    errorSkips++
                    p.seekToNextMediaItem()
                    p.prepare()
                    p.play()
                } else {
                    p.pause()
                    if (p.mediaItemCount > 1) {
                        onError?.invoke("连续 $MAX_ERROR_SKIPS 首都放不出来，已暂停。换个音源或检查会员登录后再试")
                    }
                }
            }
            override fun onRepeatModeChanged(repeatMode: Int) = emit()
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = emit()
        })
        return p
    }

    private fun buildSession(p: ExoPlayer): MediaSession {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return MediaSession.Builder(this, p)
            .setSessionActivity(openIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户滑掉任务：如果没在播就停服务，别在后台占资源
        val p = player ?: return
        if (!p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        player?.release()
        player = null
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    // ---------------- 播放 API（给 JS 桥用） ----------------

    /**
     * 设置队列并播放指定位置。
     *
     * 关键：**只解析用户点的那一首**，先出声，剩下的边播边解析。
     * 以前是 `songs.mapNotNull { itemFactory.build(it) }` 把整张歌单全解析一遍再 prepare，
     * 每首都要走「音质逐级降级 + 跨源兜底搜索」，一首 3–8 秒，歌单一长就变成
     * 「点一下卡半分钟」——用户还以为 App 死了。
     */
    fun setQueue(songs: List<Song>, startIndex: Int = 0, autoplay: Boolean = true) {
        val p = player ?: return
        if (songs.isEmpty()) return
        synchronized(queue) {
            queue.clear()
            queue.addAll(songs)
        }
        val start = startIndex.coerceIn(0, songs.size - 1)
        // 每次调用拿一个新世代号；老协程发现自己过期就要停止改写播放器。
        val gen = queueGen.incrementAndGet()
        // -1 表示「不在构建中」。构建期间界面上的「第几首」一律按用户点的位置算，
        // 不能读 player.currentMediaItemIndex —— 那时播放器里只有 1 首，索引恒为 0。
        buildIndex = start
        scope.launch {
            // 1) 只解析要播的那首，解析完立刻 prepare + play，声音先出来
            val first = withContext(Dispatchers.IO) {
                runCatching { itemFactory.build(songs[start]) }.onFailure {
                    onError?.invoke("《${songs[start].title}》${it.message ?: "解析失败"}")
                }.getOrNull()
            }
            // 解析期间用户可能已经点了别的歌，这时这一份结果整个作废
            if (gen != queueGen.get()) return@launch
            if (first == null) {
                onError?.invoke("《${songs[start].title}》取不到可播放地址")
                if (gen == queueGen.get()) buildIndex = -1
                emit()
                return@launch
            }
            // 播放器里的这版 Song 带着「实际拿到的音质」（MediaItemFactory 写进 metadata 的），
            // 界面据此显示 SQ/HQ；用搜索时的原始对象会把 320k/无损一律显示成 128K。
            val playedFirst = SongMapper.fromMediaItem(first) ?: songs[start]
            synchronized(playerSongs) {
                playerSongs.clear()
                playerSongs.add(playedFirst)
            }
            p.setMediaItems(listOf(first), 0, 0L)
            loopMode.applyTo(p)
            p.prepare()
            if (autoplay) p.play()
            onTrackResolved?.invoke(start, playedFirst)
            onQueueChanged?.invoke(songs)
            emit()

            // 2) 其余曲目后台解析。这里**不再逐首 addMediaItem**：
            //    懒解析期间播放器里只有 1 首歌（索引 0），把 currentMediaItemIndex 报给界面，
            //    标题就会先显示第 1 首，然后随着「往前插入」一次次右移，看起来像
            //    「从第一首开始一首首往下跳」。改成全部解析完后一次性 replace 成完整队列。
            val order = (start + 1 until songs.size).toList() + (start - 1 downTo 0).toList()
            val slots = arrayOfNulls<MediaItem>(songs.size)
            val slotSongs = arrayOfNulls<Song>(songs.size)
            slots[start] = first
            slotSongs[start] = playedFirst
            val skipped = mutableListOf<String>()
            for (i in order) {
                val item = withContext(Dispatchers.IO) {
                    runCatching { itemFactory.build(songs[i]) }.getOrNull()
                }
                // 每解析一首就检查一次：用户又点了别的歌就别再往下做了，
                // 否则最后那次 setMediaItems 会把人家刚换的队列整个顶回去。
                if (gen != queueGen.get()) return@launch
                if (item == null) {
                    // 会员曲且找不到免费替代：这件「商品」直接下架，不能留在队列里当空位，
                    // 否则列表第 N 行和 player 的第 N 项错位，点哪首放哪首就全乱了。
                    skipped.add(songs[i].title)
                    continue
                }
                slots[i] = item
                slotSongs[i] = SongMapper.fromMediaItem(item) ?: songs[i]
            }
            val items = slots.filterNotNull()
            val keptSongs = slotSongs.filterNotNull()
            // 前面可能有被下架的曲目，实际位置要按「活下来的」重新数
            var finalStart = 0
            for (i in 0 until start) if (slots[i] != null) finalStart++

            // 最后一次把关：替换队列前再确认一次没被更新的一次 setQueue 覆盖
            if (gen != queueGen.get()) return@launch

            // 收尾方式要挑：`setMediaItems(全队, idx, pos)` 会把当前那首重新 prepare 一遍，
            // 听感上是「放得好好的突然顿一下再继续」（用户会以为卡了）。
            // 改成在当前曲目两侧批量插入：媒体项只增不减，正在播的那首不受影响。
            val curItem = p.currentMediaItem
            val sameAsNow = p.mediaItemCount == 1 && curItem != null && curItem.mediaId == items.getOrNull(finalStart)?.mediaId
            if (sameAsNow && items.size > 1) {
                val tail = items.subList(finalStart + 1, items.size)
                val head = items.subList(0, finalStart)
                if (tail.isNotEmpty()) p.addMediaItems(1, tail)
                if (head.isNotEmpty()) p.addMediaItems(0, head)
            } else {
                val pos = p.currentPosition
                p.setMediaItems(items, finalStart, pos)
            }
            loopMode.applyTo(p)
            if (autoplay) p.play()

            synchronized(playerSongs) {
                playerSongs.clear()
                playerSongs.addAll(keptSongs)
            }
            synchronized(queue) {
                queue.clear()
                queue.addAll(keptSongs)
            }
            buildIndex = -1
            onQueueChanged?.invoke(keptSongs)
            if (skipped.isNotEmpty()) {
                val head = skipped.take(2).joinToString("、") { "《$it》" }
                val more = if (skipped.size > 2) " 等 ${skipped.size} 首" else ""
                onError?.invoke("${head}${more}需要会员且没找到免费替代，已从队列移除")
            }
            emit()
        }
    }

    fun playIndex(index: Int) {
        val p = player ?: return
        if (index !in 0 until p.mediaItemCount) {
            // 队列还在懒解析时，播放器里只有用户点过的第一首（mediaItemCount == 1），
            // 这时点列表里的第 N 行会越界 —— 以前直接 return，表现为「点了没反应」。
            // 改成用已知的整张歌单按新起点重建队列。
            val known = synchronized(queue) { queue.toList() }
            if (index in known.indices) setQueue(known, index, true)
            return
        }
        p.seekTo(index, 0L)
        p.play()
        emit()
    }

    fun toggle() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
        emit()
    }

    fun play() {
        player?.play()
        emit()
    }

    fun pause() {
        player?.pause()
        emit()
    }

    fun next() {
        player?.seekToNextMediaItem()
        player?.play()
    }

    fun prev() {
        val p = player ?: return
        // 播放超过 3 秒再点上一首：回到本曲开头，这是主流播放器的习惯
        if (p.currentPosition > 3000) p.seekTo(0) else { p.seekToPreviousMediaItem(); p.play() }
    }

    fun seekTo(ms: Long) {
        player?.seekTo(ms.coerceAtLeast(0))
        emit()
    }

    fun setLoopMode(mode: LoopMode) {
        loopMode = mode
        player?.let { mode.applyTo(it) }
        App.db.put("play.loopMode", mode.key)
        emit()
    }

    /** 恢复上次的循环模式 */
    private fun restoreLoopMode() {
        loopMode = LoopMode.of(App.db.get("play.loopMode") ?: "list")
    }

    fun currentSong(): Song? {
        val p = player ?: return null
        // 构建中：播放器里只有用户点的那一首，按 intent 索引回原始队列取，
        // 否则会拿 playerSongs[0] 当成「当前曲」，标题就跳到第一首去了。
        if (buildIndex >= 0) return synchronized(queue) { queue.getOrNull(buildIndex) }
        // 用 playerSongs 而不是 queue：懒解析下队列里可能有没进 player 的歌，索引不能混用
        return synchronized(playerSongs) { playerSongs.getOrNull(p.currentMediaItemIndex) }
    }

    /** 队列快照：前端重建播放列表用 */
    fun queueSongs(): List<Song> = synchronized(queue) { queue.toList() }

    /**
     * 只重建当前位置的 MediaItem（地址过期时用）。
     * 重新取一条新签名的 URL，避免「会员链接 30 分钟后 403」。
     */
    fun rebuildCurrent() {
        val p = player ?: return
        val idx = p.currentMediaItemIndex
        val song = synchronized(playerSongs) { playerSongs.getOrNull(idx) } ?: return
        val pos = p.currentPosition
        scope.launch {
            val item = withContext(Dispatchers.IO) { runCatching { itemFactory.build(song) }.getOrNull() } ?: return@launch
            p.replaceMediaItem(idx, item)
            p.seekTo(idx, pos)
            p.prepare()
            p.play()
        }
    }

    fun snapshot(): PlayerSnapshot {
        val p = player
        return PlayerSnapshot(
            playing = (p?.isPlaying == true).also { playingFlag = it },
            index = if (buildIndex >= 0) buildIndex else (p?.currentMediaItemIndex ?: 0),
            positionMs = p?.currentPosition ?: 0,
            durationMs = (p?.duration?.takeIf { it > 0 } ?: C.TIME_UNSET).let { if (it == C.TIME_UNSET) 0 else it },
            loopMode = loopMode.key,
            bufferedMs = p?.bufferedPosition ?: 0
        )
    }

    private fun emit() {
        onStateChanged?.invoke(snapshot())
    }

    /**
     * 播放中按固定间隔把进度推给前端。
     *
     * ExoPlayer 的监听回调只在「状态真的变了」时触发（开始播、暂停、切歌、seek），
     * 播放过程中位置是在走的但没有任何回调，于是前端拿到的 positionMs 一直停在
     * 最后一次 emit 的值上 —— 表现就是进度条不动、歌词停在某一行不往下滚。
     * 这里补一个只在播放期间运行的节拍器，暂停即停，避免待机时白白耗电。
     */
    private fun syncTicker(playing: Boolean) {
        if (playing) {
            if (tickJob != null) return
            tickJob = scope.launch {
                while (true) {
                    emit()
                    delay(PROGRESS_INTERVAL_MS)
                }
            }
        } else {
            tickJob?.cancel()
            tickJob = null
        }
    }

    companion object {
        /** 播放中向界面推进度的间隔：250ms 足够让进度条和歌词看起来连续，又不至于把桥打满 */
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val CHANNEL_ID = "j4_playback"
        private const val NOTIFICATION_ID = 1001

        /** 播放失败时最多自动跳过几首；再多就暂停等用户，避免「一首接一首疯狂往后翻」 */
        private const val MAX_ERROR_SKIPS = 3

        /** 通知栏三个按钮的 action（onStartCommand 里分发） */
        const val ACTION_PREV = "com.j4.music.PREV"
        const val ACTION_NEXT = "com.j4.music.NEXT"
        const val ACTION_TOGGLE = "com.j4.music.TOGGLE"

        @Volatile
        var instance: PlayerService? = null
            private set

        /**
         * 给 MainActivity 判断「现在按返回键该退到后台还是真关掉」用。
         * 读 ExoPlayer 必须在它自己的线程上，这里只读已经在快照里维护好的标记，
         * 拿不到就当作没在播 —— 退出逻辑宁可关掉也不要卡住。
         */
        fun isPlayingNow(): Boolean = instance?.playingFlag == true
    }
}
