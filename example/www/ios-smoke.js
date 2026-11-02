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
    assert((await plugin.start()).enabled, 'route starts');
    await plugin.changePace({ isMoving: true });
    assert((await plugin.getState()).isMoving, 'route pace changes');
    assert((await plugin.getHeartbeatStatus()).strategy === 'timer', 'heartbeat timer armed');
    assert(!(await plugin.stop()).enabled, 'route stops');
    await plugin.destroyLocations();
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
    if (parseInt((await plugin.getDeviceInfo()).osVersion, 10) >= 17) {
      await plugin.removeGeofences();
      const changes = [];
      const changeListener = await plugin.addListener('geofenceschange', (event) => changes.push(event));
      await plugin.addGeofence({ geofence: { identifier: 'smoke-circle', latitude: 24.7136, longitude: 46.6753, radius: 150, extras: { kind: 'circle' } } });
      await plugin.addGeofences({ geofences: [{ identifier: 'smoke-polygon', vertices: [[24.712, 46.674], [24.712, 46.677], [24.715, 46.677], [24.715, 46.674]], notifyOnDwell: true, loiteringDelay: 5000 }] });
      await plugin.addGeofences({ geofences: [] });
      const stored = (await plugin.getGeofences()).geofences;
      assert(stored.map((g) => g.identifier).join() === 'smoke-circle,smoke-polygon', 'geofences stored in identifier order');
      const polygon = stored.find((g) => g.identifier === 'smoke-polygon');
      assert(polygon.vertices.length === 4 && polygon.radius >= 50 && polygon.notifyOnDwell === true, 'polygon stores its enclosing circle');
      assert((await plugin.getGeofence({ identifier: 'smoke-circle' })).geofence.extras.kind === 'circle', 'get geofence');
      assert((await plugin.getGeofence({ identifier: 'missing' })).geofence === null, 'unknown geofence is null');
      assert((await plugin.geofenceExists({ identifier: 'smoke-polygon' })).exists, 'geofence exists');
      await rejects('addGeofence', 'INVALID_ARGUMENT', { geofence: { identifier: 'bad', latitude: 95, longitude: 0, radius: 10 } });
      await rejects('addGeofences', 'TOO_MANY_GEOFENCES', { geofences: Array.from({ length: 19 }, (_, i) => ({ identifier: 'extra' + i, latitude: 24.7, longitude: 46.6, radius: 100 })) });
      await plugin.removeGeofence({ identifier: 'smoke-circle' });
      await plugin.removeGeofences({ identifiers: [] });
      assert((await plugin.getGeofences()).geofences.length === 1, 'remove one geofence');
      assert((await plugin.startGeofences()).trackingMode === 'geofences', 'geofence mode starts');
      assert(!(await plugin.stop()).enabled, 'geofence mode stops');
      await plugin.removeGeofences();
      assert(!(await plugin.geofenceExists({ identifier: 'smoke-polygon' })).exists, 'remove all geofences');
      assert(changes.length === 4 && changes[3].off[0] === 'smoke-polygon', 'geofenceschange events');
      await changeListener.remove();
    } else {
      for (const method of ['startGeofences', 'addGeofence', 'addGeofences', 'removeGeofence', 'removeGeofences', 'getGeofences', 'getGeofence', 'geofenceExists']) {
        await rejects(method, 'UNAVAILABLE');
      }
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
