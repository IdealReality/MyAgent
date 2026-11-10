// Reference plugin for `ui.slots` — the two places a plugin can put UI inside a chat:
//   composer chips  → a button row above the input box (manifest ui.slots[].type = "chips")
//   message card    → card.html rendered under the newest message (type = "html")
// Both are declared in manifest.json and driven by files in this directory: edit, then
// plugin_reload. No rebuild, no restart.

// A chip whose item declares {"action":"ping"} calls this with the current chat context:
// {conversation_id, draft} for composer slots, {conversation_id, message_id, role, text}
// for message slots. The return value is applied by the host.
plugin.action('ping', function (args, ctx) {
  var where = args.message_id ? '消息 ' + String(args.message_id).slice(0, 8) : '输入框';
  var draft = args.draft || args.text || '';
  return {
    toast: 'sample-slot: ' + where,
    // 输入框已经有草稿时不要覆盖它。
    insert: draft ? '' : '（slot 回执）',
  };
});
