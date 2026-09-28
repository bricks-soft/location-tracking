/*
 * Field-force example: the startup logic without any browser or UI code (docs/e2e/architecture.md §10).
 *
 * Loaded two ways:
 *   - in the app, by www/index.html before app.js: it defines the global `FFCore`;
 *   - in Node, by the tests in examples/field-force/test/ through require(): module.exports
 *     (run: node --test "examples/field-force/test/*.test.js").
 * Everything that touches the plugins, the file system or localStorage is passed in by the caller (`deps` of
 * runStartup), so Node tests can run the whole startup with fake plugins.
 *
 * The syntax stays at ES2017 level (no optional chaining, no `??`, no object spread), like example/www/app.js, so
 * the page also runs on old Android System WebView versions.
 */
(function (root, factory) {
  'use strict';
  var core = factory();
  if (typeof module === 'object' && module && module.exports) module.exports = core;
  else root.FFCore = core;
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  var APP_ID = 'com.brickssoft.fieldforce.example';
  /** Local clock time of the daily automatic stop. */
  var DEFAULT_STOP_AT = '02:00';
  var DEFAULT_BACKEND_URL = 'http://10.0.2.2:8787';
  /** Test-mode file written by the e2e kit before it launches the app (architecture §6 addendum). */
  var OVERRIDES_FILE_PATH = '/data/data/' + APP_ID + '/files/e2e/ff-overrides.json';
  /** localStorage key with the same content (FIELD_FORCE_OVERRIDES_KEY in testing/e2e-kit/src/fixtures.ts). */
  var OVERRIDES_STORAGE_KEY = 'ff.e2e.overrides';
  var WORKER_ID = 'field-force-example';
  var MINUTE_MS = 60000;

  /** Production values that the test overrides replace. */
  var PRODUCTION = {
    heartbeat: { minInterval: 180, maxInterval: 300 },
    syncInterval: 300,
  };

  /** The DeviceInfo fields sent to the back office in every request body (`http.params.device`). */
  var DEVICE_FIELDS = [
    'manufacturer',
    'model',
    'brand',
    'osVersion',
    'sdkInt',
    'pluginVersion',
    'backend',
    'gmsAvailable',
    'hmsAvailable',
  ];

  /* ------------------------------------------------------------------ small helpers */

  function isPlainObject(value) {
    if (value === null || typeof value !== 'object' || Array.isArray(value)) return false;
    var proto = Object.getPrototypeOf(value);
    return proto === Object.prototype || proto === null;
  }

  function isFiniteNumber(value) {
    return typeof value === 'number' && isFinite(value);
  }

  /** True for Error objects, also those created in another JavaScript realm (instanceof would say false). */
  function isError(value) {
    return value instanceof Error || Object.prototype.toString.call(value) === '[object Error]';
  }

  /** Error with a `code`, like the plugin's rejections. */
  function codedError(code, message) {
    var error = new Error(message);
    error.code = code;
    return error;
  }

  function invalidOverrides(message) {
    return codedError('INVALID_OVERRIDES', message);
  }

  /** Deep copy of JSON-like data (plain objects and arrays; other values are kept as they are). */
  function clone(value) {
    if (Array.isArray(value)) return value.map(clone);
    if (isPlainObject(value)) {
      var out = {};
      Object.keys(value).forEach(function (key) {
        out[key] = clone(value[key]);
      });
      return out;
    }
    return value;
  }

  /**
   * Returns a new object: [patch] merged into [base]. Plain objects are merged key by key at every depth; every
   * other value of [patch] (arrays, null, numbers, strings, booleans) replaces the value of [base]. Keys whose patch
   * value is `undefined` are skipped. Neither argument is changed.
   */
  function deepMerge(base, patch) {
    var out = clone(isPlainObject(base) ? base : {});
    if (!isPlainObject(patch)) return out;
    Object.keys(patch).forEach(function (key) {
      var value = patch[key];
      if (value === undefined) return;
      out[key] = isPlainObject(value) && isPlainObject(out[key]) ? deepMerge(out[key], value) : clone(value);
    });
    return out;
  }

  /* ------------------------------------------------------------------ stop time */

  /** Parses 'HH:MM' or 'H:MM' (00:00 to 23:59) into {hours, minutes}; throws INVALID_OVERRIDES otherwise. */
  function parseStopAt(text) {
    var match = typeof text === 'string' ? /^([01]?\d|2[0-3]):([0-5]\d)$/.exec(text.trim()) : null;
    if (!match) throw invalidOverrides("stopAt must be a local time 'HH:MM' (00:00 to 23:59), got " + JSON.stringify(text));
    return { hours: Number(match[1]), minutes: Number(match[2]) };
  }

  /**
   * The next local date and time at which the clock shows [stopAt] ('HH:MM'): later today, or tomorrow when that time
   * of day has already come or passed today. Uses the device's local time zone. On a day where a daylight-saving
   * change skips that time, the result is the time the JavaScript Date moves it to (for 02:00 on a spring-forward
   * night in Europe: 03:00 summer time).
   */
  function nextOccurrence(stopAt, now) {
    var parsed = parseStopAt(stopAt);
    var nowDate = now instanceof Date ? now : new Date(now);
    var target = new Date(nowDate.getTime());
    target.setHours(parsed.hours, parsed.minutes, 0, 0);
    if (target.getTime() <= nowDate.getTime()) {
      target = new Date(nowDate.getFullYear(), nowDate.getMonth(), nowDate.getDate() + 1, parsed.hours, parsed.minutes, 0, 0);
    }
    return target;
  }

  /**
   * Whole minutes from [now] until the next local [stopAt] (architecture §10 step 3):
   * ceil((next HH:MM - now) / 60000). Never 0: at exactly HH:MM the next occurrence is tomorrow (1440).
   */
  function minutesUntil(stopAt, now) {
    var nowDate = now instanceof Date ? now : new Date(now === undefined ? Date.now() : now);
    var target = nextOccurrence(stopAt, nowDate);
    return Math.max(1, Math.ceil((target.getTime() - nowDate.getTime()) / MINUTE_MS));
  }

  /**
   * Step 3: a running session keeps its `stopAfterElapsedMinutes` (the engine measures it from the session start, so a
   * new value would move the stop); otherwise the minutes until the next [stopAt].
   */
  function stopMinutesFor(state, stopAt, now) {
    var geolocation = state && state.enabled && state.config && state.config.geolocation;
    if (geolocation && isFiniteNumber(geolocation.stopAfterElapsedMinutes) && geolocation.stopAfterElapsedMinutes >= 0) {
      return { minutes: geolocation.stopAfterElapsedMinutes, kept: true };
    }
    return { minutes: minutesUntil(stopAt || DEFAULT_STOP_AT, now), kept: false };
  }

  /**
   * For the status screen: the stop time of a session that started at [startedAtMs] with [minutes], relative to
   * [nowMs]. Returns null when the stop is unknown (no start time, 0 = no automatic stop).
   */
  function describeStop(startedAtMs, minutes, nowMs) {
    if (!isFiniteNumber(startedAtMs) || !isFiniteNumber(minutes) || minutes <= 0) return null;
    var stopAtMs = startedAtMs + minutes * MINUTE_MS;
    return {
      stopAtMs: stopAtMs,
      minutesLeft: Math.max(0, Math.ceil((stopAtMs - nowMs) / MINUTE_MS)),
      passed: stopAtMs <= nowMs,
    };
  }

  /* ------------------------------------------------------------------ overrides */

  /** Text of a test-mode file or of localStorage -> a plain object, or null (empty, invalid JSON, not an object). */
  function parseOverridesText(text) {
    if (typeof text !== 'string' || text.trim() === '') return null;
    try {
      var value = JSON.parse(text);
      return isPlainObject(value) ? value : null;
    } catch (e) {
      return null;
    }
  }

  function isValidPremise(p) {
    return (
      isPlainObject(p) &&
      typeof p.id === 'string' &&
      p.id !== '' &&
      isFiniteNumber(p.latitude) &&
      isFiniteNumber(p.longitude) &&
      isFiniteNumber(p.radius) &&
      p.radius > 0 &&
      (p.name === undefined || typeof p.name === 'string')
    );
  }

  /**
   * Checks a `FieldForceOverrides` object (testing/e2e-kit/src/fixtures.ts) and returns a clean copy with defaults:
   * `{stopAt?, heartbeat?, syncInterval?, backendUrl?, premise (object or null), autoStart (boolean), configPatch?}`.
   * Unknown keys are ignored. A known key with a wrong type throws INVALID_OVERRIDES (a test bug must be visible, not
   * silently replaced by production values). `null` counts as "not set".
   */
  function normalizeOverrides(raw) {
    var src = raw === undefined || raw === null ? {} : raw;
    if (!isPlainObject(src)) throw invalidOverrides('overrides must be a JSON object');
    var out = { premise: null, autoStart: true };

    if (src.stopAt !== undefined && src.stopAt !== null) {
      parseStopAt(src.stopAt);
      out.stopAt = src.stopAt.trim();
    }
    if (src.heartbeat !== undefined && src.heartbeat !== null) {
      var hb = src.heartbeat;
      var hbOk =
        isPlainObject(hb) &&
        (hb.minInterval === undefined || isFiniteNumber(hb.minInterval)) &&
        (hb.maxInterval === undefined || isFiniteNumber(hb.maxInterval));
      if (!hbOk) throw invalidOverrides('heartbeat must be {minInterval: number, maxInterval: number}');
      out.heartbeat = {};
      if (hb.minInterval !== undefined) out.heartbeat.minInterval = hb.minInterval;
      if (hb.maxInterval !== undefined) out.heartbeat.maxInterval = hb.maxInterval;
    }
    if (src.syncInterval !== undefined && src.syncInterval !== null) {
      if (!isFiniteNumber(src.syncInterval) || src.syncInterval < 0) {
        throw invalidOverrides('syncInterval must be a number of seconds >= 0');
      }
      out.syncInterval = src.syncInterval;
    }
    if (src.backendUrl !== undefined && src.backendUrl !== null && src.backendUrl !== '') {
      if (typeof src.backendUrl !== 'string' || !/^https?:\/\/\S+$/.test(src.backendUrl.trim())) {
        throw invalidOverrides('backendUrl must be an http(s) URL');
      }
      out.backendUrl = trimSlashes(src.backendUrl.trim());
    }
    if (src.premise !== undefined && src.premise !== null) {
      if (!isValidPremise(src.premise)) {
        throw invalidOverrides('premise must be {id: string, name?: string, latitude, longitude, radius > 0}');
      }
      out.premise = clone(src.premise);
    }
    if (src.autoStart !== undefined && src.autoStart !== null) {
      if (typeof src.autoStart !== 'boolean') throw invalidOverrides('autoStart must be true or false');
      out.autoStart = src.autoStart;
    }
    if (src.configPatch !== undefined && src.configPatch !== null) {
      if (!isPlainObject(src.configPatch)) throw invalidOverrides('configPatch must be a JSON object');
      out.configPatch = clone(src.configPatch);
    }
    return out;
  }

  function trimSlashes(url) {
    return url.replace(/\/+$/, '');
  }

  /** `overrides.backendUrl`, else `FF_ENV.backendUrl`, else the AVD host address. No trailing slash. */
  function resolveBackendUrl(overrides, env) {
    if (overrides && overrides.backendUrl) return trimSlashes(overrides.backendUrl);
    if (env && typeof env.backendUrl === 'string' && env.backendUrl.trim() !== '') return trimSlashes(env.backendUrl.trim());
    return DEFAULT_BACKEND_URL;
  }

  /* ------------------------------------------------------------------ preset */

  /** The DeviceInfo fields of `http.params.device`; a missing field is sent as null. */
  function deviceParams(deviceInfo) {
    var out = {};
    DEVICE_FIELDS.forEach(function (key) {
      out[key] = deviceInfo && deviceInfo[key] !== undefined ? deviceInfo[key] : null;
    });
    return out;
  }

  /**
   * The plugin config of the field-force app (architecture §10 "Preset"), with the test overrides applied and
   * `overrides.configPatch` deep-merged last.
   * options: {backendUrl, stopAfterElapsedMinutes, deviceInfo, overrides (normalized)}.
   */
  function buildConfig(options) {
    var overrides = options.overrides || {};
    var backendUrl = options.backendUrl;
    var preset = {
      geolocation: {
        desiredAccuracy: 'high',
        distanceFilter: 20,
        stationaryRadius: 50,
        stopTimeout: 5,
        stopAfterElapsedMinutes: options.stopAfterElapsedMinutes,
        filter: { trackingAccuracyThreshold: 50 },
      },
      heartbeat: Object.assign({ enabled: true }, PRODUCTION.heartbeat, overrides.heartbeat || {}),
      http: {
        url: backendUrl + '/locations',
        autoSync: true,
        syncInterval: overrides.syncInterval !== undefined ? overrides.syncInterval : PRODUCTION.syncInterval,
        batchSync: true,
        maxBatchSize: 100,
        params: {
          worker_id: WORKER_ID,
          device: deviceParams(options.deviceInfo),
        },
        authorization: {
          strategy: 'JWT',
          accessToken: 'ff-initial',
          refreshToken: 'ff-refresh',
          refreshUrl: backendUrl + '/auth/refresh',
          refreshPayload: { refresh_token: '{refreshToken}' },
        },
      },
      app: { stopOnTerminate: false, startOnBoot: true },
      notification: { title: 'Field Force', text: 'Shift tracking is on' },
      logger: { logLevel: 'debug' },
      locationProvider: 'auto',
    };
    return overrides.configPatch ? deepMerge(preset, overrides.configPatch) : preset;
  }

  /** PermissionStatus -> names whose state is not 'granted'. */
  function missingPermissions(status) {
    if (!isPlainObject(status)) return [];
    return Object.keys(status).filter(function (key) {
      return status[key] !== 'granted';
    });
  }

  /* ------------------------------------------------------------------ startup */

  /**
   * Reads the overrides: the test-mode file first, else localStorage (architecture §10 step 1).
   * A missing or unreadable file, or one whose content is not a JSON object, counts as "no file". A localStorage
   * value that is not a JSON object rejects (the contract's `JSON.parse(localStorage[key] || '{}')` would throw).
   * `deps.readOverridesFile()` resolves with the text, or null when there is no file (404); a rejection (timeout,
   * fetch error) also counts as "no file" but is reported in `warning`, so a test can see why its overrides were not
   * applied.
   * Resolves with {source: 'file' | 'localStorage' | 'none', overrides (normalized), warning (string or null)}.
   */
  function readOverrides(deps) {
    var warning = null;
    return Promise.resolve()
      .then(function () {
        return typeof deps.readOverridesFile === 'function' ? deps.readOverridesFile() : null;
      })
      .catch(function (error) {
        warning = 'overrides file not read: ' + ((error && error.message) || String(error));
        return null;
      })
      .then(function (fileText) {
        var fromFile = parseOverridesText(fileText);
        if (fromFile) return { source: 'file', raw: fromFile };
        if (typeof fileText === 'string' && fileText.trim() !== '') {
          warning = 'overrides file ignored: its content is not a JSON object';
        }
        var stored = null;
        try {
          stored = deps.storage ? deps.storage.getItem(OVERRIDES_STORAGE_KEY) : null;
        } catch (e) {
          stored = null;
        }
        if (stored === null || stored === undefined || stored === '') return { source: 'none', raw: {} };
        var fromStorage = parseOverridesText(stored);
        if (!fromStorage) throw invalidOverrides('localStorage["' + OVERRIDES_STORAGE_KEY + '"] is not a JSON object');
        return { source: 'localStorage', raw: fromStorage };
      })
      .then(function (found) {
        return { source: found.source, overrides: normalizeOverrides(found.raw), warning: warning };
      });
  }

  /** True when `overrides.configPatch` sets `geolocation.stopAfterElapsedMinutes` itself. */
  function patchSetsStopMinutes(overrides) {
    var patch = overrides && overrides.configPatch;
    return !!(patch && isPlainObject(patch.geolocation) && patch.geolocation.stopAfterElapsedMinutes !== undefined);
  }

  /**
   * The auto start (architecture §10 steps 1-8). Every page load runs it once, and a return to the foreground with
   * tracking off runs it again (createAutoStarter).
   *
   * deps:
   *   tracking            LocationTracking plugin (getState, getDeviceInfo, ready, checkPermissions,
   *                       requestPermissions, setConfig, start)
   *   premise             PremiseMonitor plugin (startMonitoring), or null when it is not installed
   *   readOverridesFile() -> Promise<string | null>: text of files/e2e/ff-overrides.json, null = no file; a
   *                       rejection = no file plus a warning
   *   storage             {getItem(key)} (localStorage), or null
   *   env                 window.FF_ENV ({backendUrl})
   *   now()               -> Date (tests pass a fixed clock)
   *   onStep(name, info)  optional progress callback for the status screen
   *
   * Resolves with {state, stopAfterElapsedMinutes, config, deviceInfo} plus diagnostics (overrides, overridesSource,
   * backendUrl, stopMinutesKept, stopMinutesRecomputed, started, startCalledAt, permissions, permissionsRequested,
   * premiseStatus, warnings).
   * Rejects with the first failing step's error; `error.step` names the step, `error.code` is kept and
   * `error.overrides` holds the normalized overrides when they were read before the failure.
   */
  function runStartup(deps) {
    var tracking = deps.tracking;
    var now = typeof deps.now === 'function' ? deps.now : function () {
      return new Date();
    };
    var onStep = typeof deps.onStep === 'function' ? deps.onStep : function () {};
    var result = {
      warnings: [],
      started: false,
      startCalledAt: null,
      stopMinutesRecomputed: false,
      permissionsRequested: false,
      premiseStatus: null,
    };

    function step(name, fn) {
      return function (input) {
        onStep(name, null);
        return Promise.resolve()
          .then(function () {
            return fn(input);
          })
          .catch(function (error) {
            // Plugin rejections are Error objects with a `code`; anything else is wrapped so `step` can be attached.
            var e = isError(error)
              ? error
              : codedError((error && error.code) || 'INTERNAL', String((error && error.message) || error));
            if (!e.step) e.step = name;
            // the overrides of this run (when the first step read them), so a resume after a failed run still
            // knows that a test set autoStart false
            if (result.overrides && !e.overrides) e.overrides = result.overrides;
            throw e;
          });
      };
    }

    return Promise.resolve()
      .then(
        step('overrides', function () {
          return readOverrides(deps).then(function (found) {
            result.overrides = found.overrides;
            result.overridesSource = found.source;
            result.backendUrl = resolveBackendUrl(found.overrides, deps.env);
            if (found.warning) result.warnings.push(found.warning);
          });
        }),
      )
      .then(
        step('getState', function () {
          return tracking.getState();
        }),
      )
      .then(
        step('stopTime', function (stateBefore) {
          var stop = stopMinutesFor(stateBefore, result.overrides.stopAt || DEFAULT_STOP_AT, now());
          result.stateBeforeReady = stateBefore;
          result.stopAfterElapsedMinutes = stop.minutes;
          result.stopMinutesKept = stop.kept;
        }),
      )
      .then(
        step('getDeviceInfo', function () {
          return tracking.getDeviceInfo().then(function (info) {
            result.deviceInfo = info;
          });
        }),
      )
      .then(
        step('ready', function () {
          result.config = buildConfig({
            backendUrl: result.backendUrl,
            stopAfterElapsedMinutes: result.stopAfterElapsedMinutes,
            deviceInfo: result.deviceInfo,
            overrides: result.overrides,
          });
          // configPatch may replace the computed value; report what the plugin was given.
          var geo = result.config.geolocation;
          if (geo && isFiniteNumber(geo.stopAfterElapsedMinutes)) result.stopAfterElapsedMinutes = geo.stopAfterElapsedMinutes;
          return tracking.ready({ config: result.config, reset: true }).then(function (state) {
            result.state = state;
          });
        }),
      )
      .then(
        step('permissions', function () {
          // A failure here does not stop the startup: start() reports PERMISSION_DENIED itself if it matters.
          return Promise.resolve()
            .then(function () {
              return tracking.checkPermissions();
            })
            .then(function (status) {
              result.permissions = status;
              if (missingPermissions(status).length === 0) return null;
              result.permissionsRequested = true;
              return tracking.requestPermissions().then(function (after) {
                result.permissions = after;
              });
            })
            .catch(function (error) {
              result.warnings.push('permissions: ' + ((error && error.message) || String(error)));
            });
        }),
      )
      .then(
        step('start', function () {
          if (result.overrides.autoStart === false || (result.state && result.state.enabled)) return null;
          // start() begins a new session, and the engine counts stopAfterElapsedMinutes from it, not from ready().
          // The permission dialogs of step 6 can take minutes on a real device, and a session that was running
          // before ready() may have ended in it (its kept minutes belong to the old start), so the minutes are
          // computed again here; if they changed, the new value is applied (setConfig merges) before start(), and
          // the stop stays at HH:MM.
          result.stopMinutesKept = false;
          var fresh = null;
          if (!patchSetsStopMinutes(result.overrides)) {
            fresh = minutesUntil(result.overrides.stopAt || DEFAULT_STOP_AT, now());
            if (fresh === result.stopAfterElapsedMinutes) fresh = null;
          }
          var applied = fresh === null
            ? Promise.resolve()
            : tracking.setConfig({ config: { geolocation: { stopAfterElapsedMinutes: fresh } } }).then(function () {
              result.stopAfterElapsedMinutes = fresh;
              // a copy: the object given to ready() stays as it was sent
              result.config = deepMerge(result.config, { geolocation: { stopAfterElapsedMinutes: fresh } });
              result.stopMinutesRecomputed = true;
            });
          return applied.then(function () {
            result.startCalledAt = now().getTime();
            return tracking.start().then(function (state) {
              result.state = state;
              result.started = true;
            });
          });
        }),
      )
      .then(
        step('premise', function () {
          var premise = result.overrides.premise;
          if (!premise) return null;
          if (!deps.premise || typeof deps.premise.startMonitoring !== 'function') {
            throw codedError('UNAVAILABLE', 'the PremiseMonitor plugin is not available');
          }
          return deps.premise
            .startMonitoring({ premise: premise, auditUrl: result.backendUrl + '/premise-audit' })
            .then(function (status) {
              result.premiseStatus = status || null;
            });
        }),
      )
      .then(function () {
        onStep('done', null);
        return result;
      });
  }

  /* ------------------------------------------------------------------ auto start on load and on resume */

  /**
   * Runs the startup on page load and again when the app comes back to the foreground with tracking off.
   *
   * Why: the startup runs on page load only, but the app's process and activity often survive the night (the app
   * is never closed). After the 02:00 stop the worker brings the app to the front the next morning without a page
   * load, so tracking would stay off. The field-force app has no stop button, so "not enabled on resume" means the
   * day's session ended (02:00 stop, permission loss, a failed service start) and the startup runs again, with
   * the minutes until the next 02:00 computed again.
   *
   * options:
   *   start(reason)   -> Promise: one startup run ('load' | 'resume'), e.g. runStartup(deps)
   *   getState()      -> Promise<State>: LocationTracking.getState()
   *   autoStartOff()  -> boolean: true when the latest overrides say autoStart false (then a resume never runs the
   *                      startup again: its ready({reset: true}) would replace a config a test has set)
   *   onBegin(reason, promise)  called when a run begins (synchronously)
   *   onResume(entry)           called with every resume check's outcome
   *
   * Returns {load(), resume(trigger), count, reason, running()}. resume() resolves with the outcome:
   *   'started'       tracking was off: a new startup run began
   *   'enabled'       tracking is on: nothing to do
   *   'busy'          a startup run (or another resume check) is in progress: nothing to do
   *   'autostart_off' the overrides say autoStart false: nothing to do
   *   'error'         getState() failed: nothing to do
   */
  function createAutoStarter(options) {
    var current = null;
    var checking = null;
    var starter = {
      /** number of startup runs begun */
      count: 0,
      /** reason of the latest run: 'load' | 'resume' */
      reason: null,
      running: function () {
        return current !== null;
      },
      load: function () {
        return begin('load');
      },
      resume: resume,
    };

    function begin(reason) {
      starter.count += 1;
      starter.reason = reason;
      var run = Promise.resolve().then(function () {
        return options.start(reason);
      });
      current = run;
      var clear = function () {
        if (current === run) current = null;
      };
      run.then(clear, clear);
      if (typeof options.onBegin === 'function') options.onBegin(reason, run);
      return run;
    }

    function report(trigger, outcome) {
      if (typeof options.onResume === 'function') {
        options.onResume({ at: Date.now(), trigger: trigger, outcome: outcome });
      }
      return outcome;
    }

    function resume(trigger) {
      if (current) return Promise.resolve(report(trigger, 'busy'));
      // A second trigger of the same foregrounding (document 'resume' and 'visibilitychange') joins the check.
      if (checking) return checking.then(function () {
        return report(trigger, 'busy');
      });
      if (typeof options.autoStartOff === 'function' && options.autoStartOff()) {
        return Promise.resolve(report(trigger, 'autostart_off'));
      }
      var check = Promise.resolve()
        .then(function () {
          return options.getState();
        })
        .then(
          function (state) {
            if (state && state.enabled) return 'enabled';
            if (current) return 'busy';
            begin('resume');
            return 'started';
          },
          function () {
            return 'error';
          },
        )
        .then(function (outcome) {
          checking = null;
          return report(trigger, outcome);
        });
      checking = check;
      return check;
    }

    return starter;
  }

  return {
    APP_ID: APP_ID,
    DEFAULT_STOP_AT: DEFAULT_STOP_AT,
    DEFAULT_BACKEND_URL: DEFAULT_BACKEND_URL,
    OVERRIDES_FILE_PATH: OVERRIDES_FILE_PATH,
    OVERRIDES_STORAGE_KEY: OVERRIDES_STORAGE_KEY,
    PRODUCTION: PRODUCTION,
    DEVICE_FIELDS: DEVICE_FIELDS,
    isPlainObject: isPlainObject,
    deepMerge: deepMerge,
    parseStopAt: parseStopAt,
    nextOccurrence: nextOccurrence,
    minutesUntil: minutesUntil,
    stopMinutesFor: stopMinutesFor,
    describeStop: describeStop,
    parseOverridesText: parseOverridesText,
    normalizeOverrides: normalizeOverrides,
    resolveBackendUrl: resolveBackendUrl,
    deviceParams: deviceParams,
    buildConfig: buildConfig,
    missingPermissions: missingPermissions,
    codedError: codedError,
    readOverrides: readOverrides,
    runStartup: runStartup,
    createAutoStarter: createAutoStarter,
  };
});
