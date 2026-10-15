/* Fake Web Bluetooth, injected with page.addInitScript before the app loads.
   One scooter like the real one: 1800/2A00 (read, notify), FFE0/FFE1 (notify, write without response), 180A/2A29 (read "HL Corp")
   and Telink OTA 1912/2B12 (read, write, notify), which the page must never touch.
   Tests drive it through window.__fakeBle: notify(uuid, bytes), drop(), calls, failConnects, discoverDelay,
   connectDelay (disconnect() during it aborts the connect, as in Chrome), requestError {name, message}.
   fake.dash (off unless a test sets it) models the real dashboard behind FFE1, see dashWrite() below. */
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
  let connected = false, allowed = [], pendingConnect = null, aborted = false;
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
    async writeValue(v) {
      this._op('write');
      calls.push(['writeData', this.uuid, hex(v)]);
      if (this.uuid === full('ffe1')) dashWrite(view(v).slice());
    }
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
  service('1800', [['2a00', { read: true, notify: true }, Array.from(new TextEncoder().encode('BirdAir-TEST01'))]]);
  service('ffe0', [['ffe1', { notify: true, writeWithoutResponse: true }]]);
  service('180a', [['2a29', { read: true }, Array.from(new TextEncoder().encode('HL Corp'))]]);
  service('00010203-0405-0607-0809-0a0b0c0d1912', [['00010203-0405-0607-0809-0a0b0c0d2b12', { read: true, write: true, writeWithoutResponse: true, notify: true }, [0]]]);

  const fake = {
    device, services, calls, failConnects: 0, discoverDelay: 0, connectDelay: 0, requestError: null, dash: null,
    get connected() { return connected; },
    char(u) { for (const s of services) for (const c of s._chars) if (c.uuid === full(u)) return c; return null; },
    notify(u, bytes) { return this.char(u)._emit(bytes); },
    drop() {   // link loss: the scooter went out of range
      if (!connected) return;
      connected = false;
      for (const s of services) for (const c of s._chars) c._notifying = false;
      dashStop();
      device.dispatchEvent(new Event('gattserverdisconnected'));
    },
  };

  /* The real dashboard (logs/20261014-174148-log.json): silent until the phone writes an ASCII «+…»
     command, then it answers and keeps relaying the controller's UART frames, cut into notifications.
     fake.dash = {
       after: { '+MODE=1': { reply: [hex…], loop: [hex…] }, … }  — real notification chunks recorded after
         that command: reply = up to and including its echo/answer, loop = the stream that followed;
       wakes(text) — which commands start the stream (default: any «+…»);
       window — the stream stops this many ms after the last «+…» command (the real dash: ~4500;
         default: never);
       every — ms between notifications (default 15).
     }
     A command without an entry is echoed back. AE "KEY?" answers "OK OK OK " and stops the stream for
     good (on the scooter: wrench and error 37 until a power cycle). A dropped link silences it again. */
  const ascii = s => Array.from(s, c => c.charCodeAt(0));
  let dashTimer = null, dashQueue = [], dashLoop = null, dashI = 0, dashOn = false;
  function dashStop() { clearInterval(dashTimer); dashTimer = null; dashQueue = []; dashOn = false; }
  function dashTick() {
    const c = fake.char('ffe1');
    if (dashQueue.length) c._emit(dashQueue.shift());
    else if (dashOn && dashLoop && dashLoop.length) c._emit(dashLoop[dashI++ % dashLoop.length]);
  }
  function dashWrite(b) {
    const d = fake.dash;
    if (!d) return;
    const text = String.fromCharCode(...b);
    if (b[0] === 0xAE && b[1] === 0x4B && b[2] === 0x45 && b[3] === 0x59) {
      d.halted = true; dashOn = false; dashLoop = null;
      dashQueue.push(ascii('OK OK OK '));
    } else if (text.startsWith('+') && !d.halted) {
      const seg = (d.after || {})[text] || { reply: [ascii(text)], loop: null };
      dashQueue.push(...seg.reply);
      if ((d.wakes || (() => true))(text)) {
        dashOn = true;
        if (seg.loop && seg.loop.length) { dashLoop = seg.loop; dashI = 0; }
        if (d.window) { clearTimeout(d.windowTimer); d.windowTimer = setTimeout(() => { dashOn = false; }, d.window); }
      }
    } else return;
    if (!dashTimer) dashTimer = setInterval(dashTick, d.every || 15);
  }
  const gatt = {
    device,
    get connected() { return connected; },
    async connect() {
      calls.push(['connect']);
      if (fake.connectDelay) {
        await new Promise((resolve, reject) => { pendingConnect = reject; setTimeout(resolve, fake.connectDelay); });
        if (aborted) { aborted = false; throw new DOMException('Connection attempt was aborted.', 'AbortError'); }
        pendingConnect = null;
      }
      if (fake.failConnects > 0) { fake.failConnects--; throw new DOMException('Connection attempt failed.', 'NetworkError'); }
      connected = true;
      return gatt;
    },
    disconnect() {
      calls.push(['disconnect']);
      if (pendingConnect) { aborted = true; pendingConnect(new DOMException('Connection attempt was aborted.', 'AbortError')); pendingConnect = null; }
      fake.drop();
    },
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
