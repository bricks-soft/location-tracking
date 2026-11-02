(async function () {
  const results = [];
  let plugin;
  const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  const assert = (value, name) => {
    if (!value) throw new Error(name);
    results.push(name);
    window.webkit.messageHandlers.stage1.postMessage({ phase: name, checks: results });
  };
  const rejects = async (name, code, args) => {
    try {
      await plugin[name](args || {});
    } catch (error) {
      assert(error.code === code, name + ' rejects ' + code);
      return;
    }
    throw new Error(name + ' unexpectedly resolved');
  };
  try {
    plugin = window.capacitorLocationTracking.LocationTracking;
    await rejects('getCount', 'NOT_READY');
    assert((await plugin.getState()).backend === 'corelocation', 'state before ready');
    assert((await plugin.getProviderState()).permission !== 'denied', 'simulator location permission');
    assert((await plugin.getDeviceInfo()).platform === 'ios', 'device platform');
    assert(typeof (await plugin.getSensors()).accelerometer === 'boolean', 'sensor availability');
    await plugin.log({ level: 'info', message: 'Simulator bridge smoke' });
    assert((await plugin.getLog()).log.includes('Simulator bridge smoke'), 'log before ready');
    await plugin.checkPermissions();
    await plugin.requestPermissions({ permissions: ['location'] });
    assert((await plugin.requestTemporaryFullAccuracy({ purposeKey: 'Tracking' })).accuracy === 'precise', 'temporary accuracy returns actual precision');
    await plugin.ready({ config: { http: { autoSync: false, url: null } } });
    await plugin.setConfig({ config: { persistence: { extras: { simulator: true } } } });
    await plugin.reset({ config: { http: { autoSync: false } } });
    let received = 0;
    const listener = await plugin.addListener('location', () => received++);
    const lastRecordAt = (await plugin.getState()).lastRecordAt;
    const id = (await plugin.insertLocation({ location: { coords: { latitude: 24.7136, longitude: 46.6753, accuracy: 5.2 } } })).uuid;
    assert((await plugin.getCount()).count === 1, 'queue count');
    assert((await plugin.getLocations()).locations[0].uuid === id, 'insert UUID retained');
    await pause(100);
    assert(received === 0, 'insertion does not emit a tracking event');
    assert((await plugin.getState()).lastRecordAt === lastRecordAt, 'insertion does not alter tracking state');
    assert((await plugin.destroyLocation({ uuid: id })).deleted, 'delete location');
    await plugin.destroyLocations();
    await plugin.setOdometer({ odometer: 123.5 });
    assert((await plugin.getOdometer()).odometer === 123.5, 'odometer set');
    assert((await plugin.resetOdometer()).odometer === 0, 'odometer reset');
    await rejects('sync', 'NO_URL');
    for (const method of ['start', 'startGeofences', 'changePace', 'getHeartbeatStatus', 'addGeofence', 'addGeofences', 'removeGeofence', 'removeGeofences', 'getGeofences', 'getGeofence', 'geofenceExists']) {
      await rejects(method, 'UNAVAILABLE');
    }
    await plugin.isPowerSaveMode();
    await plugin.getBatteryOptimizationStatus();
    await plugin.getPowerManagerInfo();
    assert(!(await plugin.openBatteryOptimizationSettings()).opened, 'battery settings unavailable');
    assert(!(await plugin.openPowerManagerSettings()).opened, 'power manager unavailable');
    const fix = await plugin.getCurrentPosition({ samples: 1, persist: true, timeout: 30000 });
    assert(fix.coords.accuracy >= 0, 'current Core Location position');
    await pause(100);
    assert(received === 1, 'location listener receives current position');
    let callbacks = 0;
    let watchError;
    const watchID = await plugin.watchPosition({ interval: 0 }, (location, error) => {
      if (error) watchError = error;
      else if (location && location.event === 'watch_position') callbacks++;
    });
    for (let n = 0; n < 100 && callbacks === 0 && !watchError; n++) await pause(100);
    assert(!watchError && callbacks > 0, 'watch callback through Capacitor');
    await plugin.stop();
    const atStop = callbacks;
    for (let n = 0; n < 200 && callbacks <= atStop && !watchError; n++) await pause(100);
    assert(!watchError && callbacks > atStop, 'watch survives stop');
    await plugin.clearWatch({ id: watchID });
    const cleared = callbacks;
    await pause(2500);
    assert(callbacks === cleared, 'clearWatch ends callbacks');
    await listener.remove();
    await plugin.removeAllListeners();
    await rejects('uploadLog', 'INVALID_ARGUMENT', { url: 'file:///logs' });
    await rejects('emailLog', 'UNAVAILABLE', { email: 'simulator@example.invalid' });
    await plugin.destroyLog();
    assert((await plugin.getLog()).log === '', 'destroy logs');
    window.webkit.messageHandlers.stage1.postMessage({ success: true, checks: results });
  } catch (error) {
    window.webkit.messageHandlers.stage1.postMessage({ success: false, checks: results, error: String(error), code: error.code || null });
  }
})();
