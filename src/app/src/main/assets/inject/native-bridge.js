/**
 * DeepSeek 续聊助手 —— 原生桥接垫片（document-start 注入，仅可信 origin）。
 *
 * 页面 → 原生（JSON 字符串经 assistantNativeBridge.postMessage）：
 *   {type:'ready'}                         页面就绪信号（加载后必发一次，建立原生→页面回传通道）
 *   {type:'getKey'}                        原生回 {type:'keyValue', value:string}
 *   {type:'saveKey', key}                  页面写 Key（上游设置界面）→ 原生加密保存
 *   {type:'deleteKey'}                     页面删 Key → 原生删除
 *   {type:'pickImport'}                    请求打开系统文件选择器（SAF）
 *   {type:'importResult', ok, code, count} 导入结果反馈（count=追加导入的会话数）
 *   {type:'exportStart', id, name, size}   导出落盘开始（备份）
 *   {type:'exportChunk', id, index, data}  导出分块（base64）
 *   {type:'exportEnd', id}                 导出结束
 *   {type:'exportError', id, reason}       导出失败
 *
 * 原生 → 页面（assistantNativeBridge.onmessage）：
 *   {type:'keyValue', value}               Key 变更（value 为空串表示已删除）
 *   {type:'deliverImport', url, name}      旧链路：投递所选 ZIP 给官方导入入口
 *   {type:'appendImport', url, name}       追加导入：直接调用官方解析与写库（0.2.0）
 *
 * 安全设计（设计稿 6.2 / 七）：
 *   - Key 只存在本内存变量，绝不写入 localStorage；
 *   - 本脚本由原生以 addDocumentStartJavaScript 注入，只在可信 origin 生效。
 */
(function () {
  'use strict';
  if (window.__DS_ASSISTANT_BRIDGE__) return;

  var cfg = window.__ASSISTANT_CONFIG__ || {};
  var KEY_NAMES = cfg.localStorageKeys || [];
  var IMPORT_SELECTORS = cfg.importFileInputSelectors || [];
  var MAX_EXPORT = cfg.maxExportBytes || 419430400;
  var CHUNK = cfg.exportChunkBytes || 262144;

  // 启动期 Key：由原生嵌入（JSON 转义），保证上游首次同步读取即可命中
  var memKey = typeof window.__ASSISTANT_BOOT_KEY__ === 'string' && window.__ASSISTANT_BOOT_KEY__
    ? window.__ASSISTANT_BOOT_KEY__
    : null;
  try { delete window.__ASSISTANT_BOOT_KEY__; } catch (e) { /* 旧引擎忽略 */ }

  var native = window.assistantNativeBridge || null;

  function postToNative(payload) {
    if (!native || typeof native.postMessage !== 'function') return false;
    try {
      native.postMessage(JSON.stringify(payload));
      return true;
    } catch (e) {
      return false;
    }
  }

  /* ---------------- Storage 垫片：Key 迁出 localStorage ---------------- */

  var origGet = Storage.prototype.getItem;
  var origSet = Storage.prototype.setItem;
  var origRemove = Storage.prototype.removeItem;

  function isTrackedKey(k) {
    return KEY_NAMES.indexOf(k) !== -1;
  }

  function isLocalStorage(store) {
    try {
      return store === window.localStorage;
    } catch (e) {
      return false;
    }
  }

  Storage.prototype.getItem = function (k) {
    if (isLocalStorage(this) && isTrackedKey(k)) return memKey;
    return origGet.call(this, k);
  };

  Storage.prototype.setItem = function (k, v) {
    if (isLocalStorage(this) && isTrackedKey(k)) {
      memKey = String(v);
      postToNative({ type: 'saveKey', key: memKey });
      try { origRemove.call(this, k); } catch (e) { /* 忽略 */ }
      syncBrandFromKey();
      return undefined;
    }
    return origSet.call(this, k, v);
  };

  Storage.prototype.removeItem = function (k) {
    if (isLocalStorage(this) && isTrackedKey(k)) {
      memKey = null;
      postToNative({ type: 'deleteKey' });
      syncBrandFromKey();
      return undefined;
    }
    return origRemove.call(this, k);
  };

  // 迁移：若本 origin 下残留过明文 Key（如安全改造前的旧版本），收编后删除
  if (!memKey) {
    KEY_NAMES.forEach(function (k) {
      try {
        var legacy = origGet.call(window.localStorage, k);
        if (legacy) {
          memKey = legacy;
          postToNative({ type: 'saveKey', key: legacy });
          origRemove.call(window.localStorage, k);
        }
      } catch (e) { /* 忽略 */ }
    });
  }

  /* ---------------- 原生 → 页面 ---------------- */

  function extractText(raw) {
    if (typeof raw === 'string') return raw;
    if (raw && typeof raw.data === 'string') return raw.data;
    if (raw && raw.data && typeof raw.data.data === 'string') return raw.data.data;
    return null;
  }

  function onNativeMessage(raw) {
    var text = extractText(raw);
    if (!text) return;
    var msg;
    try { msg = JSON.parse(text); } catch (e) { return; }
    switch (msg.type) {
      case 'keyValue':
        memKey = msg.value ? String(msg.value) : null;
        syncBrandFromKey();
        break;
      case 'deliverImport':
        deliverImport(msg.url, msg.name);
        break;
      case 'appendImport':
        appendImport(msg.url, msg.name);
        break;
      default:
        break;
    }
  }

  var readySent = false;

  function bindNativeListener() {
    native = window.assistantNativeBridge || native;
    if (native) {
      try { native.onmessage = onNativeMessage; } catch (e) { /* 忽略 */ }
      if (!readySent) {
        readySent = true;
        // 就绪信号：原生只有在收到页面首条消息后才能拿到 reply 通道；
        // 不发 ready，原生侧发起的导入会永远提示“页面尚未就绪”。
        postToNative({ type: 'ready' });
      }
      return true;
    }
    return false;
  }

  if (!bindNativeListener()) {
    var attempts = 0;
    var timer = setInterval(function () {
      attempts += 1;
      if (bindNativeListener() || attempts > 200) clearInterval(timer);
    }, 50);
  }

  /* ---------------- 导入投递：交给官方导入器 ----------------
   *
   * 官方导入入口（隐藏 <input type=file>）只在「设置」弹窗打开且停在
   * 数据标签页时才挂载（上游 open ? jsx(SettingsDialog) : null）。
   * 因此投递分两步：
   *   1) 入口已挂载 → 立即 fetch 原生流并填入 input，回 importResult ok；
   *   2) 入口未挂载 → 回 NO_INPUT_YET，原生暂存文件；本脚本每 800ms
   *      轮询入口（用户随后打开 设置→数据 即可），最长 3 分钟，超时回
   *      INPUT_TIMEOUT。结果码：ok / NO_INPUT_YET / INPUT_TIMEOUT /
   *      NO_FILE_INPUT / FETCH_HTTP_<n> / FETCH_FAILED。
   */

  function sendImportResult(ok, code, count) {
    postToNative({ type: 'importResult', ok: !!ok, code: code || '', count: count || 0 });
  }

  var pendingDelivery = null; // {url, name} 待投递文件（等待入口出现）
  var inputWatchTimer = null;

  function stopInputWatcher() {
    if (inputWatchTimer) {
      clearInterval(inputWatchTimer);
      inputWatchTimer = null;
    }
  }

  function findImportInput() {
    for (var i = 0; i < IMPORT_SELECTORS.length; i++) {
      var el = document.querySelector(IMPORT_SELECTORS[i]);
      if (el) return el;
    }
    return null;
  }

  function failDelivery(code) {
    stopInputWatcher();
    pendingDelivery = null;
    sendImportResult(false, code);
  }

  async function tryDeliver() {
    if (!pendingDelivery) return;
    var input = findImportInput();
    if (!input) return; // 入口还没出现，等下一轮轮询
    var url = pendingDelivery.url;
    var name = pendingDelivery.name;
    try {
      var res = await fetch(url, { credentials: 'omit' });
      if (!res.ok) {
        failDelivery('FETCH_HTTP_' + res.status);
        return;
      }
      // 流式读取原生挂载的 ZIP；Chromium 会把大 blob 落盘，不占满内存
      var blob = await res.blob();
      var file = new File([blob], name || 'deepseek_data.zip', { type: 'application/zip' });
      var dt = new DataTransfer();
      dt.items.add(file);
      input.files = dt.files;
      input.dispatchEvent(new Event('change', { bubbles: true }));
      // 官方导入器随后自行弹出覆盖确认并显示结果（上游自身交互）
      stopInputWatcher();
      pendingDelivery = null;
      sendImportResult(true, '');
    } catch (e) {
      failDelivery('FETCH_FAILED');
    }
  }

  function deliverImport(url, name) {
    stopInputWatcher();
    pendingDelivery = { url: url, name: name };
    var input = findImportInput();
    if (input) {
      tryDeliver();
      return;
    }
    // 入口未挂载：告知原生「已暂存、等待入口」，随后轮询
    sendImportResult(false, 'NO_INPUT_YET');
    var deadline = Date.now() + 3 * 60 * 1000;
    inputWatchTimer = setInterval(function () {
      if (!pendingDelivery) {
        stopInputWatcher();
        return;
      }
      if (Date.now() > deadline) {
        failDelivery('INPUT_TIMEOUT');
        return;
      }
      tryDeliver();
    }, 800);
  }

  /* ---------------- 追加导入：直接调用官方代码（0.2.0） ----------------
   *
   * 官方导入 = importArchive() 解析 ZIP → IndexedDB 暂存区（sessionsStaging/
   * filesStaging，含图片 blob 与 mimeType 修复），随后 UI 层调用提交函数把
   * 暂存内容搬入现网库——但提交前会**清空现网全部会话**（官方文案「覆盖当
   * 前所有对话记录」的实现点），这就是覆盖语义的来源。
   *
   * 我们复用官方前半段（解析、校验、转换、图片落库全部官方代码），只替换
   * 提交步骤：用官方常规写库 E1(session, files) 把暂存会话逐条 upsert 进
   * 现网库——不调用清库提交 → 等效追加导入。同 id 会话按导入侧覆盖（与
   * 官方备份恢复语义一致），不同 id 全部并入。
   *
   * 页面内实现（chat-demo 构建指纹，来自官方产物逆向）：
   *   - rspack 分块全局 self.rspackChunkchat_demo，push 探针回调取 require；
   *   - 解析器模块 8444（异步分块 6061+925）：{checkArchiveFormat, importArchive}；
   *   - 存储模块 3599：Le=清暂存，E1=会话 upsert（files+sessions+summaries）；
   *   - IndexedDB 库名 "chat-demo"（暂存区读取走原生 indexedDB API）。
   * 页面更新导致指纹失效时回 MODULE_NOT_FOUND，原生侧回退旧官方 UI 流程。
   */

  var appendBusy = false;

  function resolveWebpackRequire() {
    var chunks = self.rspackChunkchat_demo;
    if (!chunks || typeof chunks.push !== 'function') return null;
    var req = null;
    try {
      // 每次用全新探针 id（运行时会把探针标记为已加载，复用 id 将不再回调）；
      // 假分块不在注册表中 → 回调本探针并交出 __webpack_require__
      chunks.push([['__ds_append_probe__' + Date.now()], {}, function (r) { req = r; }]);
    } catch (e) {
      return null;
    }
    return req && typeof req.e === 'function' ? req : null;
  }

  function openAppDb() {
    return new Promise(function (resolve, reject) {
      var open;
      try {
        open = indexedDB.open('chat-demo'); // 不带版本号：按当前版本打开，不触发升级
      } catch (e) {
        reject(e);
        return;
      }
      open.onsuccess = function () { resolve(open.result); };
      open.onerror = function () { reject(open.error || new Error('idb open failed')); };
      open.onblocked = function () { reject(new Error('idb blocked')); };
    });
  }

  async function readStaging() {
    var db = await openAppDb();
    try {
      var sessions = await new Promise(function (resolve, reject) {
        var req = db.transaction('sessionsStaging', 'readonly')
          .objectStore('sessionsStaging').getAll();
        req.onsuccess = function () { resolve(req.result || []); };
        req.onerror = function () { reject(req.error || new Error('read sessions failed')); };
      });
      var files = await new Promise(function (resolve, reject) {
        var req = db.transaction('filesStaging', 'readonly')
          .objectStore('filesStaging').getAll();
        req.onsuccess = function () { resolve(req.result || []); };
        req.onerror = function () { reject(req.error || new Error('read files failed')); };
      });
      return { sessions: sessions, files: files };
    } finally {
      try { db.close(); } catch (e) { /* 忽略 */ }
    }
  }

  function mapAppendError(e) {
    // 官方校验错误为 {key:'backupXxx'} 形态；其余按通用失败处理
    var key = e && e.key ? String(e.key).toUpperCase() : '';
    return key ? 'BACKUP_' + key : 'APPEND_FAILED';
  }

  async function appendImport(url, name) {
    if (appendBusy) {
      sendImportResult(false, 'APPEND_BUSY');
      return;
    }
    appendBusy = true;
    try {
      var res = await fetch(url, { credentials: 'omit' });
      if (!res.ok) {
        sendImportResult(false, 'FETCH_HTTP_' + res.status);
        return;
      }
      var blob = await res.blob();
      var file = new File([blob], name || 'deepseek_data.zip', { type: 'application/zip' });

      var req = resolveWebpackRequire();
      if (!req) {
        sendImportResult(false, 'MODULE_NOT_FOUND');
        return;
      }
      var storage = null;
      try { storage = req(3599); } catch (e) { storage = null; }
      if (!storage || typeof storage.Le !== 'function' || typeof storage.E1 !== 'function') {
        sendImportResult(false, 'MODULE_NOT_FOUND');
        return;
      }
      var parser = null;
      try {
        await Promise.all([req.e(6061), req.e(925)]);
        parser = req(8444);
      } catch (e) { parser = null; }
      if (!parser ||
          typeof parser.checkArchiveFormat !== 'function' ||
          typeof parser.importArchive !== 'function') {
        sendImportResult(false, 'MODULE_NOT_FOUND');
        return;
      }

      // 清残留暂存（官方 add() 撞 key 会整包失败）
      await storage.Le();
      try {
        await parser.checkArchiveFormat(file);
        await parser.importArchive(file); // 官方解析 → 暂存区（内部含 mime 修复）
      } catch (e) {
        sendImportResult(false, mapAppendError(e));
        return;
      }

      var staged;
      try {
        staged = await readStaging();
      } catch (e) {
        sendImportResult(false, 'IDB_FAIL');
        return;
      }
      if (!staged.sessions.length) {
        sendImportResult(false, 'STAGE_EMPTY');
        return;
      }

      // 追加提交：暂存 → 现网（官方 E1 upsert，不清库）。文件记录按会话分组。
      var byId = {};
      for (var i = 0; i < staged.files.length; i++) byId[staged.files[i].id] = staged.files[i];
      for (var j = 0; j < staged.sessions.length; j++) {
        var session = staged.sessions[j];
        var need = {};
        var msgs = (session && session.messages) || [];
        for (var m = 0; m < msgs.length; m++) {
          var fs = (msgs[m] && msgs[m].files) || [];
          for (var k = 0; k < fs.length; k++) need[fs[k].id] = true;
        }
        var own = [];
        for (var p = 0; p < staged.files.length; p++) {
          if (need[staged.files[p].id]) own.push(staged.files[p]);
        }
        await storage.E1(session, own);
      }
      await storage.Le();

      sendImportResult(true, 'APPENDED', staged.sessions.length);
      // 刷新页面让会话列表重新读库（结果消息已同步发出，不受刷新影响）
      setTimeout(function () { try { location.reload(); } catch (e) { /* 忽略 */ } }, 400);
    } catch (e) {
      sendImportResult(false, mapAppendError(e));
    } finally {
      appendBusy = false;
    }
  }

  window.DeepSeekAssistant = {
    isNativeShell: true,
    getApiKey: function () { return memKey; },
    pickAndImportZip: function () { return postToNative({ type: 'pickImport' }); }
  };

  /* ---------------- 导出接管：WebView 内 blob 下载落盘 ---------------- */

  function bytesToBase64(bytes) {
    var s = '';
    for (var i = 0; i < bytes.length; i += 0x8000) {
      s += String.fromCharCode.apply(null, bytes.subarray(i, Math.min(i + 0x8000, bytes.length)));
    }
    return btoa(s);
  }

  async function exportBlob(blobUrl, name) {
    var id = Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
    try {
      var res = await fetch(blobUrl);
      var blob = await res.blob();
      if (blob.size > MAX_EXPORT) {
        postToNative({ type: 'exportError', id: id, reason: 'TOO_LARGE' });
        return;
      }
      postToNative({ type: 'exportStart', id: id, name: name || 'deepseek-backup.zip', size: blob.size });
      var buf = new Uint8Array(await blob.arrayBuffer());
      var total = Math.ceil(buf.length / CHUNK);
      for (var i = 0; i < total; i++) {
        var slice = buf.subarray(i * CHUNK, Math.min((i + 1) * CHUNK, buf.length));
        postToNative({ type: 'exportChunk', id: id, index: i, data: bytesToBase64(slice) });
        if (i % 8 === 7) await new Promise(function (r) { setTimeout(r, 0); });
      }
      postToNative({ type: 'exportEnd', id: id });
    } catch (e) {
      postToNative({ type: 'exportError', id: id, reason: 'READ_FAILED' });
    }
  }

  // 上游 ExportHistory 用游离 <a download> + link.click()：拦截原型 click
  var origAnchorClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    var href = this.getAttribute ? (this.getAttribute('href') || '') : '';
    if (this.hasAttribute && this.hasAttribute('download') && href.indexOf('blob:') === 0) {
      exportBlob(href, this.getAttribute('download'));
      return;
    }
    return origAnchorClick.call(this);
  };

  // 同时兜住用户点击真实 <a download> 的场景
  document.addEventListener('click', function (event) {
    var target = event.target;
    var anchor = target && target.closest ? target.closest('a[download]') : null;
    if (!anchor) return;
    var href = anchor.getAttribute('href') || '';
    if (href.indexOf('blob:') !== 0) return;
    event.preventDefault();
    event.stopPropagation();
    exportBlob(href, anchor.getAttribute('download'));
  }, true);

  /* ---------------- 品牌文案替换：未输入 Key 显示 deepseek，有效 Key 显示脱敏 Key + 余额 ---------------- */

  var TARGET_BRAND_TEXT = 'API \u4f7f\u7528 demo';
  var brandState = { title: 'deepseek', subtitle: '' };
  var lastCheckedKey = null;
  var isUpdatingBrand = false;
  var brandObserver = null;

  function maskApiKey(key) {
    if (!key || key.length < 8) return '****';
    return key.slice(0, 3) + '****' + key.slice(-4);
  }

  function formatBalanceInfos(infos) {
    if (!Array.isArray(infos) || infos.length === 0) return '\u4f59\u989d \u00a50.00';
    var parts = [];
    for (var i = 0; i < infos.length; i++) {
      var info = infos[i] || {};
      var currency = info.currency || '';
      var total = info.total_balance != null ? String(info.total_balance) : '0.00';
      if (currency === 'CNY') parts.push('\u00a5' + total);
      else if (currency === 'USD') parts.push('$' + total);
      else if (currency) parts.push(currency + ' ' + total);
      else parts.push(total);
    }
    return '\u4f59\u989d ' + parts.join(' \u00b7 ');
  }

  function findBrandTargets() {
    var targets = [];
    var marked = document.querySelectorAll('[data-ds-brand-target="1"]');
    for (var i = 0; i < marked.length; i++) targets.push(marked[i]);

    if (!document.body || typeof document.createTreeWalker !== 'function') return targets;
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
    var node;
    while ((node = walker.nextNode())) {
      if (!node.nodeValue || node.nodeValue.trim() !== TARGET_BRAND_TEXT) continue;
      var el = node.parentElement;
      if (!el) continue;
      if (el.getAttribute('data-ds-brand-target') !== '1') {
        el.setAttribute('data-ds-brand-target', '1');
        targets.push(el);
      }
    }
    return targets;
  }

  function renderBrand() {
    if (isUpdatingBrand) return;
    var targets = findBrandTargets();
    if (targets.length === 0) return;

    isUpdatingBrand = true;
    try {
      for (var i = 0; i < targets.length; i++) {
        var el = targets[i];
        el.style.display = 'inline-flex';
        el.style.flexDirection = 'column';
        el.style.alignItems = 'flex-start';
        el.style.lineHeight = '1.25';

        var titleEl = el.querySelector('[data-ds-brand-line="title"]');
        var subEl = el.querySelector('[data-ds-brand-line="sub"]');
        if (!titleEl) {
          el.textContent = '';
          titleEl = document.createElement('span');
          titleEl.setAttribute('data-ds-brand-line', 'title');
          el.appendChild(titleEl);
        }
        if (!subEl) {
          subEl = document.createElement('span');
          subEl.setAttribute('data-ds-brand-line', 'sub');
          subEl.style.fontSize = '11px';
          subEl.style.opacity = '0.72';
          subEl.style.fontWeight = 'normal';
          subEl.style.marginTop = '2px';
          el.appendChild(subEl);
        }

        titleEl.textContent = brandState.title;
        if (brandState.subtitle) {
          subEl.textContent = brandState.subtitle;
          subEl.style.display = 'block';
        } else {
          subEl.textContent = '';
          subEl.style.display = 'none';
        }
      }
    } finally {
      isUpdatingBrand = false;
    }
  }

  function syncBrandFromKey() {
    var key = memKey;
    if (!key) {
      lastCheckedKey = null;
      brandState = { title: 'deepseek', subtitle: '' };
      renderBrand();
      return;
    }
    if (key === lastCheckedKey) {
      renderBrand();
      return;
    }
    lastCheckedKey = key;
    brandState = { title: maskApiKey(key), subtitle: '\u4f59\u989d\u67e5\u8be2\u4e2d...' };
    renderBrand();

    fetch('https://api.deepseek.com/user/balance', {
      method: 'GET',
      headers: { 'Authorization': 'Bearer ' + key }
    }).then(function (res) {
      if (key !== memKey) return null;
      if (res.status === 401 || res.status === 403) {
        brandState = { title: 'deepseek', subtitle: '' };
        renderBrand();
        return null;
      }
      if (!res.ok) throw new Error('HTTP_' + res.status);
      return res.json();
    }).then(function (data) {
      if (!data || key !== memKey) return;
      brandState = { title: maskApiKey(key), subtitle: formatBalanceInfos(data.balance_infos) };
      renderBrand();
    }).catch(function () {
      if (key !== memKey) return;
      brandState = { title: maskApiKey(key), subtitle: '\u4f59\u989d\u6682\u4e0d\u53ef\u7528' };
      renderBrand();
    });
  }

  function initBrandObserver() {
    syncBrandFromKey();
    if (!document.body || brandObserver) return;
    brandObserver = new MutationObserver(function () {
      if (!isUpdatingBrand) renderBrand();
    });
    brandObserver.observe(document.body, { childList: true, subtree: true, characterData: true });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initBrandObserver);
  } else {
    initBrandObserver();
  }

  window.__DS_ASSISTANT_BRIDGE__ = true;
})();
