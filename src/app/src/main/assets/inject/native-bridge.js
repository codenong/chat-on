/**
 * DeepSeek 续聊助手 —— 原生桥接垫片（document-start 注入，仅可信 origin）。
 *
 * 页面 → 原生（JSON 字符串经 assistantNativeBridge.postMessage）：
 *   {type:'getKey'}                        原生回 {type:'keyValue', value:string}
 *   {type:'saveKey', key}                  页面写 Key（上游设置界面）→ 原生加密保存
 *   {type:'deleteKey'}                     页面删 Key → 原生删除
 *   {type:'pickImport'}                    请求打开系统文件选择器（SAF）
 *   {type:'importResult', ok, code}        投递结果反馈
 *   {type:'exportStart', id, name, size}   导出落盘开始（备份）
 *   {type:'exportChunk', id, index, data}  导出分块（base64）
 *   {type:'exportEnd', id}                 导出结束
 *   {type:'exportError', id, reason}       导出失败
 *
 * 原生 → 页面（assistantNativeBridge.onmessage）：
 *   {type:'keyValue', value}               Key 变更（value 为空串表示已删除）
 *   {type:'deliverImport', url, name}      投递所选 ZIP 给官方导入器
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
      return undefined;
    }
    return origSet.call(this, k, v);
  };

  Storage.prototype.removeItem = function (k) {
    if (isLocalStorage(this) && isTrackedKey(k)) {
      memKey = null;
      postToNative({ type: 'deleteKey' });
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
        break;
      case 'deliverImport':
        deliverImport(msg.url, msg.name);
        break;
      default:
        break;
    }
  }

  function bindNativeListener() {
    native = window.assistantNativeBridge || native;
    if (native) {
      try { native.onmessage = onNativeMessage; } catch (e) { /* 忽略 */ }
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

  /* ---------------- 导入投递：交给官方导入器 ---------------- */

  function sendImportResult(ok, code) {
    postToNative({ type: 'importResult', ok: !!ok, code: code || '' });
  }

  async function deliverImport(url, name) {
    try {
      var res = await fetch(url, { credentials: 'omit' });
      if (!res.ok) {
        sendImportResult(false, 'FETCH_HTTP_' + res.status);
        return;
      }
      // 流式读取原生挂载的 ZIP；Chromium 会把大 blob 落盘，不占满内存
      var blob = await res.blob();
      var file = new File([blob], name || 'deepseek_data.zip', { type: 'application/zip' });
      var input = null;
      for (var i = 0; i < IMPORT_SELECTORS.length; i++) {
        input = document.querySelector(IMPORT_SELECTORS[i]);
        if (input) break;
      }
      if (!input) {
        sendImportResult(false, 'NO_FILE_INPUT');
        return;
      }
      var dt = new DataTransfer();
      dt.items.add(file);
      input.files = dt.files;
      input.dispatchEvent(new Event('change', { bubbles: true }));
      // 官方导入器随后自行弹出覆盖确认并显示结果（上游自身交互）
      sendImportResult(true, '');
    } catch (e) {
      sendImportResult(false, 'FETCH_FAILED');
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

  window.__DS_ASSISTANT_BRIDGE__ = true;
})();
