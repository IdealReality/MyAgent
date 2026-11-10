// 面板脚本：主题值从注入的 CSS 变量读，原生能力从 MyAgentHost 拿。
(function () {
  // 宿主可能没注入（直接在浏览器里打开这份文件时），所有桥调用都走这个判断。
  function bridge() {
    return typeof MyAgentHost === 'undefined' ? null : MyAgentHost;
  }

  // rgba(237, 237, 237, 1.000) → #ededed：原样长度会把一行撑得很丑。
  function compact(value) {
    var text = String(value).trim();
    var m = /^rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)(?:[,/\s]+([\d.]+))?\s*\)$/.exec(text);
    if (!m) return text;
    var hex = '#' + [m[1], m[2], m[3]].map(function (part) {
      var n = Math.max(0, Math.min(255, Math.round(Number(part))));
      return ('0' + n.toString(16)).slice(-2);
    }).join('');
    var alpha = m[4] === undefined ? 1 : Number(m[4]);
    return alpha >= 0.999 ? hex : hex + ' · ' + Math.round(alpha * 100) + '%';
  }

  function fillVars() {
    var root = getComputedStyle(document.documentElement);
    Array.prototype.forEach.call(document.querySelectorAll('.vars li'), function (row) {
      var raw = root.getPropertyValue(row.getAttribute('data-var')).trim();
      row.querySelector('.value').textContent = raw ? compact(raw) : '未注入';
    });
  }

  // 桥返回值按「键 值」两列渲染：textContent 逐段赋值，不拼 HTML 字符串。
  function log(rows) {
    var out = document.getElementById('out');
    out.textContent = '';
    rows.forEach(function (row) {
      var line = document.createElement('div');
      line.className = 'line';
      var key = document.createElement('span');
      key.className = 'key';
      key.textContent = row[0];
      var val = document.createElement('span');
      val.className = 'val';
      val.textContent = row[1];
      line.appendChild(key);
      line.appendChild(val);
      out.appendChild(line);
    });
  }

  function showTheme() {
    var host = bridge();
    if (!host) {
      log([['error', 'bridge unavailable：这页是被插件外部打开的']]);
      return;
    }
    try {
      var theme = JSON.parse(host.theme());
      log([
        ['plugin', host.plugin()],
        ['dark', String(theme.dark)],
        ['primary', compact(theme.primary)],
        ['surface', compact(theme.surface)],
      ]);
    } catch (err) {
      log([['error', 'bridge unavailable: ' + err]]);
    }
    // 桥返回的值和 CSS 变量同源，顺手刷一遍变量表证明两边一致。
    fillVars();
  }

  function on(id, handler) {
    var el = document.getElementById(id);
    if (el) el.addEventListener('click', handler);
  }

  function init() {
    fillVars();
    on('read-theme', showTheme);
    on('toast', function () {
      var host = bridge();
      if (host) host.toast('来自插件面板的提示');
    });
    on('close', function () {
      var host = bridge();
      if (host) host.close();
    });
  }

  document.addEventListener('DOMContentLoaded', init);
  // 脚本被注入/延迟执行时 DOMContentLoaded 可能已经过去，别让样式表一直是「—」。
  if (document.readyState !== 'loading') init();
})();
