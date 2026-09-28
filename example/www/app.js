/*
 * Example app for @bricks-soft/capacitor-location-tracking.
 *
 * Plain browser JavaScript, no bundler. It uses two globals:
 *   - `Capacitor` from vendor/capacitor.js;
 *   - `capacitorLocationTracking.LocationTracking` from vendor/plugin.js.
 * Both files are copied into www/vendor/ by `npm run sync` (see example/package.json).
 *
 * The syntax stays at ES2017 level (no optional chaining, no `??`) so the page also runs on old
 * Android System WebView versions.
 *
 * E2E mode (docs/e2e/architecture.md §6, "Test-mode files"): the first startup step starts reading
 * files/e2e/example.json from the app's internal storage (the e2e kit writes it before it launches the app). If the
 * file holds {"e2e": true}, or localStorage['lt.e2e'] is '1', the page never calls a plugin method that changes state
 * on its own: no auto-ready, and no ready, setConfig, reset, start, startGeofences, stop, changePace, geofence
 * add/remove, sync, destroyLocations or destroyLog unless a button is tapped. It still subscribes to all events and
 * shows an "E2E mode" banner. The kit drives the plugin itself (Capacitor.Plugins.LocationTracking over the Chrome
 * DevTools protocol, or the debug broadcast receiver).
 * The buttons and the event listeners do not wait for the file; the two automatic state-changing calls (auto-ready
 * and the "stop" notification action) wait until the file was read, so they never run before e2e mode is known.
 */
(function () {
  'use strict';

  var EVENT_NAMES = [
    'location',
    'motionchange',
    'activitychange',
    'providerchange',
    'heartbeat',
    'geofence',
    'geofenceschange',
    'http',
    'connectivitychange',
    'powersavechange',
    'enabledchange',
    'notificationaction',
    'authorization',
  ];
  var WATCH_EVENT = 'watchPosition';
  var MAX_EVENTS = 200;
  var HEARTBEAT_REFRESH_MS = 10000;
  var STORAGE_KEY = 'lt-example:form:v1';
  var SAMPLE_COORDS = { latitude: 24.7136, longitude: 46.6753, accuracy: 10 };
  var METERS_PER_DEGREE = 111320;
  var STRATEGY_TEXT = {
    exact: 'exact allow-while-idle alarm: on time; in deep idle only if the app is exempt from battery optimization.',
    listener_with_backup: 'in-process exact alarm plus an allow-while-idle backup: on time while the device is awake.',
    idle_paced: 'device is in deep idle and the app is not exempt: heartbeats about 9 minutes apart.',
    disabled: 'no heartbeat is scheduled (heartbeat disabled or tracking off).',
  };
  var HB_FIELDS = [
    'strategy',
    'enabled',
    'minInterval',
    'maxInterval',
    'lastRecordAt',
    'lastHeartbeatAt',
    'nextHeartbeatAt',
    'pendingHeartbeats',
    'isIgnoringBatteryOptimizations',
    'canScheduleExactAlarms',
    'isDeviceIdleMode',
    'isPowerSaveMode',
  ];

  var APP_ID = 'com.brickssoft.locationtracking.example';
  var E2E_FILE = 'files/e2e/example.json';
  var E2E_STORAGE_KEY = 'lt.e2e';

  var ns = window.capacitorLocationTracking;
  var plugin = ns && ns.LocationTracking;

  /**
   * E2E mode, decided once at startup by detectE2eMode(); `source` is 'file', 'localStorage' or 'file+localStorage'.
   * Until the file was read, `e2e.on` is false; code that may change plugin state waits for `e2eDetection`.
   */
  var e2e = { on: false, source: null };
  var e2eDetection = null;

  function byId(id) {
    return document.getElementById(id);
  }

  /* ------------------------------------------------------------------ utilities */

  function isPlainObject(v) {
    return v !== null && typeof v === 'object' && !Array.isArray(v);
  }

  function deepMerge(base, extra) {
    var out = Object.assign({}, base);
    Object.keys(extra).forEach(function (key) {
      var v = extra[key];
      out[key] = isPlainObject(v) && isPlainObject(out[key]) ? deepMerge(out[key], v) : v;
    });
    return out;
  }

  function clientError(code, message) {
    var e = new Error(message);
    e.code = code;
    return e;
  }

  function errorInfo(e) {
    return {
      code: (e && e.code) || 'UNKNOWN',
      message: (e && e.message) || String(e),
    };
  }

  function text(id) {
    return byId(id).value.trim();
  }

  function checked(id) {
    return byId(id).checked;
  }

  function num(id, fallback) {
    var raw = byId(id).value.trim();
    var v = Number(raw);
    return raw !== '' && Number.isFinite(v) ? v : fallback;
  }

  function pad(n, width) {
    return String(n).padStart(width || 2, '0');
  }

  function fmtClock(value) {
    if (!value) return '–';
    var d = value instanceof Date ? value : new Date(value);
    if (isNaN(d.getTime())) return String(value);
    return pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
  }

  function fmtDuration(ms) {
    var s = Math.max(0, Math.round(ms / 1000));
    if (s < 60) return s + 's';
    var m = Math.floor(s / 60);
    if (m < 60) return m + 'm ' + pad(s % 60) + 's';
    var h = Math.floor(m / 60);
    return h + 'h ' + pad(m % 60) + 'm';
  }

  function relative(iso) {
    var t = Date.parse(iso);
    if (!Number.isFinite(t)) return '?';
    var diff = t - Date.now();
    return diff > 0 ? 'in ' + fmtDuration(diff) : fmtDuration(-diff) + ' ago';
  }

  function fmtTimeWithAge(iso) {
    return iso ? fmtClock(iso) + ' (' + relative(iso) + ')' : '–';
  }

  function fmtMeters(m) {
    if (typeof m !== 'number') return '–';
    return m >= 1000 ? (m / 1000).toFixed(2) + ' km' : Math.round(m) + ' m';
  }

  function fmtCoords(c) {
    if (!c || typeof c.latitude !== 'number') return 'no coords';
    var acc = typeof c.accuracy === 'number' ? ' ±' + Math.round(c.accuracy) + ' m' : '';
    return c.latitude.toFixed(5) + ', ' + c.longitude.toFixed(5) + acc;
  }

  function shortId(uuid) {
    return uuid ? String(uuid).slice(0, 8) : '–';
  }

  /** Tiny DOM builder; values are always set as text, never as HTML. */
  function el(tag, props) {
    var node = document.createElement(tag);
    Object.keys(props || {}).forEach(function (key) {
      var v = props[key];
      if (v === null || v === undefined || v === false) return;
      if (key === 'text') node.textContent = String(v);
      else if (key === 'className') node.className = v;
      else if (key === 'dataset') Object.assign(node.dataset, v);
      else node.setAttribute(key, v === true ? '' : String(v));
    });
    for (var i = 2; i < arguments.length; i++) {
      var child = arguments[i];
      if (child !== null && child !== undefined) node.append(child);
    }
    return node;
  }

  /* ------------------------------------------------------------------ e2e mode */

  /**
   * Reads the test-mode file files/e2e/example.json through Capacitor's local server. Resolves with the parsed JSON
   * object, or null for every failure: no Capacitor file URL (web), fetch error, non-2xx status, empty body, invalid
   * JSON, or a value that is not an object. There is no timeout: a slow read only delays auto-ready, and a timeout
   * could turn e2e mode off in a slow test run (auto-ready would then reset the plugin config during the test).
   */
  function readTestModeFile() {
    var cap = window.Capacitor;
    if (!cap || typeof cap.convertFileSrc !== 'function' || typeof fetch !== 'function') return Promise.resolve(null);
    try {
      return fetch(cap.convertFileSrc('/data/data/' + APP_ID + '/' + E2E_FILE), { cache: 'no-store' })
        .then(function (res) {
          return res.ok ? res.text() : '';
        })
        .then(function (body) {
          if (!body || !body.trim()) return null;
          var value = JSON.parse(body);
          return isPlainObject(value) ? value : null;
        })
        .catch(function () {
          return null;
        });
    } catch (e) {
      return Promise.resolve(null);
    }
  }

  function e2eFlagInStorage() {
    try {
      return localStorage.getItem(E2E_STORAGE_KEY) === '1';
    } catch (e) {
      return false;
    }
  }

  /** Resolves with `{on, source}`; never rejects. */
  function detectE2eMode() {
    return readTestModeFile().then(function (file) {
      var fromFile = Boolean(file) && file.e2e === true;
      var fromStorage = e2eFlagInStorage();
      var sources = [];
      if (fromFile) sources.push('file');
      if (fromStorage) sources.push('localStorage');
      return { on: sources.length > 0, source: sources.length ? sources.join('+') : null };
    });
  }

  /**
   * Status reads (getState, getHeartbeatStatus) are refreshed automatically after the page's own ready(), and always
   * in e2e mode, where the kit calls ready() (through the WebView or the debug receiver) instead of the page.
   */
  function canRefreshStatus() {
    return isReady || e2e.on;
  }

  function leaveE2eMode() {
    try {
      localStorage.removeItem(E2E_STORAGE_KEY);
    } catch (e) {
      /* storage unavailable: nothing to remove */
    }
    window.location.reload();
  }

  function showE2eBanner() {
    document.documentElement.setAttribute('data-e2e-mode', e2e.source);
    var banner = byId('e2e-banner');
    var where = [];
    if (e2e.source.indexOf('file') !== -1) where.push(E2E_FILE + ' {"e2e": true}');
    if (e2e.source.indexOf('localStorage') !== -1) where.push("localStorage['" + E2E_STORAGE_KEY + "'] = '1'");
    byId('e2e-banner-source').textContent = where.join(' and ');
    // Only the localStorage flag can be removed from the page; the kit removes the file.
    byId('e2e-leave').hidden = e2e.source.indexOf('localStorage') === -1;
    banner.hidden = false;
  }

  /* ------------------------------------------------------------------ form persistence */

  function storageGet() {
    try {
      var parsed = JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}');
      return isPlainObject(parsed) ? parsed : {};
    } catch (e) {
      return {};
    }
  }

  function storageSet(value) {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(value));
    } catch (e) {
      /* storage unavailable (private mode, blocked): the form just is not remembered */
    }
  }

  function persistentFields() {
    return Array.prototype.slice.call(document.querySelectorAll('[data-persist]'));
  }

  function restoreForm() {
    var saved = storageGet();
    persistentFields().forEach(function (field) {
      if (!Object.prototype.hasOwnProperty.call(saved, field.id)) return;
      if (field.type === 'checkbox') field.checked = Boolean(saved[field.id]);
      else field.value = String(saved[field.id]);
    });
    if (!text('cfg-device-id')) {
      byId('cfg-device-id').value = 'example-' + Math.random().toString(16).slice(2, 8);
      saveForm();
    }
  }

  function saveForm() {
    var data = {};
    persistentFields().forEach(function (field) {
      data[field.id] = field.type === 'checkbox' ? field.checked : field.value;
    });
    storageSet(data);
  }

  /* ------------------------------------------------------------------ output dock */

  function showOutput(label, value, kind) {
    var status = byId('output-status');
    status.textContent = kind === 'error' ? 'error' : kind === 'busy' ? 'running' : 'ok';
    status.className = 'badge ' + kind;
    var dock = byId('dock');
    dock.classList.toggle('ok', kind === 'ok');
    dock.classList.toggle('error', kind === 'error');
    byId('output-label').textContent = label;
    byId('output-time').textContent = fmtClock(new Date());
    var out = byId('output');
    out.textContent = typeof value === 'string' ? value : JSON.stringify(value, null, 2);
    out.scrollTop = 0;
  }

  /**
   * Runs one action, shows its result (or `code` + `message` on failure) in the output dock and
   * updates the state panel when the result is a State.
   */
  function run(label, fn, format) {
    showOutput(label, '…', 'busy');
    return Promise.resolve()
      .then(fn)
      .then(function (result) {
        if (isState(result)) renderState(result);
        var shown = format ? format(result) : result === undefined ? '(resolved without a value)' : result;
        showOutput(label, shown, 'ok');
        return { ok: true, result: result };
      })
      .catch(function (e) {
        var err = errorInfo(e);
        showOutput(label + ' failed', 'code: ' + err.code + '\nmessage: ' + err.message, 'error');
        return { ok: false, error: err };
      });
  }

  /* ------------------------------------------------------------------ state panel */

  var lastState = null;
  var isReady = false;

  function isState(v) {
    return isPlainObject(v) && typeof v.enabled === 'boolean' && typeof v.trackingMode === 'string';
  }

  function setChip(name, label, tone) {
    var chip = document.querySelector('[data-chip="' + name + '"]');
    if (!chip) return;
    chip.textContent = label;
    chip.className = 'chip' + (tone ? ' ' + tone : '');
  }

  function setStateField(name, value) {
    var dd = document.querySelector('[data-state="' + name + '"]');
    if (dd) dd.textContent = value;
  }

  function show(v) {
    return v === undefined || v === null ? '–' : String(v);
  }

  function renderState(state) {
    lastState = state;
    var config = isPlainObject(state.config) ? state.config : null;
    var http = config && isPlainObject(config.http) ? config.http : {};
    var hb = config && isPlainObject(config.heartbeat) ? config.heartbeat : null;
    setStateField('enabled', show(state.enabled));
    setStateField('trackingMode', show(state.trackingMode));
    setStateField('isMoving', show(state.isMoving));
    setStateField('odometer', fmtMeters(state.odometer));
    setStateField('backend', show(state.backend));
    setStateField('lastRecordAt', fmtTimeWithAge(state.lastRecordAt));
    setStateField('locationProvider', show(config && config.locationProvider));
    setStateField('url', show(http.url) === '–' ? '(none: records stay queued)' : http.url);
    setStateField(
      'heartbeat',
      hb ? (hb.enabled === false ? 'disabled' : show(hb.minInterval) + '–' + show(hb.maxInterval) + ' s') : '–',
    );
    if (config) byId('state-config').textContent = JSON.stringify(config, null, 2);

    if (typeof state.enabled === 'boolean') {
      setChip('enabled', 'enabled: ' + state.enabled, state.enabled ? 'on' : 'off');
    }
    if (state.trackingMode) setChip('mode', 'mode: ' + state.trackingMode);
    if (typeof state.isMoving === 'boolean') setChip('moving', 'moving: ' + state.isMoving, state.isMoving ? 'on' : '');
    if (state.backend) setChip('backend', 'backend: ' + state.backend);
  }

  /** Applies a partial update from an event (the next State from the plugin replaces it). */
  function patchState(patch) {
    renderState(Object.assign({}, lastState || {}, patch));
  }

  function setReady(value, failed) {
    isReady = value;
    setChip('ready', 'ready: ' + (value ? 'yes' : failed ? 'failed' : 'no'), value ? 'on' : failed ? 'off' : '');
  }

  /* ------------------------------------------------------------------ config builder */

  function buildConfig() {
    var deviceId = text('cfg-device-id');
    var config = {
      locationProvider: byId('cfg-provider').value,
      geolocation: {
        desiredAccuracy: byId('cfg-accuracy').value,
        distanceFilter: num('cfg-distance', 10),
      },
      heartbeat: {
        enabled: checked('cfg-hb-enabled'),
        minInterval: num('cfg-hb-min', 180),
        maxInterval: num('cfg-hb-max', 300),
      },
      http: {
        url: text('cfg-url') || null,
        autoSync: checked('cfg-autosync'),
        batchSync: checked('cfg-batch'),
        params: deviceId ? { device_id: deviceId } : {},
      },
      app: {
        stopOnTerminate: checked('cfg-stop-on-terminate'),
        startOnBoot: checked('cfg-start-on-boot'),
      },
      notification: {
        title: text('cfg-notif-title') || undefined,
        text: text('cfg-notif-text') || undefined,
        actions: checked('cfg-actions')
          ? [
              { id: 'ping', label: 'Ping' },
              { id: 'stop', label: 'Stop' },
            ]
          : [],
      },
      logger: { logLevel: byId('cfg-loglevel').value },
    };
    var extraText = text('cfg-extra');
    if (!extraText) return config;
    var extra;
    try {
      extra = JSON.parse(extraText);
    } catch (e) {
      throw clientError('INVALID_JSON', 'Extra config is not valid JSON: ' + e.message);
    }
    if (!isPlainObject(extra)) throw clientError('INVALID_JSON', 'Extra config must be a JSON object.');
    return deepMerge(config, extra);
  }

  /* ------------------------------------------------------------------ positions and geofences */

  var lastCoords = null;
  var watchIds = [];

  function rememberCoords(location) {
    if (location && location.coords && typeof location.coords.latitude === 'number') lastCoords = location.coords;
  }

  function renderWatchIds(selected) {
    var select = byId('watch-ids');
    select.textContent = '';
    if (!watchIds.length) select.append(el('option', { value: '', text: '(none)' }));
    watchIds.forEach(function (id) {
      select.append(el('option', { value: id, text: id, selected: id === selected }));
    });
  }

  /** A fresh current position (not persisted), as `{ latitude, longitude }`. */
  function currentCenter() {
    return plugin
      .getCurrentPosition({ samples: 1, persist: false, maximumAge: 60000, desiredAccuracy: 'high' })
      .then(function (location) {
        rememberCoords(location);
        if (!location || !location.coords) throw clientError('NO_COORDS', 'getCurrentPosition returned no coordinates.');
        return { latitude: location.coords.latitude, longitude: location.coords.longitude };
      });
  }

  /** The geofence center: the latitude/longitude fields if both are set, else a fresh current position. */
  function geofenceCenter() {
    var lat = text('geo-lat');
    var lng = text('geo-lng');
    if (lat !== '' || lng !== '') {
      var la = Number(lat);
      var ln = Number(lng);
      if (lat === '' || lng === '' || !Number.isFinite(la) || !Number.isFinite(ln)) {
        return Promise.reject(clientError('INVALID_INPUT', 'Set both latitude and longitude, or leave both empty.'));
      }
      return Promise.resolve({ latitude: la, longitude: ln });
    }
    return currentCenter();
  }

  function round7(v) {
    return Math.round(v * 1e7) / 1e7;
  }

  function geofenceBase(identifier, shape) {
    return {
      identifier: identifier,
      notifyOnEntry: true,
      notifyOnExit: true,
      notifyOnDwell: checked('geo-dwell'),
      loiteringDelay: 30000,
      extras: { shape: shape, created_by: 'lt-example' },
    };
  }

  function circleGeofence(identifier, center, radius) {
    return Object.assign(geofenceBase(identifier, 'circle'), {
      latitude: round7(center.latitude),
      longitude: round7(center.longitude),
      radius: radius,
    });
  }

  /** A square polygon centered on `center`, `half` meters from the center to each side. */
  function squareGeofence(identifier, center, half) {
    var dLat = half / METERS_PER_DEGREE;
    var dLng = half / (METERS_PER_DEGREE * Math.max(Math.cos((center.latitude * Math.PI) / 180), 0.01));
    var lat = center.latitude;
    var lng = center.longitude;
    return Object.assign(geofenceBase(identifier, 'square'), {
      vertices: [
        [round7(lat + dLat), round7(lng - dLng)],
        [round7(lat + dLat), round7(lng + dLng)],
        [round7(lat - dLat), round7(lng + dLng)],
        [round7(lat - dLat), round7(lng - dLng)],
      ],
    });
  }

  function geofenceId() {
    var id = text('geo-id');
    if (!id) throw clientError('INVALID_INPUT', 'Enter a geofence identifier.');
    return id;
  }

  function geofenceRadius() {
    var r = num('geo-radius', 0);
    if (!(r > 0)) throw clientError('INVALID_INPUT', 'Radius must be a positive number of meters.');
    return r;
  }

  function renderGeofences(list) {
    var ul = byId('geofence-list');
    ul.textContent = '';
    if (!list.length) {
      ul.append(el('li', { text: 'No geofences registered.' }));
      return;
    }
    list.forEach(function (g) {
      var shape = g.vertices
        ? 'polygon, ' + g.vertices.length + ' vertices (enclosing circle ' + Math.round(g.radius || 0) + ' m)'
        : 'circle ' + Math.round(g.radius || 0) + ' m';
      var where = typeof g.latitude === 'number' ? ' at ' + g.latitude.toFixed(5) + ', ' + g.longitude.toFixed(5) : '';
      ul.append(
        el(
          'li',
          null,
          el('span', null, el('strong', { text: g.identifier }), ' ' + shape + where),
          el('button', {
            type: 'button',
            className: 'danger',
            text: 'remove',
            dataset: { action: 'removeGeofenceItem', id: g.identifier },
          }),
        ),
      );
    });
  }

  function renderRecords(locations) {
    var table = byId('records-table');
    var body = table.querySelector('tbody');
    body.textContent = '';
    table.hidden = false;
    if (!locations.length) {
      body.append(el('tr', null, el('td', { colspan: 4, text: 'The queue is empty.' })));
      return;
    }
    locations
      .slice()
      .reverse()
      .forEach(function (r) {
        body.append(
          el(
            'tr',
            { dataset: { uuid: r.uuid }, title: 'Tap to select for destroyLocation' },
            el('td', { text: r.event }),
            el('td', { text: fmtClock(r.recorded_at) }),
            el('td', { text: fmtCoords(r.coords) }),
            el('td', { text: shortId(r.uuid) }),
          ),
        );
      });
  }

  /* ------------------------------------------------------------------ heartbeat panel */

  var hbStatus = null;
  var hbInFlight = false;

  function fmtHbValue(key, v) {
    if (/At$/.test(key)) return fmtTimeWithAge(v);
    if (key === 'minInterval' || key === 'maxInterval') return show(v) + ' s';
    return show(v);
  }

  function heartbeatSummary(s) {
    if (!s.enabled) return 'Heartbeat is disabled in the config.';
    var parts = [s.strategy + ': ' + (STRATEGY_TEXT[s.strategy] || '')];
    parts.push('Window ' + s.minInterval + '–' + s.maxInterval + ' s after the last record.');
    parts.push(s.nextHeartbeatAt ? 'Next check ' + relative(s.nextHeartbeatAt) + '.' : 'Nothing scheduled.');
    if (s.pendingHeartbeats > 0) parts.push(s.pendingHeartbeats + ' heartbeat(s) waiting for upload.');
    if (!s.isIgnoringBatteryOptimizations) {
      parts.push('Not exempt from battery optimization: in deep idle heartbeats are about 9 min apart.');
    }
    return parts.join(' ');
  }

  function renderHeartbeat(s) {
    hbStatus = s;
    byId('hb-summary').textContent = heartbeatSummary(s);
    var grid = byId('hb-grid');
    grid.textContent = '';
    HB_FIELDS.forEach(function (key) {
      var row = el('div', { className: key === 'strategy' ? 'wide' : '' });
      row.append(el('dt', { text: key }), el('dd', { text: fmtHbValue(key, s[key]) }));
      grid.append(row);
    });
  }

  function refreshHeartbeatQuietly() {
    if (!canRefreshStatus() || hbInFlight) return;
    hbInFlight = true;
    plugin
      .getHeartbeatStatus()
      .then(function (s) {
        renderHeartbeat(s);
        byId('hb-error').hidden = true;
      })
      .catch(function (e) {
        var err = errorInfo(e);
        var box = byId('hb-error');
        // In e2e mode NOT_READY only means that ready() was not called through this WebView (for example the kit
        // used the debug receiver); it is not an error of the page.
        if (e2e.on && !isReady && err.code === 'NOT_READY') {
          box.hidden = true;
          return;
        }
        box.textContent = 'Auto-refresh failed: ' + err.code + ': ' + err.message;
        box.hidden = false;
      })
      .then(function () {
        hbInFlight = false;
      });
  }

  /* ------------------------------------------------------------------ permissions */

  function renderPermissions(status) {
    var box = byId('perm-chips');
    box.textContent = '';
    Object.keys(status || {}).forEach(function (key) {
      var v = status[key];
      box.append(el('span', { className: 'chip ' + (v === 'granted' ? 'on' : v === 'denied' ? 'off' : 'warn'), text: key + ': ' + v }));
    });
  }

  /* ------------------------------------------------------------------ event log */

  var listenerHandles = [];
  var eventCounts = {};
  var eventTotal = 0;

  var SUMMARIES = {
    location: function (p) {
      return p.event + ' ' + fmtCoords(p.coords) + (p.is_moving ? ', moving' : '') + ', odometer ' + fmtMeters(p.odometer);
    },
    motionchange: function (p) {
      return (p.isMoving ? 'moving' : 'stationary') + ' at ' + fmtCoords(p.location && p.location.coords);
    },
    activitychange: function (p) {
      return p.activity + ' (' + p.confidence + '%)';
    },
    providerchange: function (p) {
      return (
        'enabled=' + p.enabled + ' gps=' + p.gps + ' network=' + p.network + ' permission=' + p.permission +
        ' accuracy=' + p.accuracy + ' backend=' + p.backend
      );
    },
    heartbeat: function (p) {
      var l = p.location || {};
      var fix = l.timestamp ? 'last fix ' + relative(l.timestamp) : 'no location known';
      return 'recorded ' + fmtClock(l.recorded_at) + ', ' + fix + ', ' + fmtCoords(l.coords);
    },
    geofence: function (p) {
      return p.action + ' ' + p.identifier;
    },
    geofenceschange: function (p) {
      var on = (p.on || []).map(function (g) {
        return g.identifier;
      });
      return 'on: [' + on.join(', ') + '] off: [' + (p.off || []).join(', ') + ']';
    },
    http: function (p) {
      return (p.success ? 'OK' : 'FAILED') + ', status ' + p.status + ', ' + (p.uuids || []).length + ' record(s)';
    },
    connectivitychange: function (p) {
      return (p.connected ? 'connected' : 'disconnected') + ' (' + p.type + ')';
    },
    powersavechange: function (p) {
      return 'power save mode ' + (p.isPowerSaveMode ? 'ON' : 'off');
    },
    enabledchange: function (p) {
      return 'tracking ' + (p.enabled ? 'enabled' : 'disabled');
    },
    notificationaction: function (p) {
      return 'action "' + p.id + '"';
    },
    authorization: function (p) {
      return (p.success ? 'token refreshed' : 'refresh failed') + ' (status ' + p.status + ')' + (p.error ? ': ' + p.error : '');
    },
  };

  function summarize(name, payload) {
    try {
      var fn = SUMMARIES[name];
      if (fn && isPlainObject(payload)) return fn(payload);
    } catch (e) {
      /* fall through to the raw payload */
    }
    var raw = JSON.stringify(payload);
    return raw && raw.length > 160 ? raw.slice(0, 157) + '...' : String(raw);
  }

  function addEventEntry(name, summary, payload, isError) {
    eventTotal += 1;
    eventCounts[name] = (eventCounts[name] || 0) + 1;
    var now = new Date();
    var filter = byId('event-filter').value;
    var li = el(
      'li',
      { className: isError ? 'error' : '', dataset: { event: name } },
      el(
        'div',
        { className: 'ev-head' },
        el('span', { className: 'ev-time', title: now.toISOString(), text: fmtClock(now) }),
        el('span', { className: 'ev-name', dataset: { event: name }, text: name }),
        el('span', { className: 'ev-summary', text: summary }),
      ),
      el('details', null, el('summary', { text: 'payload' }), el('pre', { className: 'code', text: JSON.stringify(payload, null, 2) })),
    );
    li.hidden = Boolean(filter) && filter !== name;
    var log = byId('event-log');
    log.prepend(li);
    while (log.children.length > MAX_EVENTS) log.lastElementChild.remove();
    renderEventCounts();
  }

  function renderEventCounts() {
    byId('event-total').textContent = '(' + eventTotal + ')';
    var box = byId('event-counts');
    box.textContent = '';
    Object.keys(eventCounts).forEach(function (name) {
      box.append(el('span', { className: 'chip', text: name + ' ' + eventCounts[name] }));
    });
  }

  function applyEventFilter() {
    var filter = byId('event-filter').value;
    Array.prototype.forEach.call(byId('event-log').children, function (li) {
      li.hidden = Boolean(filter) && li.dataset.event !== filter;
    });
  }

  function setListenerStatus(message) {
    byId('listener-status').textContent = 'Listeners: ' + message;
  }

  /** Side effects of events on the panels; errors here must never break the event log. */
  function applyEvent(name, p) {
    switch (name) {
      case 'location':
        rememberCoords(p);
        patchState({ lastRecordAt: p.recorded_at, odometer: p.odometer, isMoving: p.is_moving });
        break;
      case 'motionchange':
        rememberCoords(p.location);
        patchState({ isMoving: p.isMoving });
        break;
      case 'heartbeat':
        rememberCoords(p.location);
        if (p.location) patchState({ lastRecordAt: p.location.recorded_at });
        refreshHeartbeatQuietly();
        break;
      case 'enabledchange':
        patchState({ enabled: p.enabled });
        refreshHeartbeatQuietly();
        break;
      case 'providerchange':
        if (p.backend) patchState({ backend: p.backend });
        break;
      case 'notificationaction':
        if (p.id === 'stop') {
          e2eDetection.then(function () {
            if (e2e.on) {
              showOutput('notification action "stop"', 'E2E mode: the page does not call stop() on its own.', 'ok');
            } else {
              run('stop (notification action)', function () {
                return plugin.stop();
              });
            }
          });
        }
        break;
      default:
        break;
    }
  }

  function onPluginEvent(name, payload) {
    try {
      if (isPlainObject(payload)) applyEvent(name, payload);
    } catch (e) {
      /* keep logging even if a payload has an unexpected shape */
    }
    addEventEntry(name, summarize(name, payload), payload, false);
  }

  function subscribeAll() {
    if (listenerHandles.length) {
      return Promise.resolve({ note: 'Already subscribed.', events: EVENT_NAMES });
    }
    return Promise.all(
      EVENT_NAMES.map(function (name) {
        return plugin.addListener(name, function (payload) {
          onPluginEvent(name, payload);
        });
      }),
    ).then(function (handles) {
      listenerHandles = handles;
      setListenerStatus('subscribed to ' + EVENT_NAMES.length + ' events.');
      return { subscribed: EVENT_NAMES };
    });
  }

  /* ------------------------------------------------------------------ actions */

  function call(label, method, options, format) {
    return run(
      label,
      function () {
        return options === undefined ? plugin[method]() : plugin[method](options);
      },
      format,
    );
  }

  var actions = {
    /* config and lifecycle */
    ready: function () {
      return run('ready', function () {
        return plugin.ready({ config: buildConfig(), reset: checked('cfg-reset') }).then(
          function (state) {
            setReady(true);
            refreshHeartbeatQuietly();
            return state;
          },
          function (e) {
            setReady(false, true);
            throw e;
          },
        );
      });
    },
    setConfig: function () {
      return run('setConfig', function () {
        return plugin.setConfig({ config: buildConfig() });
      });
    },
    reset: function () {
      return run('reset (defaults + form config)', function () {
        return plugin.reset({ config: buildConfig() });
      });
    },
    getState: function () {
      return call('getState', 'getState');
    },
    showConfig: function () {
      return run('config built from the form (not sent)', buildConfig);
    },

    /* tracking */
    start: function () {
      return call('start', 'start');
    },
    startGeofences: function () {
      return call('startGeofences', 'startGeofences');
    },
    stop: function () {
      return call('stop', 'stop');
    },
    changePaceMoving: function () {
      return call('changePace(isMoving: true)', 'changePace', { isMoving: true });
    },
    changePaceStationary: function () {
      return call('changePace(isMoving: false)', 'changePace', { isMoving: false });
    },

    /* positions */
    getCurrentPosition: function () {
      return run('getCurrentPosition', function () {
        return plugin
          .getCurrentPosition({
            samples: num('pos-samples', 3),
            desiredAccuracy: byId('pos-accuracy').value,
            persist: checked('pos-persist'),
            extras: { requested_by: 'lt-example' },
          })
          .then(function (location) {
            rememberCoords(location);
            return location;
          });
      });
    },
    watchPosition: function () {
      return run('watchPosition', function () {
        var watchId = null;
        var options = {
          interval: num('watch-interval', 1000),
          desiredAccuracy: byId('pos-accuracy').value,
          persist: checked('watch-persist'),
        };
        return plugin
          .watchPosition(options, function (location, error) {
            var tag = '[' + (watchId || '?') + '] ';
            if (error) {
              addEventEntry(WATCH_EVENT, tag + 'error ' + error.code + ': ' + error.message, error, true);
            } else if (location) {
              rememberCoords(location);
              addEventEntry(WATCH_EVENT, tag + fmtCoords(location.coords), location, false);
            }
          })
          .then(function (id) {
            watchId = id;
            watchIds.push(id);
            renderWatchIds(id);
            return { id: id, options: options };
          });
      });
    },
    clearWatch: function () {
      return run('clearWatch', function () {
        var id = byId('watch-ids').value || watchIds[watchIds.length - 1];
        if (!id) throw clientError('NO_WATCH', 'No active watch. Press watchPosition first.');
        return plugin.clearWatch({ id: id }).then(function () {
          watchIds = watchIds.filter(function (w) {
            return w !== id;
          });
          renderWatchIds(watchIds[watchIds.length - 1]);
          return { cleared: id };
        });
      });
    },

    /* odometer */
    getOdometer: function () {
      return run('getOdometer', function () {
        return plugin.getOdometer().then(function (r) {
          patchState({ odometer: r.odometer });
          return r;
        });
      });
    },
    setOdometer: function () {
      return run('setOdometer', function () {
        return plugin.setOdometer({ odometer: num('odo-value', 0) }).then(function (r) {
          patchState({ odometer: r.odometer });
          return r;
        });
      });
    },
    resetOdometer: function () {
      return run('resetOdometer', function () {
        return plugin.resetOdometer().then(function (r) {
          patchState({ odometer: r.odometer });
          return r;
        });
      });
    },

    /* records */
    getLocations: function () {
      return run('getLocations', function () {
        return plugin.getLocations({ limit: num('rec-limit', 20) }).then(function (r) {
          var list = (r && r.locations) || [];
          renderRecords(list);
          if (list.length) byId('rec-uuid').value = list[list.length - 1].uuid;
          return { count: list.length, locations: list };
        });
      });
    },
    getCount: function () {
      return call('getCount', 'getCount');
    },
    insertLocation: function () {
      return run('insertLocation', function () {
        var c = lastCoords || SAMPLE_COORDS;
        var input = {
          coords: {
            latitude: c.latitude,
            longitude: c.longitude,
            accuracy: typeof c.accuracy === 'number' ? c.accuracy : 10,
          },
          timestamp: new Date().toISOString(),
          event: 'location',
          is_moving: false,
          extras: { inserted_by: 'lt-example', sample: !lastCoords },
        };
        return plugin.insertLocation({ location: input }).then(function (r) {
          if (r && r.uuid) byId('rec-uuid').value = r.uuid;
          return { uuid: r && r.uuid, inserted: input };
        });
      });
    },
    sync: function () {
      return run('sync', function () {
        return plugin.sync().then(function (r) {
          var list = (r && r.locations) || [];
          return { uploaded: list.length, locations: list };
        });
      });
    },
    destroyLocation: function () {
      return run('destroyLocation', function () {
        var uuid = text('rec-uuid');
        if (!uuid) throw clientError('INVALID_INPUT', 'Enter a uuid, or press getLocations and tap a row.');
        return plugin.destroyLocation({ uuid: uuid });
      });
    },
    destroyLocations: function () {
      return call('destroyLocations', 'destroyLocations');
    },

    /* geofences */
    useCurrentPosition: function () {
      return run('getCurrentPosition (geofence center)', function () {
        return currentCenter().then(function (c) {
          byId('geo-lat').value = c.latitude.toFixed(6);
          byId('geo-lng').value = c.longitude.toFixed(6);
          return c;
        });
      });
    },
    addCircle: function () {
      return run('addGeofence (circle)', function () {
        var id = geofenceId();
        var radius = geofenceRadius();
        return geofenceCenter().then(function (center) {
          var geofence = circleGeofence(id, center, radius);
          return plugin.addGeofence({ geofence: geofence }).then(function () {
            return { added: geofence };
          });
        });
      });
    },
    addPolygon: function () {
      return run('addGeofence (square polygon)', function () {
        var id = geofenceId();
        var half = geofenceRadius();
        return geofenceCenter().then(function (center) {
          var geofence = squareGeofence(id, center, half);
          return plugin.addGeofence({ geofence: geofence }).then(function () {
            return { added: geofence };
          });
        });
      });
    },
    addBoth: function () {
      return run('addGeofences (circle + square)', function () {
        var id = geofenceId();
        var size = geofenceRadius();
        return geofenceCenter().then(function (center) {
          var geofences = [circleGeofence(id + '-circle', center, size), squareGeofence(id + '-square', center, size)];
          return plugin.addGeofences({ geofences: geofences }).then(function () {
            return { added: geofences };
          });
        });
      });
    },
    getGeofences: function () {
      return run('getGeofences', function () {
        return plugin.getGeofences().then(function (r) {
          renderGeofences((r && r.geofences) || []);
          return r;
        });
      });
    },
    getGeofence: function () {
      return run('getGeofence', function () {
        return plugin.getGeofence({ identifier: geofenceId() });
      });
    },
    geofenceExists: function () {
      return run('geofenceExists', function () {
        return plugin.geofenceExists({ identifier: geofenceId() });
      });
    },
    removeGeofence: function () {
      return run('removeGeofence', function () {
        return plugin.removeGeofence({ identifier: geofenceId() });
      });
    },
    removeGeofenceItem: function (button) {
      var id = button.dataset.id;
      return run('removeGeofence ' + id, function () {
        return plugin.removeGeofence({ identifier: id }).then(function () {
          var li = button.closest('li');
          if (li) li.remove();
          return { removed: id };
        });
      });
    },
    removeGeofences: function () {
      return run('removeGeofences (all)', function () {
        return plugin.removeGeofences().then(function () {
          renderGeofences([]);
        });
      });
    },

    /* heartbeat */
    getHeartbeatStatus: function () {
      return run('getHeartbeatStatus', function () {
        return plugin.getHeartbeatStatus().then(function (s) {
          renderHeartbeat(s);
          byId('hb-error').hidden = true;
          return s;
        });
      });
    },

    /* device and settings */
    getProviderState: function () {
      return call('getProviderState', 'getProviderState');
    },
    isPowerSaveMode: function () {
      return call('isPowerSaveMode', 'isPowerSaveMode');
    },
    getDeviceInfo: function () {
      return call('getDeviceInfo', 'getDeviceInfo');
    },
    getSensors: function () {
      return call('getSensors', 'getSensors');
    },
    getBatteryOptimizationStatus: function () {
      return call('getBatteryOptimizationStatus', 'getBatteryOptimizationStatus');
    },
    openBatteryOptimizationSettings: function () {
      return call('openBatteryOptimizationSettings', 'openBatteryOptimizationSettings');
    },
    getPowerManagerInfo: function () {
      return call('getPowerManagerInfo', 'getPowerManagerInfo');
    },
    openPowerManagerSettings: function () {
      return call('openPowerManagerSettings', 'openPowerManagerSettings');
    },
    openLocationSettings: function () {
      return call('openLocationSettings', 'openLocationSettings');
    },
    openAppSettings: function () {
      return call('openAppSettings', 'openAppSettings');
    },

    /* permissions */
    checkPermissions: function () {
      return run('checkPermissions', function () {
        return plugin.checkPermissions().then(function (s) {
          renderPermissions(s);
          return s;
        });
      });
    },
    requestPermissions: function () {
      return run('requestPermissions (checked)', function () {
        var types = Array.prototype.map.call(document.querySelectorAll('input[name="perm"]:checked'), function (i) {
          return i.value;
        });
        if (!types.length) throw clientError('INVALID_INPUT', 'Check at least one permission.');
        return plugin.requestPermissions({ permissions: types }).then(function (s) {
          renderPermissions(s);
          return s;
        });
      });
    },
    requestAllPermissions: function () {
      return run('requestPermissions (all four)', function () {
        return plugin.requestPermissions().then(function (s) {
          renderPermissions(s);
          return s;
        });
      });
    },

    /* plugin log */
    log: function () {
      return run('log', function () {
        var message = text('log-message');
        if (!message) throw clientError('INVALID_INPUT', 'Enter a message.');
        var level = byId('log-level').value;
        return plugin.log({ level: level, message: message }).then(function () {
          return { logged: { level: level, message: message } };
        });
      });
    },
    getLog: function () {
      return run(
        'getLog',
        function () {
          return plugin.getLog({ limit: num('log-limit', 200), order: byId('log-order').value });
        },
        function (r) {
          return (r && r.log) || '(the log is empty)';
        },
      );
    },
    uploadLog: function () {
      return run('uploadLog', function () {
        var url = text('log-upload-url');
        if (!url) throw clientError('INVALID_INPUT', 'Enter the uploadLog url.');
        return plugin.uploadLog({ url: url, params: { device_id: text('cfg-device-id') } });
      });
    },
    emailLog: function () {
      return run('emailLog', function () {
        var email = text('log-email');
        if (!email) throw clientError('INVALID_INPUT', 'Enter an email address.');
        return plugin.emailLog({ email: email, subject: 'LocationTracking log' });
      });
    },
    destroyLog: function () {
      return call('destroyLog', 'destroyLog');
    },

    /* events */
    subscribeAll: function () {
      return run('addListener (all 13 events)', subscribeAll);
    },
    removeAllListeners: function () {
      return run('removeAllListeners', function () {
        return plugin.removeAllListeners().then(function () {
          listenerHandles = [];
          setListenerStatus('none (removeAllListeners). Press "Subscribe to all 13 events" to listen again.');
        });
      });
    },
    clearEvents: function () {
      byId('event-log').textContent = '';
      eventCounts = {};
      eventTotal = 0;
      renderEventCounts();
    },
  };

  /* ------------------------------------------------------------------ wiring */

  function onClick(e) {
    var row = e.target.closest('#records-table tbody tr[data-uuid]');
    if (row) {
      byId('rec-uuid').value = row.dataset.uuid;
      return;
    }
    var button = e.target.closest('button[data-action]');
    if (!button) return;
    var fn = actions[button.dataset.action];
    if (!fn) {
      showOutput(button.dataset.action, 'This button has no handler.', 'error');
      return;
    }
    button.classList.add('busy');
    Promise.resolve()
      .then(function () {
        return fn(button);
      })
      .catch(function (err) {
        var info = errorInfo(err);
        showOutput(button.dataset.action + ' failed', 'code: ' + info.code + '\nmessage: ' + info.message, 'error');
      })
      .then(function () {
        button.classList.remove('busy');
      });
  }

  function toggleDock() {
    var dock = byId('dock');
    var expanded = dock.classList.toggle('expanded');
    var button = byId('dock-toggle');
    button.setAttribute('aria-expanded', String(expanded));
    button.textContent = expanded ? 'collapse' : 'expand';
  }

  function tick() {
    if (hbStatus) renderHeartbeat(hbStatus);
    if (lastState && lastState.lastRecordAt) setStateField('lastRecordAt', fmtTimeWithAge(lastState.lastRecordAt));
  }

  function onVisibilityChange() {
    if (document.visibilityState !== 'visible' || !canRefreshStatus()) return;
    if (checked('hb-auto')) refreshHeartbeatQuietly();
    plugin.getState().then(renderState, function () {
      /* shown on the next explicit call */
    });
  }

  function init() {
    var platform = window.Capacitor && typeof window.Capacitor.getPlatform === 'function' ? window.Capacitor.getPlatform() : 'unknown';
    byId('platform').textContent = 'platform: ' + platform;

    if (!plugin) {
      var fatal = byId('fatal');
      fatal.textContent =
        'The plugin bundle is missing (window.capacitorLocationTracking is undefined). ' +
        'Run "npm run build" in the repository root, then "npm run sync" in example/.';
      fatal.hidden = false;
      Array.prototype.forEach.call(document.querySelectorAll('button[data-action]'), function (b) {
        b.disabled = true;
      });
      return;
    }

    restoreForm();
    EVENT_NAMES.concat([WATCH_EVENT]).forEach(function (name) {
      byId('event-filter').append(el('option', { value: name, text: name }));
    });

    document.addEventListener('click', onClick);
    document.addEventListener('change', function (e) {
      if (e.target.matches('[data-persist]')) saveForm();
      if (e.target.id === 'event-filter') applyEventFilter();
    });
    document.addEventListener('visibilitychange', onVisibilityChange);
    byId('dock-toggle').addEventListener('click', toggleDock);
    byId('e2e-leave').addEventListener('click', leaveE2eMode);

    setInterval(tick, 1000);
    setInterval(function () {
      if (checked('hb-auto') && document.visibilityState === 'visible') refreshHeartbeatQuietly();
    }, HEARTBEAT_REFRESH_MS);

    subscribeAll().catch(function (e) {
      var err = errorInfo(e);
      setListenerStatus('subscription failed: ' + err.code + ': ' + err.message);
    });

    // Auto-ready waits for the test-mode file: in e2e mode the page never calls ready() on its own.
    e2eDetection.then(function () {
      if (e2e.on) showE2eBanner();
      if (checked('cfg-autoready') && !e2e.on) {
        actions.ready();
      } else {
        plugin.getState().then(renderState, function () {
          /* getState is allowed before ready(); ignore failures on platforms without it */
        });
      }
    });
  }

  // First startup step: start reading the test-mode file (see the top of this file).
  e2eDetection = detectE2eMode().then(
    function (mode) {
      e2e = mode;
    },
    function () {
      /* detectE2eMode never rejects; keep normal mode if it does */
    },
  );
  init();
})();
