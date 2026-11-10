// TRPG Runner — deterministic tabletop runtime for MyAgent.
//
// Contract notes (see PluginJsRuntime): every invocation gets a fresh QuickJS context, so
// nothing may live in JS globals between calls. All state is persisted through ctx.kv as
// JSON strings. Handlers are synchronous and must return a string or { text }.
//
// Why the PRNG is hand-rolled: Math.random() alone is reseeded per context, so two calls in
// the same millisecond can produce correlated streams. We mix in a persisted counter, which
// also makes every roll auditable ("how many rolls happened in this session").

var CTX = null;

// ---------------------------------------------------------------- storage helpers

function kvGet(key, dflt) {
  var raw = CTX.kv.get(key);
  if (raw === null || raw === undefined || raw === '') return dflt;
  try {
    return JSON.parse(raw);
  } catch (e) {
    return dflt;
  }
}

function kvPut(key, value) {
  CTX.kv.set(key, JSON.stringify(value));
}

function kvText(key, dflt) {
  var raw = CTX.kv.get(key);
  return raw === null || raw === undefined || raw === '' ? dflt : raw;
}

// ---------------------------------------------------------------- PRNG (sfc32)

var S0 = 0, S1 = 0, S2 = 0, S3 = 0;

function seedPrng() {
  var counter = parseInt(kvText('rng.counter', '0'), 10);
  if (!isFinite(counter)) counter = 0;
  counter = (counter + 1) % 2147483647;
  CTX.kv.set('rng.counter', String(counter));

  var t = CTX.now() >>> 0;
  var r1 = (Math.random() * 0x100000000) >>> 0;
  var r2 = (Math.random() * 0x100000000) >>> 0;
  var r3 = (Math.random() * 0x100000000) >>> 0;

  S0 = mix32(t ^ counter) >>> 0;
  S1 = mix32(r1 ^ (t * 31 + 1)) >>> 0;
  S2 = mix32(r2 ^ counter) >>> 0;
  S3 = mix32(r3 ^ 0x9e3779b9) >>> 0;
  // Discard the first outputs: sfc32 needs a few rounds before its state is well mixed.
  for (var i = 0; i < 12; i++) sfc32();
}

function mix32(x) {
  x = x >>> 0;
  x ^= x >>> 16;
  x = Math.imul(x, 0x7feb352d) >>> 0;
  x ^= x >>> 15;
  x = Math.imul(x, 0x846ca68b) >>> 0;
  x ^= x >>> 16;
  return x >>> 0;
}

function sfc32() {
  var a = S0, b = S1, c = S2, d = S3;
  var t = (a + b) >>> 0;
  a = (b ^ (b >>> 9)) >>> 0;
  b = (c + (c << 3)) >>> 0;
  c = ((c << 21) | (c >>> 11)) >>> 0;
  d = (d + 1) >>> 0;
  var r = (t + d) >>> 0;
  S0 = a; S1 = b; S2 = c; S3 = d;
  return r;
}

/** Uniform integer in [0, n) with rejection sampling — no modulo bias. */
function uniform(n) {
  if (!(n > 1)) return 0;
  var span = 4294967296;
  var limit = span - (span % n);
  var x = sfc32();
  while (x >= limit) x = sfc32();
  return x % n;
}

function rollDie(sides) {
  return uniform(sides) + 1;
}

// ---------------------------------------------------------------- dice expressions

/** Parses `NdM`, `NdM+K`, `NdM-K`, `NdMkhX`, `NdMklX`. Bare `dM` means 1 die. */
function parseDiceExpr(expr) {
  var s = String(expr || '').replace(/\s+/g, '').toLowerCase().replace(/d%/, 'd100');
  var m = /^(\d*)d(\d+)(?:([+-])(\d+))?(?:(kh|kl)(\d+))?$/.exec(s);
  if (!m) return null;
  var count = m[1] === '' ? 1 : parseInt(m[1], 10);
  var sides = parseInt(m[2], 10);
  if (!(count >= 1) || !(sides >= 2) || count > 100 || sides > 1000) return null;
  var mod = m[4] ? parseInt(m[4], 10) * (m[3] === '-' ? -1 : 1) : 0;
  return {
    count: count,
    sides: sides,
    mod: mod,
    keep: m[6] ? Math.min(parseInt(m[6], 10), count) : 0,
    keepHigh: m[5] !== 'kl',
  };
}

function rollParsed(spec) {
  var rolls = [];
  for (var i = 0; i < spec.count; i++) rolls.push(rollDie(spec.sides));
  var kept = rolls;
  if (spec.keep > 0 && spec.keep < rolls.length) {
    kept = rolls.slice().sort(function (a, b) {
      return spec.keepHigh ? b - a : a - b;
    }).slice(0, spec.keep);
  }
  var sum = kept.reduce(function (a, b) { return a + b; }, 0);
  return {
    rolls: rolls,
    kept: kept,
    subtotal: sum,
    total: sum + spec.mod,
  };
}

// ---------------------------------------------------------------- CoC 7e

var COC_LEVELS = {
  critical: '大成功',
  extreme: '极难成功',
  hard: '困难成功',
  regular: '普通成功',
  fail: '失败',
  fumble: '大失败',
};

function cocLevel(roll, target) {
  var fumbleAt = target < 50 ? 96 : 100;
  if (roll >= fumbleAt) return 'fumble';
  if (roll === 1) return 'critical';
  if (roll <= Math.floor(target / 5)) return 'extreme';
  if (roll <= Math.floor(target / 2)) return 'hard';
  if (roll <= target) return 'regular';
  return 'fail';
}

function rollCoc(target, bonus) {
  var units = uniform(10);
  var tens = [uniform(10)];
  var extra = Math.min(Math.abs(bonus | 0), 4);
  for (var i = 0; i < extra; i++) tens.push(uniform(10));

  var values = tens.map(function (t) {
    var v = t * 10 + units;
    return v === 0 ? 100 : v;
  });
  var pick = bonus >= 0 ? Math.min.apply(null, values) : Math.max.apply(null, values);
  var level = cocLevel(pick, target);
  var thresholds = '极难≤' + Math.floor(target / 5) +
    ' 困难≤' + Math.floor(target / 2) +
    ' 普通≤' + target +
    ' 大失败=' + (target < 50 ? '96-100' : '100');

  var lines = [];
  if (extra > 0) {
    lines.push((bonus > 0 ? '奖励骰 +' : '惩罚骰 ') + extra +
      '：十位候选 [' + tens.join(', ') + '] 个位 [' + units + '] → [' + values.join(', ') + ']');
    lines.push('取值 ' + pick + '（' + (bonus > 0 ? '取最有利' : '取最不利') + '）');
  } else {
    lines.push('十位 [' + tens[0] + '] 个位 [' + units + '] → ' + pick);
  }
  lines.push('结果：' + COC_LEVELS[level] + '（' + thresholds + '）');
  return { roll: pick, level: level, text: lines.join('\n') };
}

// ---------------------------------------------------------------- tool: dice

function diceTool(args, ctx) {
  CTX = ctx;
  seedPrng();

  var system = String(args.system || '').toLowerCase();
  var label = args.label ? String(args.label) : '';
  if (!system) {
    if (args.target !== undefined && args.target !== null) system = 'coc';
    else if (args.dc !== undefined && args.dc !== null) system = 'dnd';
    else system = 'generic';
  }

  var head = '[RNG:trpg_dice]';
  var out = [];

  if (system === 'coc') {
    var target = Number(args.target);
    if (!isFinite(target) || target <= 0) {
      return { error: 'coc 判定需要 target（技能/属性值，正整数）' };
    }
    var bonus = Number(args.bonus || 0);
    if (!isFinite(bonus)) bonus = 0;
    var coc = rollCoc(Math.min(Math.round(target), 999), bonus);
    out.push(head + ' CoC 7e 检定' + (label ? ' · ' + label : '') + ' · 目标 ' + target);
    out.push(coc.text);
    appendRollLog({ system: 'coc7e', label: label, target: target, roll: coc.roll, level: coc.level });
    out.push('来源：trpg_dice（点数已确定，叙事必须与之一致，禁止改写）');
    return out.join('\n');
  }

  if (system === 'dnd' || (system === 'generic' && args.dc !== undefined && args.dc !== null)) {
    var mod = Number(args.mod || 0);
    if (!isFinite(mod)) mod = 0;
    var mode = String(args.mode || 'none').toLowerCase();
    var dc = args.dc === undefined || args.dc === null ? null : Number(args.dc);
    var first = rollDie(20);
    var rolls = [first];
    if (mode === 'adv' || mode === 'dis') {
      var second = rollDie(20);
      rolls.push(second);
      first = mode === 'adv' ? Math.max(first, second) : Math.min(first, second);
    }
    var total = first + Math.round(mod);
    var verdict = '';
    if (dc !== null && isFinite(dc)) {
      if (first === 20) verdict = '暴击成功（自然 20）';
      else if (first === 1) verdict = '暴击失败（自然 1）';
      else verdict = total >= dc ? '成功' : '失败';
    }
    out.push(head + ' DnD 5e 检定' + (label ? ' · ' + label : '') +
      (dc !== null && isFinite(dc) ? ' · DC ' + dc : '') +
      ' · 修正 ' + (mod >= 0 ? '+' : '') + Math.round(mod) +
      (mode === 'adv' ? ' · 优势' : mode === 'dis' ? ' · 劣势' : ''));
    out.push('d20 [' + rolls.join(', ') + '] 取 ' + first +
      ' → ' + total + (verdict ? ' → ' + verdict : ''));
    appendRollLog({
      system: 'dnd5e', label: label, dc: dc, roll: first, total: total, verdict: verdict,
    });
    out.push('来源：trpg_dice（点数已确定，叙事必须与之一致，禁止改写）');
    return out.join('\n');
  }

  // generic
  var expr = String(args.expr || '1d100');
  var repeats = 1;
  var hash = expr.indexOf('#');
  if (hash > 0) {
    repeats = parseInt(expr.slice(0, hash), 10);
    expr = expr.slice(hash + 1);
    if (!(repeats >= 1) || repeats > 20) return { error: '重复次数需在 1..20' };
  }
  var spec = parseDiceExpr(expr);
  if (!spec) return { error: "无法解析骰子表达式 '" + expr + "'（支持 NdM、NdM+K、NdMkhX、NdMklX）" };

  if (repeats > 1) {
    var groups = [];
    var grand = 0;
    for (var i = 0; i < repeats; i++) {
      var g = rollParsed(spec);
      groups.push(g.total);
      grand += g.total;
    }
    out.push(head + ' ' + expr + ' ×' + repeats + (label ? ' · ' + label : ''));
    out.push('结果 [' + groups.join(', ') + '] 合计 ' + grand);
    appendRollLog({ system: 'generic', label: label, expr: expr, results: groups, total: grand });
    out.push('来源：trpg_dice（点数已确定，叙事必须与之一致，禁止改写）');
    return out.join('\n');
  }

  var r = rollParsed(spec);
  out.push(head + ' ' + expr + (label ? ' · ' + label : ''));
  var detail = '骰 [' + r.rolls.join(', ') + ']';
  if (r.kept.length !== r.rolls.length) detail += ' 取 [' + r.kept.join(', ') + ']';
  detail += ' = ' + r.subtotal + (spec.mod !== 0 ? (spec.mod > 0 ? ' +' : ' -') + Math.abs(spec.mod) + ' = ' + r.total : '');
  out.push(detail);
  appendRollLog({ system: 'generic', label: label, expr: expr, rolls: r.rolls, total: r.total });
  out.push('来源：trpg_dice（点数已确定，叙事必须与之一致，禁止改写）');
  return out.join('\n');
}

function appendRollLog(entry) {
  var log = kvGet('trpg.rolls', []);
  if (!Array.isArray(log)) log = [];
  entry.at = CTX.now();
  log.push(entry);
  if (log.length > 40) log = log.slice(log.length - 40);
  kvPut('trpg.rolls', log);
}

// ---------------------------------------------------------------- sheets

var DEFAULT_SHEET = {
  system: 'coc7e',
  hp: { cur: 10, max: 10 },
  mp: { cur: 10, max: 10 },
  san: { cur: 50, max: 50 },
  luck: 50,
  attrs: {},
  skills: {},
  items: [],
  status: [],
  notes: '',
};

function clone(v) {
  return JSON.parse(JSON.stringify(v));
}

function deepMerge(target, patch) {
  Object.keys(patch).forEach(function (k) {
    var v = patch[k];
    if (v && typeof v === 'object' && !Array.isArray(v) &&
      target[k] && typeof target[k] === 'object' && !Array.isArray(target[k])) {
      deepMerge(target[k], v);
    } else {
      target[k] = v;
    }
  });
  return target;
}

function getPath(obj, path) {
  var parts = path.split('.');
  var cur = obj;
  for (var i = 0; i < parts.length; i++) {
    if (cur === null || cur === undefined || typeof cur !== 'object') return undefined;
    cur = cur[parts[i]];
  }
  return cur;
}

function setPath(obj, path, value) {
  var parts = path.split('.');
  var cur = obj;
  for (var i = 0; i < parts.length - 1; i++) {
    if (cur[parts[i]] === null || typeof cur[parts[i]] !== 'object') cur[parts[i]] = {};
    cur = cur[parts[i]];
  }
  cur[parts[parts.length - 1]] = value;
}

/**
 * Applies numeric deltas and clamps "cur" to [0, max] when both exist.
 * `hp` as a bare key is shorthand for `hp.cur`.
 */
function applyDelta(sheet, delta) {
  var changes = [];
  Object.keys(delta).forEach(function (key) {
    var amount = Number(delta[key]);
    if (!isFinite(amount)) return;
    var path = key.indexOf('.') === -1 && getPath(sheet, key) &&
      typeof getPath(sheet, key) === 'object' ? key + '.cur' : key;
    var current = getPath(sheet, path);
    if (typeof current !== 'number') current = 0;
    var next = current + amount;
    var maxPath = path.replace(/\.cur$/, '.max');
    var max = getPath(sheet, maxPath);
    if (typeof max === 'number' && !isNaN(max)) next = Math.min(next, max);
    if (path.indexOf('.max') === -1) next = Math.max(next, 0);
    setPath(sheet, path, next);
    changes.push(path + ' ' + (amount >= 0 ? '+' : '') + amount + ' → ' + next +
      (typeof max === 'number' ? '/' + max : ''));
  });
  return changes;
}

function renderSheet(name, sheet) {
  var lines = ['[角色卡] ' + name + ' (' + (sheet.system || 'custom') + ')'];
  var bars = [];
  ['hp', 'mp', 'san'].forEach(function (k) {
    var v = sheet[k];
    if (v && typeof v === 'object') bars.push(k.toUpperCase() + ' ' + v.cur + '/' + v.max);
    else if (typeof v === 'number') bars.push(k.toUpperCase() + ' ' + v);
  });
  if (typeof sheet.luck === 'number') bars.push('LUCK ' + sheet.luck);
  if (bars.length) lines.push('  ' + bars.join('   '));

  var attrs = sheet.attrs || {};
  var attrKeys = Object.keys(attrs);
  if (attrKeys.length) {
    lines.push('  属性: ' + attrKeys.map(function (k) { return k + ' ' + attrs[k]; }).join(', '));
  }
  var skills = sheet.skills || {};
  var skillKeys = Object.keys(skills);
  if (skillKeys.length) {
    lines.push('  技能: ' + skillKeys.map(function (k) { return k + ' ' + skills[k]; }).join(', '));
  }
  if (Array.isArray(sheet.items) && sheet.items.length) {
    lines.push('  物品: ' + sheet.items.join(', '));
  }
  if (Array.isArray(sheet.status) && sheet.status.length) {
    lines.push('  状态: ' + sheet.status.join(', '));
  }
  if (sheet.notes) lines.push('  备注: ' + sheet.notes);
  return lines.join('\n');
}

function sheetTool(args, ctx) {
  CTX = ctx;
  var action = String(args.action || 'show').toLowerCase();
  var sheets = kvGet('trpg.sheets', {});
  if (!sheets || typeof sheets !== 'object') sheets = {};
  var name = args.name ? String(args.name) : '';

  if (action === 'show') {
    var names = Object.keys(sheets);
    if (!names.length) return '[角色卡] 尚未创建任何角色。用 action=new 创建。';
    if (!name) {
      return '[角色卡] 共 ' + names.length + ' 个：' + names.join('、') +
        '\n用 action=show name=<名字> 查看详情。';
    }
    if (!sheets[name]) return { error: "没有名为 '" + name + "' 的角色卡；现有：" + names.join('、') };
    return renderSheet(name, sheets[name]);
  }

  if (action === 'new') {
    if (!name) return { error: 'action=new 需要 name' };
    if (sheets[name]) return { error: "角色 '" + name + "' 已存在；用 action=update 修改" };
    var fresh = clone(DEFAULT_SHEET);
    if (args.system) fresh.system = String(args.system);
    if (args.patch) fresh = deepMerge(fresh, JSON.parse(String(args.patch)));
    sheets[name] = fresh;
    kvPut('trpg.sheets', sheets);
    return '已创建 ' + renderSheet(name, fresh);
  }

  if (action === 'update') {
    if (!name) return { error: 'action=update 需要 name' };
    if (!sheets[name]) return { error: "没有名为 '" + name + "' 的角色卡" };
    var sheet = sheets[name];
    var changes = [];
    if (args.patch) {
      var patch;
      try {
        patch = JSON.parse(String(args.patch));
      } catch (e) {
        return { error: 'patch 不是合法 JSON：' + e.message };
      }
      deepMerge(sheet, patch);
      changes.push('patch 已合并');
    }
    if (args.delta) {
      var delta;
      try {
        delta = JSON.parse(String(args.delta));
      } catch (e) {
        return { error: 'delta 不是合法 JSON：' + e.message };
      }
      changes = changes.concat(applyDelta(sheet, delta));
    }
    sheets[name] = sheet;
    kvPut('trpg.sheets', sheets);
    return '已更新（' + (changes.join('；') || '无变化') + '）\n' + renderSheet(name, sheet);
  }

  if (action === 'delete') {
    if (!name) return { error: 'action=delete 需要 name' };
    if (!sheets[name]) return { error: "没有名为 '" + name + "' 的角色卡" };
    delete sheets[name];
    kvPut('trpg.sheets', sheets);
    return '已删除角色 ' + name;
  }

  return { error: "未知 action '" + action + "'（可用 show/new/update/delete）" };
}

// ---------------------------------------------------------------- scene

var DEFAULT_SCENE = {
  campaign: '',
  act: 0,
  location: '',
  time: '',
  npcs: [],
  facts: [],
  clues: [],
  clocks: {},
};

function sceneTool(args, ctx) {
  CTX = ctx;
  var action = String(args.action || 'status').toLowerCase();
  var scene = kvGet('trpg.scene', null);
  if (!scene || typeof scene !== 'object') scene = clone(DEFAULT_SCENE);

  if (action === 'status') {
    return renderScene(scene);
  }

  if (action === 'begin') {
    scene = clone(DEFAULT_SCENE);
    if (args.patch) {
      try {
        scene = deepMerge(scene, JSON.parse(String(args.patch)));
      } catch (e) {
        return { error: 'patch 不是合法 JSON：' + e.message };
      }
    }
    scene.startedAt = CTX.now();
    scene.updatedAt = CTX.now();
    kvPut('trpg.scene', scene);
    return '新场景已开始。\n' + renderScene(scene);
  }

  if (action === 'set') {
    if (!args.patch) return { error: 'action=set 需要 patch' };
    try {
      deepMerge(scene, JSON.parse(String(args.patch)));
    } catch (e) {
      return { error: 'patch 不是合法 JSON：' + e.message };
    }
    scene.updatedAt = CTX.now();
    kvPut('trpg.scene', scene);
    return '场景已更新。\n' + renderScene(scene);
  }

  if (action === 'fact' || action === 'clue' || action === 'npc') {
    var text = args.text ? String(args.text) : '';
    if (!text) return { error: 'action=' + action + ' 需要 text' };
    var listKey = action === 'fact' ? 'facts' : action === 'clue' ? 'clues' : 'npcs';
    if (!Array.isArray(scene[listKey])) scene[listKey] = [];
    scene[listKey].push(action === 'fact' ? { n: scene.facts.length + 1, text: text } : text);
    if (listKey === 'facts' && scene.facts.length > 200) scene.facts = scene.facts.slice(-200);
    scene.updatedAt = CTX.now();
    kvPut('trpg.scene', scene);
    var label = action === 'fact' ? '事实' : action === 'clue' ? '线索' : 'NPC';
    return '已记录' + label + '：' + text;
  }

  if (action === 'clock') {
    var clockName = args.name ? String(args.name) : '';
    if (!clockName) return { error: 'action=clock 需要 name' };
    var clocks = scene.clocks || {};
    var clock = clocks[clockName] || { value: 0, max: Number(args.max) || 6 };
    if (args.max !== undefined && args.max !== null) clock.max = Number(args.max);
    if (args.value !== undefined && args.value !== null) {
      clock.value = Math.max(0, Math.min(Number(args.value), clock.max));
    } else {
      clock.value = Math.min(clock.value + 1, clock.max);
    }
    clocks[clockName] = clock;
    scene.clocks = clocks;
    scene.updatedAt = CTX.now();
    kvPut('trpg.scene', scene);
    return '钟「' + clockName + '」= ' + clock.value + '/' + clock.max +
      (clock.value >= clock.max ? '（已满！）' : '');
  }

  if (action === 'reset') {
    kvPut('trpg.scene', clone(DEFAULT_SCENE));
    return '场景状态已清空（角色卡与存档保留）。';
  }

  return { error: "未知 action '" + action + "'（可用 status/begin/set/fact/clue/npc/clock/reset）" };
}

function renderScene(scene) {
  var lines = ['[场景]'];
  var head = [];
  if (scene.campaign) head.push(scene.campaign);
  if (scene.act) head.push('第 ' + scene.act + ' 幕');
  if (scene.location) head.push('@' + scene.location);
  if (scene.time) head.push('🕒 ' + scene.time);
  lines.push('  ' + (head.length ? head.join(' · ') : '（未开始，用 action=begin 开团）'));

  if (Array.isArray(scene.npcs) && scene.npcs.length) {
    lines.push('  在场 NPC: ' + scene.npcs.join('、'));
  }
  var clocks = scene.clocks || {};
  var clockKeys = Object.keys(clocks);
  if (clockKeys.length) {
    lines.push('  钟: ' + clockKeys.map(function (k) {
      return k + ' ' + clocks[k].value + '/' + clocks[k].max;
    }).join('  '));
  }
  if (Array.isArray(scene.clues) && scene.clues.length) {
    lines.push('  线索(' + scene.clues.length + '): ' + scene.clues.slice(-8).join('；'));
  }
  if (Array.isArray(scene.facts) && scene.facts.length) {
    var recent = scene.facts.slice(-10);
    lines.push('  最近事实:');
    recent.forEach(function (f) {
      lines.push('    ' + (f.n ? f.n + '. ' : '') + (f.text || f));
    });
    if (scene.facts.length > recent.length) {
      lines.push('    （共 ' + scene.facts.length + ' 条，仅显示最近 ' + recent.length + ' 条）');
    }
  }
  return lines.join('\n');
}

// ---------------------------------------------------------------- saves

function saveTool(args, ctx) {
  CTX = ctx;
  var action = String(args.action || 'list').toLowerCase();
  var saves = kvGet('trpg.saves', {});
  if (!saves || typeof saves !== 'object') saves = {};

  if (action === 'list') {
    var names = Object.keys(saves);
    if (!names.length) return '[存档] 暂无存档。';
    return '[存档] 共 ' + names.length + ' 个：\n' + names.map(function (n) {
      var s = saves[n];
      var when = new Date(s.savedAt).toISOString().replace('T', ' ').slice(0, 16);
      var place = [s.scene && s.scene.campaign, s.scene && s.scene.act ? '第' + s.scene.act + '幕' : '',
        s.scene && s.scene.location].filter(Boolean).join(' ');
      return '  ' + n + ' — ' + when + (place ? ' · ' + place : '') + (s.note ? ' — ' + s.note : '');
    }).join('\n');
  }

  var name = args.name ? String(args.name) : '';
  if (!name) return { error: 'action=' + action + ' 需要 name' };

  if (action === 'save') {
    saves[name] = {
      savedAt: CTX.now(),
      note: args.note ? String(args.note) : '',
      scene: kvGet('trpg.scene', clone(DEFAULT_SCENE)),
      sheets: kvGet('trpg.sheets', {}),
    };
    kvPut('trpg.saves', saves);
    return '已存档「' + name + '」（含 ' + Object.keys(saves[name].sheets || {}).length + ' 个角色卡）';
  }

  if (action === 'load') {
    var slot = saves[name];
    if (!slot) return { error: '没有名为「' + name + '」的存档；现有：' + Object.keys(saves).join('、') };
    kvPut('trpg.scene', slot.scene || clone(DEFAULT_SCENE));
    kvPut('trpg.sheets', slot.sheets || {});
    return '已读档「' + name + '」。\n' + renderScene(slot.scene || clone(DEFAULT_SCENE)) +
      '\n' + Object.keys(slot.sheets || {}).map(function (k) {
        return renderSheet(k, slot.sheets[k]);
      }).join('\n');
  }

  if (action === 'delete') {
    if (!saves[name]) return { error: '没有名为「' + name + '」的存档' };
    delete saves[name];
    kvPut('trpg.saves', saves);
    return '已删除存档「' + name + '」';
  }

  return { error: "未知 action '" + action + "'（可用 save/load/list/delete）" };
}

// ---------------------------------------------------------------- registration

plugin.tool('trpg_dice', diceTool);
plugin.tool('trpg_sheet', sheetTool);
plugin.tool('trpg_scene', sceneTool);
plugin.tool('trpg_save', saveTool);
