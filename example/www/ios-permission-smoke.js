(async function () {
  const plugin = window.capacitorLocationTracking.LocationTracking;
  const location = document.createElement('p');
  const accuracy = document.createElement('p');
  const result = document.createElement('p');
  const route = document.createElement('p');
  document.body.replaceChildren(location, accuracy, result, route);
  const refresh = async () => {
    location.textContent = 'Location permission: ' + (await plugin.checkPermissions()).location;
    const provider = await plugin.getProviderState();
    accuracy.textContent = 'Provider accuracy: ' + provider.accuracy;
    if ((await plugin.getState()).enabled) route.textContent = 'Route: ' + provider.accuracy + ' enabled';
  };
  const button = (label, action) => {
    const element = document.createElement('button');
    element.textContent = label;
    element.style.cssText = 'display:block;padding:20px;margin:20px;font-size:20px';
    element.onclick = () => action().catch((error) => { result.textContent = String(error); });
    document.body.appendChild(element);
  };
  await refresh();
  button('Request Location', async () => {
    result.textContent = 'Location request started';
    await plugin.requestPermissions({ permissions: ['location'] });
    await refresh();
  });
  button('Request Precision', async () => {
    result.textContent = 'Precision request started';
    await plugin.requestTemporaryFullAccuracy({ purposeKey: 'Tracking' });
    await refresh();
  });
  button('Start Route', async () => {
    await plugin.ready({ config: { http: { autoSync: false }, heartbeat: { enabled: false } } });
    try { await plugin.start(); await refresh(); }
    catch (error) { result.textContent = 'Route error: ' + error.code; }
  });
  button('Stop Route', async () => {
    await plugin.stop();
    route.textContent = 'Route: stopped';
  });
  button('Request Position', async () => {
    await plugin.ready({ config: { http: { autoSync: false } } });
    try { await plugin.getCurrentPosition({ persist: false, timeout: 1000 }); }
    catch (error) { result.textContent = 'Position error: ' + error.code; }
  });
})();
