# J4Music

一个用 WebView 承载界面、原生 Media3 承载播放的 Android 音乐播放器。

界面全部是 `app/src/main/assets/web` 下的 HTML/CSS/JS（原生 JS，没有构建步骤，改一行样式刷新就能看到效果），原生侧只负责三件事：播放引擎、媒体会话（后台播放与通知栏）、以及一个把所有功能暴露给 JS 的桥。

## 功能

- **播放**：ExoPlayer + `MediaSessionService`，后台播放、通知栏三键、耳机线控；FLAC / MP3 / OGG / M4A / WAV 多协议；队列懒解析（点歌 0.5 秒内出声，不整张歌单先解析完）。
- **界面**：深空渐变主题、唱片旋转、封面模糊背景、四套配色；每 250ms 推送进度但只重绘进度条与歌词，不整页刷新。
- **歌词**：LRC 与 yrc 逐字歌词，整行高亮 + 平滑滚动，点任意一行跳到对应进度。
- **音源**：网易云音乐、QQ 音乐，走一个可自建的上游 HTTP 服务取搜索、播放地址与歌词。
- **歌单**：完全自定义；导出 J4 文本 / M3U / CSV / TXT；系统分享；导入来自其它软件的表格；可选云歌单（存服务端，多端一致）。
- **收藏 / 下载 / 本地音乐**：都以歌单页形态呈现，支持离线播放、把下载结果直接变成一张本地歌单。
- **加密格式转译**：网易云 `.ncm`、QQ 音乐 `.qmc / .mflac / .mgg`、华为 `.qy2` 转回明文音频。
- **换源兜底**：平台 A 的会员曲拿不到正式地址时，按「歌名 + 歌手」去平台 B 找能播的版本，并明确告诉你「已改播谁的哪一版」。

## 目录结构

```
app/src/main/java/com/j4/music/
  App.kt                      Application，装配数据库与音源注册表
  MainActivity.kt             WebView 宿主 + SAF 文件选择器注册
  bridge/JsBridge.kt          JS ↔ Kotlin 唯一通道（同步 call + 事件 emit）
  playback/PlayerService.kt   Media3 播放服务、懒解析队列、通知栏、错误跳过护栏
  repo/                       歌单仓库、下载器、本地库、转译、云歌单、取址解析
  source/                     音源实现（网易云 / QQ / 上游）与地址判定
  crypto/                     NCM / QMC v1·v2 / QY2 / TEA 解密与可重放自检
app/src/main/assets/web/      前端：index.html + css/ + js/
tools/gateway.js              给上游加口令、云歌单接口、二维码生成的零依赖反代
tools/console.html            网页控制台：扫码登录、粘贴 Cookie、搜索自测
```

## 怎么用

**初次使用请先读 [使用说明](docs/使用说明.md)**——里面按「第一次打开 / 登录 / 设置项地图 / 歌单 / 转译 / 常见问题」逐条写了每个开关在哪一页、Cookie 从哪儿复制、连不上服务器怎么排查。同样的内容也内置在 App 里：设置 → 使用说明。

三步上手：

1. 先放本地音乐或转译几个文件，确认播放链路正常。
2. 想在线搜歌：设置 →「云端 · 分发会员」→ 填服务地址与口令 → 点「测试云端连通」。
3. 想要会员音质：同一处点「打开网页控制台」，在浏览器里扫码或用 Cookie 登录网易云 / QQ 音乐。

## 构建

需要 Android SDK（compileSdk 35）与 JDK 17，Gradle 8.13：

```bash
gradle :app:assembleDebug        # 或直接用它打开 Android Studio
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`。

仓库没有内置 `gradle-wrapper.jar`（二进制不入库），想用 wrapper 就在项目根跑一次 `gradle wrapper --gradle-version 8.13`。依赖版本集中在 `app/deps-lock.gradle` 里锁死，避免 Kotlin 元数据版本漂移。

## 配置上游（不配也能放本地音乐，但在线搜索与取址不可用）

仓库**不含任何服务器地址或令牌**。App 里的在线音源都指向一个你自己部署的上游服务，请求带 `X-J4-Token` 头。搭法：

1. 部署上游本体（网易云 + QQ 的接口聚合），例如 [tlyanyu/multiPlatformMusicApi](https://github.com/tlyanyu/multiPlatformMusicApi)：
   ```bash
   git clone https://github.com/tlyanyu/multiPlatformMusicApi
   cd multiPlatformMusicApi && npm i --omit=dev
   PORT=8081 HOST=127.0.0.1 node app.js
   ```
2. 用 `tools/gateway.js` 把它挡在口令后面，同时获得网页控制台与云歌单接口：
   ```bash
   J4_TOKEN=<你自己的口令> PORT=8080 TARGET_PORT=8081 node tools/gateway.js
   ```
3. App →「设置 → 云端服务」，填 `http://<你的地址>:8080` 与同一个口令。
4. 想用会员音质：打开 `http://<同一地址>/`，在里面扫码或用 Cookie 登录网易云 / QQ。登录态保存在服务端，所有连这个服务的客户端共用（这就是「上游登录一次，下游全部用它的会员音源」）。

QQ 的搜索接口对出口 IP 做突发限流，短时间连打会返回空列表；`tools/gateway.js` 里带缓存与重试，但仍然建议放在干净的 IP 上。

## 边界

- 本项目只是播放器：不内置任何音源内容，不绕过任何付费墙。会员曲目能否拿到正式地址，取决于你在上游登录的账号本身有什么权限；拿不到时会退到免费版本或试听片段，并在界面上说明。
- 加密格式转译只针对你自己已经拥有、被平台加密过的本地文件，用于在你有播放权的设备上正常播放。
- 解密实现里每条容易写错的地方都配了可重放的断言（NCM 头布局、QMC 的 MAP 回卷、QMC v1 装表后 seek、TEA 跨块混写等），App 内「设置 → 解码器自检」可以逐项跑。

## License

MIT，见 [LICENSE](LICENSE)。
