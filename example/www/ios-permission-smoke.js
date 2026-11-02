(async function () {
  const plugin = window.capacitorLocationTracking.LocationTracking;
  const location = document.createElement('p');
  const accuracy = document.createElement('p');
  const result = document.createElement('p');
  document.body.replaceChildren(location, accuracy, result);
  const refresh = async () => {
    location.textContent = 'Location permission: ' + (await plugin.checkPermissions()).location;
    accuracy.textContent = 'Provider accuracy: ' + (await plugin.getProviderState()).accuracy;
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
  button('Request Position', async () => {
    await plugin.ready({ config: { http: { autoSync: false } } });
    try { await plugin.getCurrentPosition({ persist: false, timeout: 1000 }); }
    catch (error) { result.textContent = 'Position error: ' + error.code; }
  });
})();
