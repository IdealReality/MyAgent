// Reference plugin for ctx.app — the "change the app from inside the conversation" path.
// get/set are synchronous and return already-parsed objects; read is gated by app_read,
// write by app_write, and the host only accepts whitelisted display keys.

plugin.tool('ui_get', function (args, ctx) {
  var what = (args && args.what) ? String(args.what) : 'display';
  var data = ctx.app.get(what);
  return { text: JSON.stringify(data, null, 2) };
});

plugin.tool('ui_set', function (args, ctx) {
  var patch = (args && args.patch) ? args.patch : {};
  if (typeof patch !== 'object' || Array.isArray(patch)) {
    return { error: 'patch must be a JSON object of display settings' };
  }
  var result = ctx.app.set('display', patch);
  return {
    text: 'applied: [' + (result.applied || '') + ']\n\ncurrent display:\n' +
      JSON.stringify(result.display, null, 2),
  };
});
