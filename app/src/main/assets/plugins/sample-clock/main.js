// Reference plugin — the smallest useful shape of the kernel contract.
// Rules: register synchronously at load time, keep handlers synchronous, return a string
// or an object { text, image_base64?, error? }.
plugin.tool('plugin_clock', function (args, ctx) {
  var now = new Date();
  var pad = function (n) { return (n < 10 ? '0' : '') + n; };
  var local =
    now.getFullYear() + '-' + pad(now.getMonth() + 1) + '-' + pad(now.getDate()) +
    ' ' + pad(now.getHours()) + ':' + pad(now.getMinutes()) + ':' + pad(now.getSeconds());
  ctx.log('plugin_clock called');
  return {
    text: local + ' (epoch_ms=' + ctx.now() + ')'
  };
});
