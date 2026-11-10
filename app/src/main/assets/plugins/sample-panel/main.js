// Reference plugin for `ui.panel`: a full HTML page rendered in-app (Plugins page → Panel).
// The visible part lives in panel.html + style.css + app.js, so editing those files and calling
// plugin_reload changes the interface — no rebuild, no restart.

plugin.tool('sample_panel_info', function (args, ctx) {
  return {
    text: [
      'sample-panel: reference for plugin-shipped UI.',
      '- manifest: ui.panel = panel.html → Screen.PluginPanel',
      '- assets: style.css / app.js resolve as https://rikkahub.local/plugin/sample-panel/<file>',
      '- bridge (window.MyAgentHost): theme() → JSON, toast(msg), close(), plugin()',
      '- chat slots (composer chips / per-message card) live in the sample-slot plugin',
      '- theme: CSS vars --rk-fg / --rk-muted / --rk-surface / --rk-border / --rk-primary are injected',
      '- the panel follows light/dark automatically; do not hardcode backgrounds',
    ].join('\n'),
  };
});
