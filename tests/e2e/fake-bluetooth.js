/* Fake Web Bluetooth, injected with page.addInitScript before the app loads.
   One scooter: FFE0/FFE1 (notify, write without response), 180A/2A29 (read "HL Corp")
   and Telink OTA 1912/2B12 (read, write, notify), which the page must never touch.
   Tests drive it through window.__fakeBle: notify(uuid, bytes), drop(), calls, failConnects, discoverDelay,
   requestError {name, message}. */
(() => {
  const BASE = '-0000-1000-8000-00805f9b34fb';
  const full = u => (/^[0-9a-f]{4}$/i.test(u) ? '0000' + u + BASE : u).toLowerCase();
  const toBytes = v => (typeof v === 'string'
    ? Uint8Array.from(v.replace(/[^0-9a-f]/gi, '').match(/../g) || [], x => parseInt(x, 16))
    : Uint8Array.from(v));
  const view = v => (v instanceof DataView ? new Uint8Array(v.buffer, v.byteOffset, v.byteLength)
    : ArrayBuffer.isView(v) ? new Uint8Array(v.buffer, v.byteOffset, v.byteLength) : new Uint8Array(v));
  const hex = v => Array.from(view(v), b => b.toString(16).padStart(2, '0')).join(' ').toUpperCase();
  const lost = () => new DOMException('GATT Server is disconnected.', 'NetworkError');
  const calls = [];
  let connected = false, allowed = [];
  const device = new EventTarget();

  class Char extends EventTarget {
    constructor(service, uuid, props, value) {
      super();
      this.service = service;
      this.uuid = full(uuid);
      this.value = null;
      this._value = toBytes(value || []);
      this._notifying = false;
      this.properties = { broadcast: false, read: false, writeWithoutResponse: false, write: false, notify: false, indicate: false, authenticatedSignedWrites: false, ...props };
    }
    _op(name) { calls.push([name, this.uuid]); if (!connected) throw lost(); }
    async readValue() {
      this._op('read');
      this.value = new DataView(this._value.slice().buffer);
      this.dispatchEvent(new Event('characteristicvaluechanged'));   // Chrome fires it on reads too
      return this.value;
    }
    async startNotifications() { this._op('startNotifications'); this._notifying = true; return this; }
    async stopNotifications() { calls.push(['stopNotifications', this.uuid]); this._notifying = false; return this; }
    async writeValue(v) { this._op('write'); calls.push(['writeData', this.uuid, hex(v)]); }
    writeValueWithResponse(v) { return this.writeValue(v); }
    writeValueWithoutResponse(v) { return this.writeValue(v); }
    _emit(bytes) {
      if (!connected || !this._notifying) return false;
      this.value = new DataView(toBytes(bytes).buffer);
      this.dispatchEvent(new Event('characteristicvaluechanged'));
      return true;
    }
  }

  const services = [];
  function service(uuid, chars) {
    const s = { uuid: full(uuid), isPrimary: true, device };
    s._chars = chars.map(([u, p, v]) => new Char(s, u, p, v));
    s.getCharacteristics = async () => { if (!connected) throw lost(); return s._chars.slice(); };
    s.getCharacteristic = async u => {
      const c = s._chars.find(x => x.uuid === full(u));
      if (!c) throw new DOMException('No Characteristics matching UUID found.', 'NotFoundError');
      return c;
    };
    services.push(s);
  }
  service('ffe0', [['ffe1', { notify: true, writeWithoutResponse: true }]]);
  service('180a', [['2a29', { read: true }, Array.from(new TextEncoder().encode('HL Corp'))]]);
  service('00010203-0405-0607-0809-0a0b0c0d1912', [['00010203-0405-0607-0809-0a0b0c0d2b12', { read: true, write: true, writeWithoutResponse: true, notify: true }, [0]]]);

  const fake = {
    device, services, calls, failConnects: 0, discoverDelay: 0, requestError: null,
    get connected() { return connected; },
    char(u) { for (const s of services) for (const c of s._chars) if (c.uuid === full(u)) return c; return null; },
    notify(u, bytes) { return this.char(u)._emit(bytes); },
    drop() {   // link loss: the scooter went out of range
      if (!connected) return;
      connected = false;
      for (const s of services) for (const c of s._chars) c._notifying = false;
      device.dispatchEvent(new Event('gattserverdisconnected'));
    },
  };
  const gatt = {
    device,
    get connected() { return connected; },
    async connect() {
      calls.push(['connect']);
      if (fake.failConnects > 0) { fake.failConnects--; throw new DOMException('Connection attempt failed.', 'NetworkError'); }
      connected = true;
      return gatt;
    },
    disconnect() { calls.push(['disconnect']); fake.drop(); },
    async getPrimaryServices() {
      calls.push(['getPrimaryServices']);
      if (fake.discoverDelay) await new Promise(r => setTimeout(r, fake.discoverDelay));
      if (!connected) throw lost();
      return services.filter(s => allowed.includes(s.uuid));   // Chrome only exposes listed services
    },
  };
  Object.assign(device, { id: 'FAKE-ID-0123', name: 'BirdAir-TEST01', gatt });
  const bluetooth = {
    async requestDevice(o) {
      calls.push(['requestDevice', JSON.parse(JSON.stringify(o))]);
      if (fake.requestError) throw new DOMException(fake.requestError.message, fake.requestError.name);   // the chooser was closed or Bluetooth is off
      allowed = (o.optionalServices || []).map(full);
      return device;
    },
    async getAvailability() { return true; },
  };
  Object.defineProperty(navigator, 'bluetooth', { value: bluetooth, configurable: true });
  window.__fakeBle = fake;
})();
