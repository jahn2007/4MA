package io.github.jahn2007.fourma;

/** Installs a value-redacting probe at the Mini Program JavaScript/native boundary. */
final class BridgeTraceScript {
    static final String SOURCE = """
            (function () {
              'use strict';
              const MAX = 80;
              const sensitive = /token|cookie|session|secret|password|authorization|signature|phone|mobile/i;
              const root = window;
              if (!root.__fourmaTrace) {
                const trace = {events: [], installed: []};
                root.__fourmaTrace = trace;

                function safeKeys(value) {
                  if (!value || typeof value !== 'object' || Array.isArray(value)) return [];
                  return Object.keys(value).filter(key => !sensitive.test(key)).slice(0, 30).sort();
                }

                function safePath(value) {
                  if (typeof value !== 'string') return '';
                  try {
                    const parsed = new URL(value, location.href);
                    return (parsed.host + parsed.pathname).slice(0, 240);
                  } catch (_) {
                    return value.split('?')[0].replace(/[^0-9A-Za-z_./:-]/g, '').slice(0, 240);
                  }
                }

                function push(kind, name, argument) {
                  const event = {
                    kind: String(kind || '').slice(0, 32),
                    name: String(name || '').replace(/[^0-9A-Za-z_.$:/-]/g, '').slice(0, 120),
                    path: safePath(argument && argument.url),
                    keys: safeKeys(argument),
                    dataKeys: safeKeys(argument && argument.data)
                  };
                  const last = trace.events[trace.events.length - 1];
                  const fingerprint = JSON.stringify(event);
                  if (!last || last.fingerprint !== fingerprint) {
                    event.fingerprint = fingerprint;
                    trace.events.push(event);
                    if (trace.events.length > MAX) trace.events.shift();
                  }
                }

                function wrap(object, method, kind, nameFromArgs) {
                  if (!object || typeof object[method] !== 'function') return false;
                  const original = object[method];
                  if (original.__fourmaWrapped) return true;
                  const wrapped = function () {
                    const args = Array.prototype.slice.call(arguments);
                    let name = method;
                    let argument = args[0];
                    if (nameFromArgs) {
                      name = args[0];
                      argument = args[1];
                    }
                    push(kind, name, argument);
                    return original.apply(this, args);
                  };
                  wrapped.__fourmaWrapped = true;
                  wrapped.__fourmaOriginal = original;
                  try {
                    object[method] = wrapped;
                    return object[method] === wrapped;
                  } catch (_) { return false; }
                }

                if (wrap(root.wx, 'request', 'wx', false)) trace.installed.push('wx.request');
                if (root.wx && root.wx.cloud
                    && wrap(root.wx.cloud, 'callFunction', 'cloud', false)) {
                  trace.installed.push('wx.cloud.callFunction');
                }
                if (wrap(root.WeixinJSBridge, 'invoke', 'bridge', true)) {
                  trace.installed.push('WeixinJSBridge.invoke');
                }
              }

              const trace = root.__fourmaTrace;
              const drained = trace.events.splice(0, 24).map(event => {
                delete event.fingerprint;
                return event;
              });
              return '4MA_TRACE|' + trace.installed.join(',') + '|'
                  + encodeURIComponent(JSON.stringify(drained));
            })();
            """;

    private BridgeTraceScript() {
    }
}
