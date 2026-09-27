/**
 * Wrapper around the native browser WebSocket - no socket.io-client or similar, just the raw
 * API. Reconnect-with-backoff and pending-ack tracking are hand-rolled here to match the
 * server side.
 */
class ChatSocket {
  constructor(username, handlers) {
    this.username = username;
    this.handlers = handlers; // { onOpen, onClose, onChat, onAck, onError }
    this.socket = null;
    this.reconnectAttempts = 0;
    this.manualClose = false;
    // messages we've sent but haven't gotten an ACK for yet - lets us flag something as
    // "failed" if the socket dies before the ack shows up, instead of leaving it stuck on
    // "sending..." forever
    this.pendingAcks = new Map();
  }

  connect() {
    this.manualClose = false;
    this.socket = new WebSocket(`${WS_BASE}?username=${encodeURIComponent(this.username)}`);

    this.socket.onopen = () => {
      this.reconnectAttempts = 0;
      this.handlers.onOpen?.();
    };

    this.socket.onmessage = (event) => {
      let msg;
      try { msg = JSON.parse(event.data); } catch { return; }
      switch (msg.type) {
        case 'CHAT':
          this.handlers.onChat?.(msg);
          break;
        case 'ACK':
          this.pendingAcks.delete(msg.clientMessageId);
          this.handlers.onAck?.(msg);
          break;
        case 'ERROR':
          this.handlers.onError?.(msg);
          break;
        case 'PING':
          // reply so the server sees activity and resets its timeout for us. server doesn't
          // strictly need the PONG payload itself right now, but keeping this symmetric in
          // case that changes later.
          this._send({ type: 'PONG', timestamp: Date.now() });
          break;
      }
    };

    this.socket.onclose = () => {
      this.handlers.onClose?.();
      if (!this.manualClose) this._scheduleReconnect();
    };

    this.socket.onerror = () => {
      // onclose always fires right after this, reconnect logic lives there
    };
  }

  _scheduleReconnect() {
    // exponential backoff, capped at 10s - don't want to hammer the server (or the user's
    // battery) during a real outage, but still recover fast from a brief blip
    const delay = Math.min(10000, 500 * Math.pow(2, this.reconnectAttempts));
    this.reconnectAttempts++;
    setTimeout(() => { if (!this.manualClose) this.connect(); }, delay);
  }

  sendChat(to, content) {
    const clientMessageId = crypto.randomUUID();
    const payload = { type: 'CHAT', to, content, clientMessageId, timestamp: Date.now() };
    this.pendingAcks.set(clientMessageId, payload);
    this._send(payload);
    return clientMessageId;
  }

  _send(payload) {
    if (this.socket && this.socket.readyState === WebSocket.OPEN) {
      this.socket.send(JSON.stringify(payload));
    }
  }

  close() {
    this.manualClose = true;
    this.socket?.close();
  }
}
