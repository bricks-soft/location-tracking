/*
 * Field-force example app (docs/e2e/architecture.md §10): tracking starts automatically on every page load and
 * stops at 02:00 local time; this page shows the status of tracking, the stop time, the location provider, the
 * heartbeat, the premise and the last events.
 *
 * Plain browser JavaScript, no bundler. Globals:
 *   - FF_ENV (env.js, written by `npm run copy-vendor`): {backendUrl};
 *   - Capacitor (vendor/capacitor.js);
 *   - capacitorLocationTracking.LocationTracking (vendor/plugin.js);
 *   - capacitorPremiseMonitor.PremiseMonitor (vendor/premise-monitor.js);
 *   - FFCore (ff-core.js): the startup logic, also tested in Node (examples/field-force/test/).
 *
 * For the e2e tests the page publishes window.FF_APP; `FF_APP.startup` is the promise of the auto start.
 * ES2017 syntax only (no optional chaining, no `??`, no object spread).
 */
(function () {
  'use strict';

  var core = window.FFCore;
  var trackingNs = window.capacitorLocationTracking;
  var premiseNs = window.capacitorPremiseMonitor;
  var tracking = trackingNs && trackingNs.LocationTracking;
  var premise = premiseNs && premiseNs.PremiseMonitor;
  var cap = window.Capacitor;

  var SESSION_KEY = 'ff.session';
  var MAX_EVENTS = 30;
  var REFRESH_MS = 30000;
  var FILE_READ_TIMEOUT_MS = 5000;
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
  /** Events after which the status cards are read again (1 s later, once for a burst). */
  var REFRESH_EVENTS = ['enabledchange', 'motionchange', 'providerchange', 'heartbeat', 'geofence', 'http'];

  var app = {
    /** Promise of the auto start: resolves with {state, stopAfterElapsedMinutes, config, deviceInfo, ...}. */
    startup: null,
    /** 'running' | 'done' | 'failed' */
    status: 'running',
    /** name of the startup step that runs now (or 'done') */
    step: null,
    result: null,
    /** {code, message, step} when the startup failed */
    error: null,
    /** newest first: {at, name, summary} */
    events: [],
    refresh: refreshAll,
    /** FFCore (ff-core.js), e.g. FF_APP.core.minutesUntil('02:00') */
    core: core || null,
  };
  window.FF_APP = app;

  var refreshTimer = null;
  var pendingRefresh = null;
  /** the refresh in progress, and the one queued behind it */
  var refreshing = null;
  var refreshQueued = null;

  /* ------------------------------------------------------------------ small helpers */

  function byId(id) {
    return document.getElementById(id);
  }

  function pad(n) {
    return String(n).padStart(2, '0');
  }

  function fmtClock(value, withSeconds) {
    if (value === null || value === undefined || value === '') return '–';
    var d = value instanceof Date ? value : new Date(value);
    if (isNaN(d.getTime())) return String(value);
    return pad(d.getHours()) + ':' + pad(d.getMinutes()) + (withSeconds === false ? '' : ':' + pad(d.getSeconds()));
  }

  function fmtMinutes(total) {
    var m = Math.max(0, Math.round(total));
    var h = Math.floor(m / 60);
    return h > 0 ? h + ' h ' + (m % 60) + ' min' : m + ' min';
  }

  function yesNo(value) {
    if (value === true) return 'yes';
    if (value === false) return 'no';
    return '–';
  }

  function errorText(error) {
    var code = (error && error.code) || 'ERROR';
    var message = (error && error.message) || String(error);
    return code + ': ' + message;
  }

  function storageGet(key) {
    try {
      return window.localStorage.getItem(key);
    } catch (e) {
      return null;
    }
  }

  function storageSet(key, value) {
    try {
      if (value === null) window.localStorage.removeItem(key);
      else window.localStorage.setItem(key, value);
    } catch (e) {
      /* private mode or storage disabled: the stop time is then shown as unknown */
    }
  }

  /** [promise], or a rejection after [ms]. */
  function withTimeout(promise, ms) {
    return new Promise(function (resolve, reject) {
      var timer = setTimeout(function () {
        reject(new Error('timed out after ' + ms + ' ms'));
      }, ms);
      promise.then(
        function (value) {
          clearTimeout(timer);
          resolve(value);
        },
        function (error) {
          clearTimeout(timer);
          reject(error);
        },
      );
    });
  }

  /**
   * Text of files/e2e/ff-overrides.json through Capacitor's local server; null when there is no file (404) or no app
   * storage (browser). A timeout or a fetch error rejects: runStartup then continues without the file and reports a
   * warning (FF_APP.result.warnings and the banner).
   */
  function readOverridesFile() {
    if (!cap || typeof cap.isNativePlatform !== 'function' || !cap.isNativePlatform()) return Promise.resolve(null);
    if (typeof fetch !== 'function' || typeof cap.convertFileSrc !== 'function') return Promise.resolve(null);
    var url = cap.convertFileSrc(core.OVERRIDES_FILE_PATH);
    var request = fetch(url, { cache: 'no-store' }).then(function (response) {
      return response.ok ? response.text() : null;
    });
    return withTimeout(request, FILE_READ_TIMEOUT_MS);
  }

  /* ------------------------------------------------------------------ rendering */

  /** Replaces the rows of the <dl id=[id]> with [rows] = [[label, value, tone?], ...]. Text only, no HTML. */
  function renderRows(id, rows) {
    var dl = byId(id);
    if (!dl) return;
    while (dl.firstChild) dl.removeChild(dl.firstChild);
    rows.forEach(function (row) {
      var dt = document.createElement('dt');
      dt.textContent = row[0];
      var dd = document.createElement('dd');
      dd.textContent = row[1] === undefined || row[1] === null || row[1] === '' ? '–' : String(row[1]);
      if (row[2]) dd.className = row[2];
      dl.appendChild(dt);
      dl.appendChild(dd);
    });
  }

  function renderError(id, label, error) {
    renderRows(id, [[label, errorText(error), 'bad']]);
  }

  function setChip(id, text, tone) {
    var chip = byId(id);
    if (!chip) return;
    chip.textContent = text;
    chip.className = 'chip' + (tone ? ' ' + tone : '');
  }

  function showBanner(text, tone) {
    var banner = byId('banner');
    if (!banner) return;
    banner.textContent = text || '';
    banner.className = 'banner' + (tone ? ' ' + tone : '');
    banner.hidden = !text;
  }

  function renderStartup() {
    var result = app.result;
    var rows = [['Startup', app.status + (app.status === 'running' && app.step ? ' (' + app.step + ')' : ''),
      app.status === 'failed' ? 'bad' : app.status === 'done' ? 'good' : '']];
    if (result) {
      var overrides = result.overrides || {};
      rows.push(['Test overrides', result.overridesSource === 'none' ? 'none (production values)' : 'from ' + result.overridesSource]);
      rows.push(['Back office', result.backendUrl]);
      var http = (result.config && result.config.http) || {};
      var hb = (result.config && result.config.heartbeat) || {};
      rows.push(['Upload interval', http.syncInterval + ' s']);
      rows.push(['Heartbeat interval', hb.minInterval + '–' + hb.maxInterval + ' s']);
      rows.push(['Auto start', overrides.autoStart === false ? 'off (test override)' : 'on']);
      rows.push(['Started by this page', yesNo(result.started)]);
      if (result.warnings && result.warnings.length) rows.push(['Warnings', result.warnings.join('; '), 'warn']);
    }
    if (app.error) rows.push(['Error', (app.error.step ? app.error.step + ': ' : '') + errorText(app.error), 'bad']);
    renderRows('startup-rows', rows);
    setChip('chip-startup', 'startup: ' + app.status, app.status === 'failed' ? 'bad' : app.status === 'done' ? 'good' : '');
  }

  /** The stop time: from the start time this page recorded when it called start(). */
  function stopRow(state) {
    if (!state || !state.enabled) return ['Automatic stop', 'tracking is off'];
    if (!core) return ['Automatic stop', 'unknown'];
    var geo = (state.config && state.config.geolocation) || {};
    var minutes = geo.stopAfterElapsedMinutes;
    if (!(minutes > 0)) return ['Automatic stop', 'none (stopAfterElapsedMinutes is 0)', 'warn'];
    var session = null;
    try {
      session = JSON.parse(storageGet(SESSION_KEY) || 'null');
    } catch (e) {
      session = null;
    }
    var info = session && session.minutes === minutes ? core.describeStop(session.startedAt, minutes, Date.now()) : null;
    if (!info || info.passed) {
      return ['Automatic stop', minutes + ' min after the session start (start time unknown to this page)'];
    }
    return ['Automatic stop', 'at ' + fmtClock(info.stopAtMs, false) + ' (in ' + fmtMinutes(info.minutesLeft) + ')'];
  }

  function renderState(state, queued) {
    if (!state.enabled) storageSet(SESSION_KEY, null);
    setChip('chip-tracking', state.enabled ? 'tracking: on' : 'tracking: off', state.enabled ? 'good' : 'bad');
    renderRows('shift-rows', [
      ['Tracking', state.enabled ? 'on (' + state.trackingMode + ')' : 'off', state.enabled ? 'good' : 'bad'],
      stopRow(state),
      ['Moving', yesNo(state.isMoving)],
      ['Odometer', (Number(state.odometer || 0) / 1000).toFixed(2) + ' km'],
      ['Last record', fmtClock(state.lastRecordAt)],
      ['Queued records', queued],
      ['Backend', state.backend],
    ]);
  }

  function renderProvider(provider, permissions) {
    var rows = [];
    if (provider) {
      rows.push(['Location services', provider.enabled ? 'on' : 'off', provider.enabled ? 'good' : 'bad']);
      rows.push(['GPS / network', yesNo(provider.gps) + ' / ' + yesNo(provider.network)]);
      rows.push(['Permission', provider.permission, provider.permission === 'always' ? 'good' : 'warn']);
      rows.push(['Accuracy', provider.accuracy, provider.accuracy === 'precise' ? '' : 'warn']);
      rows.push(['Provider backend', provider.backend]);
    }
    if (permissions) {
      Object.keys(permissions).forEach(function (key) {
        rows.push(['Permission: ' + key, permissions[key], permissions[key] === 'granted' ? '' : 'warn']);
      });
    }
    renderRows('provider-rows', rows);
  }

  function renderHeartbeat(hb) {
    renderRows('heartbeat-rows', [
      ['Strategy', hb.strategy, hb.strategy === 'idle_paced' || hb.strategy === 'disabled' ? 'warn' : ''],
      ['Interval', hb.minInterval + '–' + hb.maxInterval + ' s'],
      ['Last heartbeat', fmtClock(hb.lastHeartbeatAt)],
      ['Next heartbeat', fmtClock(hb.nextHeartbeatAt)],
      ['Battery optimization exempt', yesNo(hb.isIgnoringBatteryOptimizations)],
      ['Exact alarms allowed', yesNo(hb.canScheduleExactAlarms)],
      ['Device idle (Doze)', yesNo(hb.isDeviceIdleMode)],
      ['Power save mode', yesNo(hb.isPowerSaveMode)],
    ]);
  }

  function renderPremise(status) {
    var p = status.premise;
    renderRows('premise-rows', [
      ['Monitoring', yesNo(status.monitoring), status.monitoring ? 'good' : ''],
      ['Premise', p ? (p.name || p.id) + ' (' + p.radius + ' m)' : 'none'],
      ['Inside', status.inside === null || status.inside === undefined ? 'unknown' : yesNo(status.inside)],
      ['Premise service running', yesNo(status.serviceRunning)],
      ['Audit uploads pending', status.pendingUploads],
      ['Last audit entry', fmtClock(status.lastEntryAt)],
    ]);
  }

  function coordsText(location) {
    var c = location && location.coords;
    if (!c) return 'no position';
    return c.latitude.toFixed(5) + ', ' + c.longitude.toFixed(5) + ' ±' + Math.round(c.accuracy) + ' m';
  }

  /** One line per event for the event list. */
  function summarize(name, e) {
    e = e || {};
    switch (name) {
      case 'location':
        return e.event + ' ' + coordsText(e) + (e.is_moving ? ' moving' : ' stationary');
      case 'motionchange':
        return (e.isMoving ? 'moving' : 'stationary') + ' at ' + coordsText(e.location);
      case 'heartbeat':
        return 'position from ' + fmtClock(e.location && e.location.timestamp) + ', ' + coordsText(e.location);
      case 'providerchange':
        return 'enabled ' + yesNo(e.enabled) + ', permission ' + e.permission + ', accuracy ' + e.accuracy;
      case 'geofence':
        return e.identifier + ' ' + e.action;
      case 'geofenceschange':
        return 'on ' + ((e.on && e.on.length) || 0) + ', off ' + ((e.off && e.off.length) || 0);
      case 'http':
        return 'HTTP ' + e.status + (e.success ? ' ok' : ' failed') + ', ' + ((e.uuids && e.uuids.length) || 0) + ' records';
      case 'enabledchange':
        return e.enabled ? 'tracking on' : 'tracking off';
      case 'connectivitychange':
        return (e.connected ? 'online' : 'offline') + ' (' + e.type + ')';
      case 'powersavechange':
        return 'power save ' + yesNo(e.isPowerSaveMode);
      case 'activitychange':
        return e.activity + ' ' + e.confidence + '%';
      case 'authorization':
        return 'token refresh ' + (e.success ? 'ok' : 'failed') + ' (' + e.status + ')';
      case 'notificationaction':
        return 'button ' + e.id;
      default:
        return '';
    }
  }

  function renderEvents() {
    var list = byId('events');
    if (!list) return;
    while (list.firstChild) list.removeChild(list.firstChild);
    if (app.events.length === 0) {
      var empty = document.createElement('li');
      empty.className = 'muted';
      empty.textContent = 'No events yet.';
      list.appendChild(empty);
      return;
    }
    app.events.forEach(function (entry) {
      var li = document.createElement('li');
      var time = document.createElement('time');
      time.textContent = fmtClock(entry.at);
      var name = document.createElement('b');
      name.textContent = entry.name;
      li.appendChild(time);
      li.appendChild(document.createTextNode(' '));
      li.appendChild(name);
      li.appendChild(document.createTextNode(' ' + entry.summary));
      list.appendChild(li);
    });
  }

  /* ------------------------------------------------------------------ data */

  function onEvent(name, payload) {
    var summary = '';
    try {
      summary = summarize(name, payload);
    } catch (e) {
      summary = '';
    }
    app.events.unshift({ at: Date.now(), name: name, summary: summary });
    if (app.events.length > MAX_EVENTS) app.events.length = MAX_EVENTS;
    renderEvents();
    // Not during the startup: most reads are rejected with NOT_READY before ready() has resolved.
    if (app.status !== 'running' && REFRESH_EVENTS.indexOf(name) >= 0) scheduleRefresh(1000);
  }

  function subscribeEvents() {
    EVENT_NAMES.forEach(function (name) {
      Promise.resolve()
        .then(function () {
          return tracking.addListener(name, function (payload) {
            onEvent(name, payload);
          });
        })
        .catch(function (error) {
          console.warn('[field-force] addListener(' + name + ') failed', error);
        });
    });
  }

  /** Runs [load], then [render] with its value; an error of either is shown in the card [rowsId]. Never rejects. */
  function card(rowsId, label, load, render) {
    return Promise.resolve()
      .then(load)
      .then(render)
      .catch(function (error) {
        renderError(rowsId, label, error);
      });
  }

  /**
   * Reads every status card again. A call while a refresh runs queues one more run after it, so a state change
   * reported by an event is never hidden behind an older read; further calls share that queued run.
   */
  function refreshAll() {
    if (refreshing) {
      if (!refreshQueued) {
        refreshQueued = refreshing.then(function () {
          refreshQueued = null;
          return refreshAll();
        });
      }
      return refreshQueued;
    }
    var jobs = [];
    if (tracking) {
      jobs.push(
        card(
          'shift-rows',
          'Tracking',
          function () {
            var count = tracking.getCount().then(
              function (r) {
                return r.count;
              },
              function () {
                return '–';
              },
            );
            return Promise.all([tracking.getState(), count]);
          },
          function (values) {
            renderState(values[0], values[1]);
          },
        ),
      );
      jobs.push(
        card(
          'provider-rows',
          'Provider',
          function () {
            var permissions = tracking.checkPermissions().catch(function () {
              return null;
            });
            return Promise.all([tracking.getProviderState(), permissions]);
          },
          function (values) {
            renderProvider(values[0], values[1]);
          },
        ),
      );
      jobs.push(
        card(
          'heartbeat-rows',
          'Heartbeat',
          function () {
            return tracking.getHeartbeatStatus();
          },
          renderHeartbeat,
        ),
      );
    }
    if (premise) {
      jobs.push(
        card(
          'premise-rows',
          'Premise',
          function () {
            return premise.getStatus();
          },
          renderPremise,
        ),
      );
    } else {
      renderRows('premise-rows', [['Premise', 'PremiseMonitor plugin not loaded', 'warn']]);
    }
    var updated = byId('updated');
    if (updated) updated.textContent = 'Updated ' + fmtClock(new Date());
    refreshing = Promise.all(jobs).then(function () {
      refreshing = null;
    });
    return refreshing;
  }

  function scheduleRefresh(delayMs) {
    if (pendingRefresh) return;
    pendingRefresh = setTimeout(function () {
      pendingRefresh = null;
      refreshAll();
    }, delayMs);
  }

  /** Reads the status every 30 s while the page is visible; no polling in the background (battery). */
  function updateTimer() {
    var visible = document.visibilityState !== 'hidden';
    if (visible && !refreshTimer && app.status !== 'running') {
      refreshTimer = setInterval(refreshAll, REFRESH_MS);
    } else if (!visible && refreshTimer) {
      clearInterval(refreshTimer);
      refreshTimer = null;
    }
  }

  /* ------------------------------------------------------------------ startup */

  function start() {
    if (!core) {
      var missing = new Error('ff-core.js did not load');
      missing.code = 'UNAVAILABLE';
      return Promise.reject(missing);
    }
    if (!tracking) {
      return Promise.reject(core.codedError('UNAVAILABLE', 'vendor/plugin.js did not load (run `npm run sync`)'));
    }
    subscribeEvents();
    return core.runStartup({
      tracking: tracking,
      premise: premise || null,
      readOverridesFile: readOverridesFile,
      storage: {
        getItem: storageGet,
      },
      env: window.FF_ENV || {},
      now: function () {
        return new Date();
      },
      onStep: function (name) {
        app.step = name;
        renderStartup();
      },
    });
  }

  app.startup = start();
  renderStartup();
  renderEvents();

  app.startup.then(
    function (result) {
      app.status = 'done';
      app.result = result;
      if (result.started && result.startCalledAt) {
        storageSet(SESSION_KEY, JSON.stringify({ startedAt: result.startCalledAt, minutes: result.stopAfterElapsedMinutes }));
      }
      showBanner(result.warnings.length ? 'Started with warnings: ' + result.warnings.join('; ') : '', 'warn');
      renderStartup();
      if (result.state) renderState(result.state, '–');
      refreshAll();
      updateTimer();
    },
    function (error) {
      app.status = 'failed';
      app.error = { code: (error && error.code) || 'ERROR', message: (error && error.message) || String(error), step: error && error.step };
      showBanner('Automatic start failed' + (app.error.step ? ' at step "' + app.error.step + '"' : '') + ': ' + errorText(error), 'bad');
      renderStartup();
      if (tracking) refreshAll();
      updateTimer();
    },
  );

  document.addEventListener('visibilitychange', function () {
    updateTimer();
    if (document.visibilityState !== 'hidden' && app.status !== 'running') refreshAll();
  });

  var refreshButton = byId('refresh');
  if (refreshButton) {
    refreshButton.addEventListener('click', function () {
      refreshAll();
    });
  }
})();
