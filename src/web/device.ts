// Owned by Unit 1 (TS wrapper + web).
import type { DeviceInfo } from '../definitions';

/** Keep in sync with `package.json` (a test checks this) and `PLUGIN_VERSION` in `android/build.gradle`. */
export const PLUGIN_VERSION = '8.0.0';

/** Tokens after `Android x` that do not name a device ('K' is Chrome's reduced user agent). */
const NOT_A_MODEL = ['K', 'U', 'wv', 'Mobile', 'Tablet', 'HarmonyOS'];

interface OsInfo {
  osVersion: string;
  manufacturer: string;
  model: string | undefined;
}

/** The device model among the `;`-separated tokens that follow `Android x` in the user agent's comment. */
function androidModel(tokens: string[]): string | undefined {
  for (const token of tokens) {
    const model = token.replace(/\s*Build\/.*$/, '').trim();
    const skip =
      !model ||
      NOT_A_MODEL.indexOf(model) >= 0 ||
      /^[a-z]{2}(?:[-_][a-z]{2})?$/i.test(model) || // locale, e.g. en-us
      /^(?:rv:|HMSCore)/.test(model);
    if (!skip) {
      return model;
    }
  }
  return undefined;
}

function parseOs(ua: string): OsInfo {
  let match = /\(([^)]*?)Android\s*([\d.]*)([^)]*)\)/.exec(ua);
  if (match) {
    return {
      osVersion: match[2] ? `Android ${match[2]}` : 'Android',
      manufacturer: 'unknown',
      model: androidModel(match[3].split(';')),
    };
  }
  match = /(iPhone|iPad|iPod).*?OS (\d+(?:_\d+)*)/.exec(ua);
  if (match) {
    return { osVersion: `iOS ${match[2].replace(/_/g, '.')}`, manufacturer: 'Apple', model: match[1] };
  }
  match = /Mac OS X (\d+(?:[_.]\d+)*)/.exec(ua);
  if (match) {
    return { osVersion: `macOS ${match[1].replace(/_/g, '.')}`, manufacturer: 'Apple', model: 'Macintosh' };
  }
  match = /Windows NT (\d+(?:\.\d+)*)/.exec(ua);
  if (match) {
    return { osVersion: `Windows ${match[1]}`, manufacturer: 'unknown', model: undefined };
  }
  match = /CrOS \S+ (\d+(?:\.\d+)*)/.exec(ua);
  if (match) {
    return { osVersion: `ChromeOS ${match[1]}`, manufacturer: 'unknown', model: undefined };
  }
  if (/Linux/.test(ua)) {
    return { osVersion: 'Linux', manufacturer: 'unknown', model: undefined };
  }
  return { osVersion: 'unknown', manufacturer: 'unknown', model: undefined };
}

/** Browser name and major version, e.g. `Chrome 120`. Order matters: many browsers also claim Chrome or Safari. */
function parseBrowser(ua: string): string {
  const browsers: [string, RegExp][] = [
    ['Edge', /Edg(?:e|A|iOS)?\/(\d+)/],
    ['Opera', /(?:OPR|Opera)\/(\d+)/],
    ['Samsung Internet', /SamsungBrowser\/(\d+)/],
    ['Huawei Browser', /HuaweiBrowser\/(\d+)/],
    ['Firefox', /(?:Firefox|FxiOS)\/(\d+)/],
    ['Chrome', /(?:Chrome|CriOS)\/(\d+)/],
    ['Safari', /Version\/(\d+)[\d.]*.*Safari\//],
    ['Node.js', /Node\.js\/(\d+)/],
  ];
  for (const [name, pattern] of browsers) {
    const match = pattern.exec(ua);
    if (match) {
      return `${name} ${match[1]}`;
    }
  }
  return 'unknown';
}

/**
 * Best-effort `DeviceInfo` for a browser, parsed from `navigator.userAgent`.
 * `brand` is the browser, `model` the device when the user agent names one (else `navigator.platform`),
 * `osVersion` includes the OS name, and `sdkInt` is -1 because there is no Android API level.
 */
export function webDeviceInfo(userAgent: string, platform?: string): DeviceInfo {
  const os = parseOs(userAgent);
  return {
    platform: 'web',
    manufacturer: os.manufacturer,
    model: os.model || platform || 'unknown',
    brand: parseBrowser(userAgent),
    osVersion: os.osVersion,
    sdkInt: -1,
    pluginVersion: PLUGIN_VERSION,
    gmsAvailable: false,
    hmsAvailable: false,
    backend: 'web',
    packagedProviders: [],
  };
}
