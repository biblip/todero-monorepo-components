
/*! resilient-ws (browser) */
(function (root, factory) {
  if (typeof define === 'function' && define.amd) {
    // AMD
    define([], factory);
  } else if (typeof module === 'object' && module.exports) {
    // CommonJS
    module.exports = factory();
  } else {
    // Browser global
    root.ResilientWebSocket = factory();
  }
}(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  function noop() {}

  /**
   * ResilientWebSocket:
   *  - Auto-reconnect with exponential backoff + jitter
   *  - Handshake timeout
   *  - Optional send queue while connecting
   *  - Event callbacks: open, message, error, close (library-managed, not DOM)
   *
   * Options:
   *  - protocols?: string|string[]
   *  - autoReconnect?: boolean (default true)
   *  - baseDelay?: number ms (default 1000)
   *  - maxDelay?: number ms (default 10000)
   *  - jitterMs?: number ms (default 250)
   *  - handshakeTimeout?: number ms (default 8000)
   *  - queueWhileConnecting?: boolean (default false)
   *  - debug?: boolean (default false)
   *  - logger?: (msg: string, ...args: any[]) => void (default console.log)
   */
  function ResilientWebSocket(url, options) {
    if (!(this instanceof ResilientWebSocket)) return new ResilientWebSocket(url, options);
    options = options || {};

    this.url = url;
    this.protocols = options.protocols;
    this.autoReconnect = options.autoReconnect !== false;
    this.baseDelay = options.baseDelay != null ? options.baseDelay : 1000;
    this.maxDelay = options.maxDelay != null ? options.maxDelay : 10000;
    this.jitterMs = options.jitterMs != null ? options.jitterMs : 250;
    this.handshakeTimeout = options.handshakeTimeout != null ? options.handshakeTimeout : 8000;
    this.queueWhileConnecting = !!options.queueWhileConnecting;
    this.debug = !!options.debug;
    this.logger = options.logger || (typeof console !== 'undefined' ? console.log.bind(console) : noop);

    this.ws = null;
    this._connecting = false;
    this._shouldReconnect = this.autoReconnect;
    this._reconnectDelay = this.baseDelay;
    this._reconnectTimer = null;
    this._handshakeTimer = null;
    this._sendQueue = [];

    this._handlers = {
      open: new Set(),
      message: new Set(),
      error: new Set(),
      close: new Set()
    };
  }

  ResilientWebSocket.prototype._log = function () {
    if (this.debug) {
      try { this.logger.apply(null, arguments); } catch (_) {}
    }
  };

  ResilientWebSocket.prototype.on = function (event, handler) {
    if (this._handlers[event]) this._handlers[event].add(handler);
    return this;
  };

  ResilientWebSocket.prototype.off = function (event, handler) {
    if (this._handlers[event]) this._handlers[event].delete(handler);
    return this;
  };

  ResilientWebSocket.prototype._emit = function (event, arg) {
    if (!this._handlers[event]) return;
    this._handlers[event].forEach(function (fn) {
      try { fn(arg); } catch (e) { /* swallow */ }
    });
  };

  ResilientWebSocket.prototype.connect = function () {
    if (this._connecting) return;
    if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)) return;
    this._connecting = true;
    this._log('[ResilientWS] connecting to', this.url);

    var ws;
    try {
      ws = this.protocols ? new WebSocket(this.url, this.protocols) : new WebSocket(this.url);
    } catch (e) {
      this._connecting = false;
      this._log('[ResilientWS] constructor error', e);
      this._scheduleReconnect();
      return;
    }
    this.ws = ws;

    // Handshake timeout
    this._clearHandshakeTimer();
    var self = this;
    this._handshakeTimer = setTimeout(function () {
      if (!self.ws) return;
      if (self.ws.readyState === WebSocket.CONNECTING) {
        self._log('[ResilientWS] handshake timeout, closing');
        try { self.ws.close(); } catch (_) {}
      }
    }, this.handshakeTimeout);

    ws.onopen = function () {
      self._connecting = false;
      self._clearHandshakeTimer();
      self._log('[ResilientWS] open');
      self._reconnectDelay = self.baseDelay;
      self._emit('open');
      // flush queue
      if (self._sendQueue.length) {
        var q = self._sendQueue.slice();
        self._sendQueue.length = 0;
        for (var i = 0; i < q.length; i++) {
          try { ws.send(q[i]); } catch (e) { self._log('[ResilientWS] flush send failed', e); }
        }
      }
    };

    ws.onmessage = function (ev) {
      self._emit('message', ev);
    };

    ws.onerror = function (ev) {
      self._log('[ResilientWS] error event', ev && ev.message ? ev.message : ev);
      self._emit('error', ev);
    };

    ws.onclose = function (ev) {
      self._connecting = false;
      self._clearHandshakeTimer();
      self._emit('close', ev);
      self._log('[ResilientWS] close code=' + (ev && ev.code));

      // Do not reconnect on clean closes
      if (!self._shouldReconnect || (ev && (ev.code === 1000 || ev.code === 1001))) return;
      self._scheduleReconnect();
    };

    return this;
  };

  ResilientWebSocket.prototype._scheduleReconnect = function () {
    if (!this._shouldReconnect) return;
    if (this._reconnectTimer) return;
    var delay = this._reconnectDelay + Math.floor(Math.random() * this.jitterMs);
    this._reconnectDelay = Math.min(this._reconnectDelay * 2, this.maxDelay);
    var self = this;
    this._log('[ResilientWS] scheduling reconnect in', delay, 'ms');
    this._reconnectTimer = setTimeout(function () {
      self._reconnectTimer = null;
      self.connect();
    }, delay);
  };

  ResilientWebSocket.prototype.send = function (data) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      try {
        this.ws.send(data);
        return true;
      } catch (e) {
        this._log('[ResilientWS] send failed', e);
        return false;
      }
    }
    if (this.queueWhileConnecting) {
      this._sendQueue.push(data);
      this._log('[ResilientWS] queued message (connecting)');
      return true;
    }
    return false;
  };

  ResilientWebSocket.prototype.isOpen = function () {
    return !!(this.ws && this.ws.readyState === WebSocket.OPEN);
  };

  ResilientWebSocket.prototype.close = function (code, reason) {
    if (code == null) code = 1000;
    if (reason == null) reason = 'client closing';
    this._shouldReconnect = false;
    this._clearHandshakeTimer();
    if (this._reconnectTimer) { clearTimeout(this._reconnectTimer); this._reconnectTimer = null; }
    if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)) {
      try { this.ws.close(code, reason); } catch (_) {}
    }
  };

  ResilientWebSocket.prototype.setUrl = function (url) {
    this.url = url;
    return this;
  };

  ResilientWebSocket.prototype.enableAutoReconnect = function (enable) {
    this._shouldReconnect = !!enable;
    return this;
  };

  ResilientWebSocket.prototype._clearHandshakeTimer = function () {
    if (this._handshakeTimer) clearTimeout(this._handshakeTimer);
    this._handshakeTimer = null;
  };

  return ResilientWebSocket;
}));
