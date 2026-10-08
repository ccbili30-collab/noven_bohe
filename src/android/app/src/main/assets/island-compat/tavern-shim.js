/* tavern-compat：导入的酒馆前端资产按酒馆惯例读取宿主全局——这里把
 * 那些读取映射到 NOVEN_FRAME（沙箱内唯一数据桥）。只提供读取面；
 * 酒馆脚本对宿主的写操作一律落成空操作，不穿透沙箱。 */
(function () {
  // NOVEN_FRAME.state 是扁平点键表（MVU 补丁摊平落库的结果）；MVU
  // 脚本按嵌套对象访问（getVariables().角色.好感度）——这里重建嵌套
  // 结构，纯数字段按数组索引落位。
  var unflatten = function (flat) {
    var root = {};
    Object.keys(flat).forEach(function (k) {
      var segs = String(k).split('.');
      var node = root;
      for (var i = 0; i < segs.length - 1; i++) {
        var s = segs[i];
        var nextArr = /^\d+$/.test(segs[i + 1]);
        if (node[s] == null || typeof node[s] !== 'object' || (nextArr && !Array.isArray(node[s]))) {
          node[s] = nextArr ? [] : {};
        }
        node = node[s];
      }
      node[segs[segs.length - 1]] = flat[k];
    });
    return root;
  };
  // 岛本地写面：酒馆脚本对变量/消息的写不落宿主（沙箱只读），但同一渲染
  // 期内写后读必须自洽——overlay 覆盖在宿主状态上。
  var overlay = {};
  var merge = function (base, over) {
    var out = {};
    Object.keys(base).forEach(function (k) { out[k] = base[k]; });
    Object.keys(over).forEach(function (k) {
      var v = over[k];
      out[k] = (v && typeof v === 'object' && !Array.isArray(v) &&
                out[k] && typeof out[k] === 'object' && !Array.isArray(out[k]))
        ? merge(out[k], v) : v;
    });
    return out;
  };
  var state = function () {
    return merge(unflatten((window.NOVEN_FRAME && window.NOVEN_FRAME.state) || {}), overlay);
  };
  var getPath = function (root, path) {
    var cur = root;
    var segs = String(path).split('.');
    for (var i = 0; i < segs.length; i++) {
      if (cur == null || typeof cur !== 'object') return undefined;
      cur = cur[segs[i]];
    }
    return cur;
  };
  var setPath = function (root, path, v) {
    var segs = String(path).split('.');
    var node = root;
    for (var i = 0; i < segs.length - 1; i++) {
      if (node[segs[i]] == null || typeof node[segs[i]] !== 'object') node[segs[i]] = {};
      node = node[segs[i]];
    }
    node[segs[segs.length - 1]] = v;
  };
  // MVU/TavernHelper 系脚本的变量入口：参数（'global'/'chat'/范围名）
  // 在本协议里不区分——状态扁平表是唯一数据源。
  if (!window.getVariables) window.getVariables = function () { return state(); };
  if (!window.getAllVariables) window.getAllVariables = function () { return state(); };
  if (!window.getvar) window.getvar = function (path) { return getPath(state(), path); };
  if (!window.setvar) window.setvar = function (path, v) { setPath(overlay, path, v); return v; };
  if (!window.deleteVariable) window.deleteVariable = function (path) { setPath(overlay, path, undefined); };
  if (!window.insertOrAssignVariables) window.insertOrAssignVariables = function (obj) {
    overlay = merge(overlay, obj || {}); return Promise.resolve();
  };
  if (!window.formatAsTavernRegexedString) {
    // 酒馆把消息过一遍正则脚本再显示——本运行时正则已在宿主侧转译成
    // 模块，岛里拿到即最终文本，恒等返回。
    window.formatAsTavernRegexedString = function (s) { return s; };
  }
  if (!window.getCurrentMessageId) {
    window.getCurrentMessageId = function () {
      var ms = (window.NOVEN_FRAME && window.NOVEN_FRAME.messages) || [];
      return ms.length - 1;
    };
  }
  if (!window.getChatMessages) {
    // 酒馆语义：负数从尾部数（-1=最近一条），非负为楼层号。
    window.getChatMessages = function (idx) {
      var ms = (window.NOVEN_FRAME && window.NOVEN_FRAME.messages) || [];
      var i = idx < 0 ? ms.length + idx : idx;
      var m = ms[i];
      if (!m) return [];
      return [{ role: m.role, name: m.name, message: m.text, message_id: i, swipes: [m.text] }];
    };
  }
  if (!window.setChatMessage) window.setChatMessage = function () { return Promise.resolve(); };
  if (!window.waitGlobalInitialized) {
    window.waitGlobalInitialized = function (name) { return Promise.resolve(window[name]); };
  }
  // eventOn/eventOff：MVU 事件名注册；VARIABLE_UPDATE_ENDED 语义映射到
  // noven:frame（宿主每次状态热更新派发），回调拿到新的变量表。
  var listeners = {};
  window.Mvu = window.Mvu || {};
  if (!window.Mvu.events) {
    window.Mvu.events = {
      VARIABLE_UPDATE_ENDED: 'mvu_variable_update_ended',
      VARIABLE_UPDATE_STARTED: 'mvu_variable_update_started',
      SINGLE_VARIABLE_UPDATED: 'mvu_single_variable_updated',
      COMMAND_PARSED: 'mvu_command_parsed',
    };
  }
  if (!window.eventOn) window.eventOn = function (ev, cb) {
    (listeners[ev] = listeners[ev] || []).push(cb);
  };
  if (!window.eventOff) window.eventOff = function (ev, cb) {
    var l = listeners[ev] || []; var i = l.indexOf(cb); if (i >= 0) l.splice(i, 1);
  };
  window.addEventListener('noven:frame', function () {
    var l = listeners[window.Mvu.events.VARIABLE_UPDATE_ENDED] || [];
    var vars = state();
    l.forEach(function (cb) { try { cb(vars); } catch (e) { console.error('noven:frame listener', e); } });
  });
  if (!window.Mvu.getVariables) window.Mvu.getVariables = function () { return state(); };
  if (!window.Mvu.getMvuData) window.Mvu.getMvuData = function () { return state(); };
  if (!window.Mvu.getUserAvatar) window.Mvu.getUserAvatar = function () { return ''; };
  if (!window.Mvu.updateVariablesWith) window.Mvu.updateVariablesWith = function () {};
  window.TavernHelper = window.TavernHelper || {};
  if (!window.TavernHelper.getVariables) window.TavernHelper.getVariables = function () { return state(); };
  window.TH = window.TH || window.TavernHelper;
  window.SillyTavern = window.SillyTavern || {};
  window.SillyTavern.getContext = window.SillyTavern.getContext || function () {
    return { chat: (window.NOVEN_FRAME && window.NOVEN_FRAME.messages) || [] };
  };
  // 酒馆前端常经 window.parent 摸宿主全局（音乐播放器、提示框）——
  // 岛里 parent 就是自身，预置空对象让链式访问安全落空而非抛错。
  window.__tavernStub = window.__tavernStub || {};
})();
