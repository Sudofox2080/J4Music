# J4Music

一个希望把多平台歌单、本地音乐和加密格式文件整理到一起的 Android 音乐播放器。

[使用说明](docs/使用说明.md) · [核心难点](#核心难点) · [排错记录](#两次真实的排错记录) · [构建](#本地构建) · [未来规划](#未来规划)

## 项目背景

音乐收藏很容易散落在不同地方：网易云里有一张歌单，QQ 音乐里有另一张，电脑上还留着下载过的音频和平台加密文件。换一个播放器，往往也意味着重新整理歌单、处理格式兼容，再确认哪些歌曲能播放。

J4Music 想解决的是这段割裂的使用过程：让歌单成为统一入口，把搜索、收藏、下载、本地导入和格式转译接到同一套播放体验里。多平台整合不只是在页面上放两个搜索框，还要处理不同的歌曲标识、登录态、音质档位和失败反馈。

界面采用 WebView，是为了方便迭代歌词、封面和列表交互；播放交给 Kotlin 与 Media3，是为了接入 Android 的后台服务、通知栏和媒体控制。这个项目的重点，也逐渐从“做出一个播放页”变成了“让界面、播放状态和音源结果始终对得上”。

## 现在能做什么

| 场景 | 当前能力 |
| --- | --- |
| 日常听歌 | 原生播放服务、后台播放、通知栏控制、列表循环 / 单曲循环 / 随机播放 |
| 整理收藏 | 自定义歌单、收藏、下载管理、本地音乐扫描，导入 JSON / M3U / CSV / TXT 等歌单文本，导出与系统分享 |
| 多平台接入 | 通过自建上游服务接入网易云和 QQ 音乐的搜索、播放地址、歌词与登录能力 |
| 音源不可用 | 尝试跨平台查找同名歌曲，提示实际替换的歌手、版本和音质 |
| 歌词与界面 | 整行歌词高亮、平滑滚动、翻译、点行跳转、旋转封面与主题切换 |
| 本地格式处理 | 提供 NCM、QMC、QY2 等格式的转译入口，输出明文音频并加入歌单；兼容范围见下文 |
| 多端歌单 | 可选自建云歌单服务，保留本地歌单操作入口 |

在线功能需要配置上游服务，本地音乐和格式转译可以先独立使用。当前逐字时间数据会被解析，但前端 `RICH_ENABLED = false`，实际呈现的是整行高亮。

## 核心难点

### WebView 与 Kotlin：让一次调用有结果，让播放状态持续更新

WebView 的 `@JavascriptInterface` 调用需要同步返回，而搜索、取址和文件操作需要等待。播放进度、自动切歌等变化又可能发生在用户没有主动调用接口的时候。因此，通信分为请求结果和主动事件两条通道。

1. **统一入口与返回结构。** Kotlin 用 `addJavascriptInterface` 注册 `J4Native`；前端只调用 `J4Bridge.call(method, args)`，由桥负责 JSON 序列化以及 `{ok, data}` / `{ok, error}` 的解包。
2. **耗时任务用编号回填。** 原生收到异步请求后立即返回带 `__async` 和 `id` 的票据，在协程里执行操作；完成后通过 `window.__J4BridgeResolve(id, json)` 找到前端等待中的 Promise。失败也回填错误，前端另有 60 秒超时，避免加载状态一直挂着。
3. **明确线程边界。** WebView 脚本回调通过主线程执行；访问播放器状态与控制播放器也要切回主线程，网络和文件任务在后台处理。
4. **播放变化主动推送。** 原生通过 `__J4NativeEvent` 推送 `state`、`track`、`queue` 等事件。播放期间约每 250ms 推送进度，前端据此更新播放状态、进度条和歌词。

返回键也是一个典型边界：Android 必须立即决定是否退出，但 `evaluateJavascript` 的结果异步返回。这里由前端在路由或面板变化时提前同步“能否处理返回”，原生读取缓存后再通知页面，避免在主线程等待 JS 回调。

实现入口：[MainActivity.kt](app/src/main/java/com/j4/music/MainActivity.kt)、[JsBridge.kt](app/src/main/java/com/j4/music/bridge/JsBridge.kt)、[bridge.js](app/src/main/assets/web/js/bridge.js)。

### NCM：从“解出字节”到“能定位问题、能分块读取”

NCM 需要解析文件头、密钥区、元数据和音频体。只要起始偏移或密钥表索引错了，输出就可能是噪音；播放器报“解码失败”也无法直接说明是哪一步出了问题。

当前实现把这条链路拆开处理：

- **先检查结构。** `NcmCipher` 对魔数、长度、密钥明文前缀和截断读取分别检查，错误信息带上字段或偏移，尽早定位问题。
- **把音频坐标与文件坐标分开。** 文件读取位置是 `dataStart + decPos`，解密器收到的是相对音频体的 `decPos`。密钥流按这个位置计算，分块或随机读取时才不会因为文件头长度而错位。
- **转译不把整首歌装进内存。** `Transcode` 分块读写到临时文件，检查输出音频头并确定后缀，成功后再生成最终文件和歌单记录。
- **把易错点做成可重放自检。** `CryptoProbe` 包含 NCM 合成往返、密钥表置换、头部偏移等断言，也覆盖其他解密器的分块和随机读取边界。App 内可从“设置 → 解码器自检”运行。

需要说明验证范围：NCM 的现有自检主要使用合成样本，不能据此认定所有真实网易云文件都兼容。仓库已经实现随机访问解密读取器和 DataSource 工厂，但播放服务尚未接入该工厂；当前应先转译为明文音频再播放，加密文件直接边解边播和真机拖动进度仍需完成接入与验证。

相关代码：[NcmCipher.kt](app/src/main/java/com/j4/music/crypto/NcmCipher.kt)、[DecryptingReader.kt](app/src/main/java/com/j4/music/crypto/DecryptingReader.kt)、[Transcode.kt](app/src/main/java/com/j4/music/repo/Transcode.kt)、[CryptoProbe.kt](app/src/main/java/com/j4/music/crypto/CryptoProbe.kt)。

## 两次真实的排错记录

以下记录来自 2026 年 11 月的提交，保留触发条件、原因和修复方式，方便继续排查同类问题。

### 连续点歌，新歌却被上一首顶掉

**现象：** 点 A 后很快再点 B，B 已经开始播放，过一会儿却又回到 A 的队列；有时第一首播放正常，后台补完队列时会突然顿一下。

**原因：** 为了尽快出声，队列采用“先解析当前曲，其余后台补齐”的方式。第二次点歌没有让第一次构建失效，旧协程完成后仍会修改播放器；而整队调用 `setMediaItems` 又会重新准备正在播放的媒体项。

**处理：** 每次构建递增 `queueGen`，在异步解析返回和最终修改队列之前核对编号，让过期任务停止写入。当前曲仍一致时，改为在其前后批量插入其他曲目，保留正在播放的媒体项；队列未补齐时点击其他行，也会按新的起点重新构建。

这次排错留下的经验是：异步任务完成后，还要判断结果是否属于用户当前的操作。对应提交：[1594b0f](https://github.com/Sudofox2080/J4Music/commit/1594b0f54729413a7c140f54e7ce83ce8266d72e)。

### 扫码显示已登录，会员曲仍只拿到试听

**现象：** 网页控制台已经保存登录态，但会员曲取址仍像匿名请求，返回试听或失败。

**原因：** 网关只把登录态放进 `?cookie=` 查询参数，而上游模块实际从 HTTP `Cookie` 请求头读取。与此同时，客户端残留的旧票据还可能覆盖服务端刚保存的新票据。

**处理：** 将选定的登录态明确写入转发请求的 `Cookie` 头，优先使用服务端存档；QQ 取址所需的 `uin`、`qm_keyst` 等字段另行补齐。新增 `verify-gateway.js`，分别检查账号态、会员曲、免费曲对照和 QQ 取址。

这次排错提醒了项目：界面上的“已登录”只能证明某一层成功，必须继续验证票据是否真正到达取址接口。对应提交：[653f826](https://github.com/Sudofox2080/J4Music/commit/653f826fc302422139288b8fd537867610ae38d5)。

## 开始使用

1. 先播放本地音乐，或从“设置 → 音乐转译”选择文件，确认基础播放链路。
2. 需要在线搜索时，在“设置 → 云端 · 分发会员”填写上游地址和访问令牌，点击“测试云端连通”。
3. 需要使用自己账号的权益时，打开网页控制台扫码或导入 Cookie；实际可用音质仍由平台和账号权限决定。

完整操作步骤、设置位置和常见问题见 [使用说明](docs/使用说明.md)，App 内也可从“设置 → 使用说明”查看。

## 本地构建

需要 **Android SDK Platform 35、JDK 17 和 Gradle 8.13**；App 最低支持 Android 7.0（API 24）。

```bash
git clone https://github.com/Sudofox2080/J4Music.git
cd J4Music
java -version
gradle --version
gradle :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

仓库没有提交 Gradle Wrapper。可先安装 Gradle 8.13，或用已安装的 Gradle 执行 `gradle wrapper --gradle-version 8.13` 生成本地 Wrapper。Android Studio 用户可直接打开项目根目录，将 Gradle JDK 设为 JDK 17。依赖约束位于 [app/deps-lock.gradle](app/deps-lock.gradle)，界面 HTML/CSS/JS 无需额外的前端构建。

## 配置上游

上游负责平台接口，仓库内网关负责访问口令、登录态转发、网页控制台和云歌单。需要 Node.js 18 或更高版本。

1. 在独立目录部署兼容上游，例如 [tlyanyu/multiPlatformMusicApi](https://github.com/tlyanyu/multiPlatformMusicApi)：

   ```bash
   git clone https://github.com/tlyanyu/multiPlatformMusicApi.git
   cd multiPlatformMusicApi
   npm install --omit=dev
   PORT=8081 HOST=127.0.0.1 node app.js
   ```

2. 保持上游运行，另开终端，在 **J4Music 仓库根目录**安装二维码依赖并准备控制台文件：

   ```bash
   npm install --no-save --package-lock=false qrcode
   mkdir -p tools/public
   cp tools/console.html tools/public/index.html
   ```

3. 启动网关，将占位内容替换为自己的口令：

   ```bash
   J4_TOKEN='<自行生成的口令>' PORT=8080 TARGET_PORT=8081 node tools/gateway.js
   ```

4. App 填写 `http://<服务器地址>:8080` 和同一口令，通过 App 的“打开网页控制台”入口登录。服务端保存的登录态由连接该网关的客户端共用；`COOKIE_FILE`、`PLAYLIST_DIR` 可指定持久化位置。

部署后，在 J4Music 根目录运行网关自检：

```bash
J4_GATEWAY=http://127.0.0.1:8080 J4_TOKEN='<同一口令>' node tools/verify-gateway.js
```

自检依赖运行中的上游和有效账号权益，不是离线测试。QQ 搜索还会受到出口 IP 限流影响；空结果需要结合网关日志和平台返回排查。

## 未来规划

以下是后续方向，尚未作为完整能力交付：

- [ ] **优化歌词动效。** 先拆分进度与队列、循环模式更新，让进度包不再触发完整页面刷新；改善连续短句、长句换行和手动滚动后的回中。评估以原生进度为基准的前台插值，在暂停、拖动和换歌时重新校准，减少滚动跳动。
- [ ] **按设备能力控制动画开销。** 为封面模糊、缩放和歌词过渡提供更明确的动效档位，测量低端机上的帧耗时与耗电表现。
- [ ] **补齐真实文件验证。** 在取得授权的 NCM / QMC / QY2 样本上记录兼容矩阵，覆盖损坏文件、不同变体和边界读取；完成解密 DataSource 与播放服务的接入及真机 seek 验收。
- [ ] **完善多平台歌单匹配。** 进一步区分同名歌、翻唱与现场版，展示匹配依据，让用户能够确认或修正换源结果。
- [ ] **沉淀回归场景。** 将连续换歌、慢网取址、登录态失效、后台恢复和长歌单播放整理成可重复执行的检查流程。

## 代码入口

| 路径 | 作用 |
| --- | --- |
| [app/src/main/assets/web](app/src/main/assets/web) | 页面、样式、前端状态和歌词渲染 |
| [bridge](app/src/main/java/com/j4/music/bridge) | JS 调用、异步回填、事件推送与歌词数据转换 |
| [playback](app/src/main/java/com/j4/music/playback) | Media3 服务、队列、媒体会话与通知栏 |
| [source](app/src/main/java/com/j4/music/source) | 平台差异、上游请求与播放地址判定 |
| [repo](app/src/main/java/com/j4/music/repo) | 歌单、本地库、下载、转译与云同步 |
| [crypto](app/src/main/java/com/j4/music/crypto) | 格式解析、解密、随机访问读取与自检 |
| [tools/gateway.js](tools/gateway.js) | 网关与登录态转发 |

## 使用边界与许可证

J4Music 不内置音乐内容。在线播放取决于上游可用性及账号已有权限；跨平台匹配可能找到其他版本，播放提示会说明替换结果。格式转译用于自己有权使用的本地文件。

网关口令、Cookie 和账号信息由使用者自行配置，请勿提交到仓库。

项目以 [MIT License](LICENSE) 发布。
