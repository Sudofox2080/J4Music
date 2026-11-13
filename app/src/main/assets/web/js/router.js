/* J4Music · router.js
 * 页面栈路由。
 *   Tab：home / discover / library / settings（底部 Tab，切换不堆栈）
 *   推入页：playlist / search / local / download / translate / login / artist
 * 播放页（player）是独立浮层，不走栈，由 player.js 控制。
 *
 * 每个页面模块注册：
 *   J4Router.register('home', { title:'首页', render(ctx) -> HTMLElement, enter(node,ctx), leave(node,ctx) })
 * ctx = { params, root, query, back() }
 */
(function (global) {
  'use strict';

  var TABS = ['home', 'discover', 'library', 'settings'];
  var pages = Object.create(null);
  var stack = []; // [{name, params, node}]
  var host = null;
  // 初始必须是 null：switchTab('home') 的第一句会在 name === currentTab 时直接
  // return。初值写成 'home' 会让启动时的 switchTab('home') 空转，页面节点从不插入
  // DOM —— 表现就是底部 Tab 栏出来了、主内容区永远全黑。
  var currentTab = null;
  var animating = false;
  var tabCache = Object.create(null);

  function register(name, def) {
    pages[name] = def;
  }

  function init(hostEl) {
    host = hostEl;
  }

  function moduleFor(name) {
    return pages[name] || null;
  }

  function build(name, params) {
    var def = moduleFor(name);
    if (!def) {
      var n = document.createElement('div');
      n.className = 'page';
      n.textContent = '未知页面：' + name;
      return n;
    }
    var ctx = {
      params: params || {},
      query: params || {},
      name: name,
      back: back,
      isTab: TABS.indexOf(name) >= 0
    };
    var node = def.render(ctx);
    node.classList.add('page');
    if (TABS.indexOf(name) >= 0) node.classList.add('page--tab');
    else node.classList.add('page--push');
    node.dataset.page = name;
    if (def.enter) {
      // 渲染后调用 enter（此时已可访问 DOM）
      requestAnimationFrame(function () {
        if (node.isConnected && def.enter) def.enter(node, ctx);
      });
    }
    node.__ctx = ctx;
    node.__def = def;
    return node;
  }

  // ---- Tab 切换 ----
  //
  // 不堆叠的保证在这里：整个宿主在任何时刻只允许存在「当前 tab 的那一个页面」。
  // 历史实现只给旧节点摘掉 is-active，真正的移除挂在 setTimeout(320) 里，而
  // .page 是 position:absolute;inset:0（css/pages.css:7），class 掉了定位还在，
  // 于是 320ms 内新页面插在后面、旧页面盖在前景照旧绘制 —— 就是「从我的切到设置
  // 后两块内容互相穿透」的截图。现在改成同步摘除，DOM 里同一时刻只有一页。
  function detachTabNode(node) {
    if (!node) return;
    node.classList.remove('is-active');
    if (node.parentNode) node.parentNode.removeChild(node);
  }

  /**
   * 把 NodeList 变成真数组。
   *
   * 为什么不用 `host.querySelectorAll(sel).filter(...)`：querySelectorAll 返回的是
   * NodeList，不是 Array，Chromium 的 NodeList 没有 filter/forEach —— 直接链式调用
   * 会抛 "filter is not a function"，而异常发生在摘旧页之前，结果是页面既不清理也不
   * 切换，整个 tab 系统卡死。NodeList 只保证 length 与 item()，所以这里只依赖这两样。
   */
  function toArray(list) {
    var out = [];
    if (!list) return out;
    for (var i = 0; i < list.length; i++) out.push(list[i]);
    return out;
  }

  function tabPages() {
    return toArray(host ? host.querySelectorAll('.page--tab') : null);
  }

  function switchTab(name, opts) {
    if (TABS.indexOf(name) < 0) return;
    if (!host) { currentTab = name; return; }
    if (name === currentTab && !(opts && opts.force)) return;
    // 切换 tab 时清空推入栈
    clearStack();

    // 1) 先把宿主里所有非当前目标的 tab 页摘掉（包括本该被上面 clearStack 处理、
    //    但把节点留在 DOM 里的残留）。这一步是幂等的，重复调用不会误删新节点。
    tabPages().forEach(function (n) {
      if (n !== tabCache[name]) detachTabNode(n);
    });

    var old = host.querySelector('.page--tab.is-active');
    var cached = tabCache[name];
    var node = cached || build(name, {});
    if (!cached) {
      node.__ctx.isTab = true;
    }
    tabCache[name] = node;
    if (node.parentNode !== host) host.appendChild(node);
    // 2) 新节点立刻接管：加 is-active、去掉可能残留的隐藏态
    node.classList.add('is-active');
    node.style.visibility = 'visible';
    currentTab = name;
    J4Store.setState({ tab: name });
    if (old && old !== node) {
      // 3) 旧节点同步摘除。以前这里 setTimeout 延迟 320ms 再删，窗口期内两个
      //    absolute 页面叠着；keepAlive 分支还会反向把 visibility 设回 visible。
      //    缓存节点本身留在 tabCache 里，下次切回来直接复用，不需要挂在 DOM 上。
      detachTabNode(old);
    }
    if (old !== node) {
      node.style.animation = 'none';
      /* 强制重排以重放动画 */
      void node.offsetWidth;
      node.style.animation = 'tab-fade-in 260ms cubic-bezier(0.22,0.9,0.28,1) both';
    }
    if (node.__def && node.__def.show) node.__def.show(node, node.__ctx);
    J4Store.emit('tab:changed', name);
    notifyBackState();
  }

  // ---- 推入 ----
  function push(name, params) {
    if (!host) return null;
    // 名字没注册就直接退回：以前会照样 build 出「未知页面：xxx」并压进栈，
    // 结果是一张白纸盖在界面上、返回键还得点一次才回来。
    // （播放页不是路由页，它走 J4Player.open() 的全屏浮层，误传 'player' 就会踩到这里。）
    if (!moduleFor(name)) {
      if (global.J4UI && global.J4UI.toast) global.J4UI.toast('这一页还没做好：' + name, 'warn', 1600);
      return null;
    }
    // 推入页必须盖在「唯一的当前 tab 页」上面，所以先做一次归属校正：
    // 宿主里出现多个 .page--tab 时（历史遗留/外部操作），只留 currentTab 那一个。
    // 不做这步的话，push 出来的页面会夹在两张 tab 页之间，z-index 相同、DOM 序靠后
    // 的那张 tab 页反而压在推入页上 —— 表现同样是「页面叠着、点哪里都不对」。
    var strayTabs = tabPages().filter(function (n) {
      return n.dataset.page !== currentTab || n !== tabCache[currentTab];
    });
    strayTabs.forEach(detachTabNode);

    var prev = host.querySelector('.page--tab.is-active');
    var node = build(name, params || {});
    host.appendChild(node);
    stack.push({ name: name, params: params || {}, node: node });
    node.style.animation = 'page-push-in 340ms cubic-bezier(0.22,0.9,0.28,1) both';
    if (prev) {
      prev.style.animation = 'page-push-out-back 340ms cubic-bezier(0.22,0.9,0.28,1) both';
      prev.style.pointerEvents = 'none';
    }
    J4Store.setState({ stack: stack.map(function (s) { return s.name; }) });
    notifyBackState();
    return node;
  }

  function clearStack() {
    while (stack.length) {
      var top = stack.pop();
      // 栈里已经退掉但还挂在 DOM 上的推入页也要摘 —
      // back() 的 300ms 动画窗口会留下「已出栈、仍在宿主里」的节点。
      if (top.node && top.node.parentNode) top.node.parentNode.removeChild(top.node);
      if (top.node) top.node.classList.remove('is-active');
      if (top.node && top.node.__def && top.node.__def.leave) top.node.__def.leave(top.node, top.node.__ctx);
    }
    var tabs = host.querySelectorAll('.page--tab');
    for (var i = 0; i < tabs.length; i++) {
      tabs[i].style.animation = '';
      tabs[i].style.pointerEvents = '';
    }
  }

  // back() 以前被一个全局 animating 锁把住，而解锁写在 300ms 的 setTimeout 里；
  // 如果在那 300ms 内发生了切 tab（clearStack 把栈清了），回调永远拿不到下一次
  // back 的机会，animating 会卡在 true —— 之后物理返回键在整个会话里彻底失效。
  // 现在每次 back 各自持有锁的令牌，只有持有者才重置。
  var backToken = 0;
  var animatingSince = 0;

  // 返回 true 表示这次真的退掉了一页；false 表示当前没法退。
  // 以前这里是静默 return，调用方（app.js 的 handleBack）拿不到结果就一律认为
  // “已消费”，于是返回键被吃掉、页面却不动 —— 表现为按返回没反应。
  function back() {
    if (animating) return false;
    if (!stack.length) return false;
    if (!host) return false;
    // 上一轮动画的定时器可能被页面切换清掉了，导致 animating 永远挂在 true。
    // 这里按时间自愈，避免返回键在会话中途彻底失效。
    if (animatingSince && Date.now() - animatingSince > 800) animating = false;
    if (animating) return false;
    animatingSince = Date.now();
    var mine = ++backToken;
    var top = stack.pop();
    var node = top.node;
    var prevTab = host.querySelector('.page--tab.is-active');
    node.style.animation = 'page-pop-out 300ms cubic-bezier(0.6,0.05,0.2,1) both';
    if (prevTab) prevTab.style.animation = 'page-pop-in-back 300ms cubic-bezier(0.22,0.9,0.28,1) both';
    setTimeout(function () {
      if (node.parentNode) node.parentNode.removeChild(node);
      if (node.__def && node.__def.leave) node.__def.leave(node, node.__ctx);
      if (prevTab && prevTab.parentNode) {
        prevTab.style.animation = '';
        prevTab.style.pointerEvents = '';
      }
      if (mine === backToken) animating = false;
    }, 300);
    J4Store.setState({ stack: stack.map(function (s) { return s.name; }) });
    notifyBackState();
    return true;
  }

  function replace(name, params) {
    if (stack.length) {
      var top = stack.pop();
      if (top.node && top.node.parentNode) top.node.parentNode.removeChild(top.node);
    }
    push(name, params);
  }

  function top() {
    return stack.length ? stack[stack.length - 1] : null;
  }

  function notifyBackState() {
    // 栈深一变，「能不能返回」的结论就变了；app.js 监听这个事件后
    // 用同步桥把结论推给原生（原生的 onBackPressed 必须当场拿到答案）。
    try { J4Store.emit('route:changed', stack.length); } catch (e) {}
    try { global.dispatchEvent(new Event('j4:route')); } catch (e) {}
  }

  function current() {
    return top() || { name: currentTab };
  }

  function depth() {
    return stack.length;
  }

  // 统一处理返回键（安卓物理返回/手势）
  function handleBack() {
    if (global.J4UI && global.J4UI.closeTopOverlay()) return true;
    if (stack.length) {
      back();
      return true;
    }
    return false;
  }

  // 其它模块（ui.js 的浮层、player.js 的播放页）状态一变也要通知原生，
  // 所以除了事件之外再挂一个全局入口；不挂的话它们只能 ReferenceError。
  global.J4NotifyBackState = notifyBackState;

  global.J4Router = {
    TABS: TABS,
    register: register,
    init: init,
    switchTab: switchTab,
    push: push,
    replace: replace,
    back: back,
    top: top,
    current: current,
    depth: depth,
    handleBack: handleBack,
    get tab() {
      return currentTab;
    },
    get stack() {
      return stack;
    }
  };
})(window);
