// Runs first in every Soulection and SoundCloud frame (the page and its player iframes).
//
// Android's WebView has no working navigator.mediaSession, so the iPhone app's
// NowPlayingScript (which sets the lock screen info from inside the player iframe) would
// do nothing. This stands in for it: whatever the scripts set (song, artist, cover,
// next/previous handlers) and the audio's play/pause/position go to the app through
// window.SRMedia, and the app's lock screen and notification buttons come back the same way.
(function () {
  if (window.__srMediaShim) return;
  window.__srMediaShim = true;
  // The app's message channel. Normally there from the start; looked up again in case not.
  var bridge = null;
  function connect() {
    if (bridge || !window.SRMedia) return bridge;
    bridge = window.SRMedia;
    bridge.onmessage = onAppMessage;
    return bridge;
  }

  var id = Math.random().toString(36).slice(2) + Date.now().toString(36);
  var isTop = window === window.top;
  var volume = 1;
  var medias = [];
  var lastMetaKey = '';
  var lastStateKey = '';
  var lastStateAt = 0;

  function send(o) {
    o.id = id;
    o.top = isTop;
    if (!connect()) return;
    try { bridge.postMessage(JSON.stringify(o)); } catch (e) {}
  }

  // The media session the scripts see.
  function MediaSessionShim() {
    this._meta = null;
    this._state = 'none';
    this._handlers = {};
  }
  Object.defineProperty(MediaSessionShim.prototype, 'metadata', {
    configurable: true,
    get: function () { return this._meta; },
    set: function (v) { this._meta = v || null; sendMeta(); }
  });
  Object.defineProperty(MediaSessionShim.prototype, 'playbackState', {
    configurable: true,
    get: function () { return this._state; },
    set: function (v) { this._state = String(v || 'none'); sendState(true); }
  });
  MediaSessionShim.prototype.setActionHandler = function (action, handler) {
    if (typeof handler === 'function') this._handlers[action] = handler;
    else delete this._handlers[action];
    sendState(true);
  };
  MediaSessionShim.prototype.setPositionState = function () {};
  MediaSessionShim.prototype.setMicrophoneActive = function () {};
  MediaSessionShim.prototype.setCameraActive = function () {};

  var session = new MediaSessionShim();
  try {
    Object.defineProperty(navigator, 'mediaSession', { configurable: true, get: function () { return session; } });
  } catch (e) {}
  if (typeof window.MediaMetadata === 'undefined') {
    window.MediaMetadata = function (o) {
      o = o || {};
      this.title = o.title || '';
      this.artist = o.artist || '';
      this.album = o.album || '';
      this.artwork = o.artwork || [];
    };
  }

  function sendMeta() {
    var m = session._meta;
    var art = '';
    if (m && m.artwork && m.artwork.length) {
      var a = m.artwork[m.artwork.length - 1];
      art = (a && a.src) || '';
    }
    var meta = { title: (m && m.title) || '', artist: (m && m.artist) || '', album: (m && m.album) || '', art: art };
    var key = JSON.stringify(meta);
    if (key === lastMetaKey || !connect()) return;
    lastMetaKey = key;
    send({ t: 'meta', meta: meta });
  }

  // The audio: the element that's actually heard (playing and not muted), else any.
  function current() {
    for (var i = 0; i < medias.length; i++) if (!medias[i].paused && !medias[i].muted) return medias[i];
    return medias[0] || null;
  }
  function sendState(force) {
    var m = current();
    var playing = !!(m && !m.paused && !m.muted);
    var pos = m ? (m.currentTime || 0) : 0;
    var dur = m && isFinite(m.duration) ? m.duration : 0;
    var actions = Object.keys(session._handlers);
    var key = JSON.stringify([playing, actions, !!m, Math.round(dur)]);
    var now = Date.now();
    if (!force && key === lastStateKey && now - lastStateAt < 4000) return;
    lastStateKey = key;
    lastStateAt = now;
    send({ t: 'state', playing: playing, hasMedia: !!m, pos: pos, dur: dur, actions: actions });
  }

  function track(el) {
    if (!(el instanceof HTMLMediaElement) || medias.indexOf(el) >= 0) return;
    medias.push(el);
    try { el.volume = volume; } catch (e) {}
    ['play', 'playing', 'pause', 'ended', 'seeked', 'volumechange', 'loadedmetadata'].forEach(function (name) {
      el.addEventListener(name, function () { sendState(true); });
    });
    el.addEventListener('timeupdate', function () { sendState(false); });
  }
  var nativePlay = HTMLMediaElement.prototype.play;
  HTMLMediaElement.prototype.play = function () {
    track(this);
    return nativePlay.apply(this, arguments);
  };
  document.addEventListener('play', function (e) { track(e.target); }, true);

  // From the app: lock screen / notification buttons, volume (sleep fade), pause all.
  function onAppMessage(ev) {
    var d;
    try { d = JSON.parse(ev.data); } catch (e) { return; }
    if (!d) return;
    if (typeof d.volume === 'number') {
      volume = Math.max(0, Math.min(1, d.volume));
      medias.forEach(function (m) { try { m.volume = volume; } catch (e) {} });
    }
    if (d.pauseAll) medias.forEach(function (m) { try { m.pause(); } catch (e) {} });
    if (d.action) act(d.action, d.seekTime);
  }

  function act(action, seekTime) {
    var h = session._handlers[action];
    if (h) {
      try { h({ action: action, seekTime: seekTime }); } catch (e) {}
      return;
    }
    var m = current();
    if (!m) return;
    if (action === 'play') { var p = m.play(); if (p && p.catch) p.catch(function () {}); }
    else if (action === 'pause') m.pause();
    else if (action === 'seekto' && typeof seekTime === 'number') { try { m.currentTime = seekTime; } catch (e) {} }
  }

  if (!connect()) document.addEventListener('DOMContentLoaded', function () { send({ t: 'hello' }); sendMeta(); sendState(true); });
  send({ t: 'hello' });
})();
