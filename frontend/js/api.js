// backend is a separate container/port from this frontend, and the browser talks to it
// directly (not through the nginx container serving these files) - that's why "localhost"
// works here even though this js is served from a different container.
const API_BASE = 'http://localhost:8080/api';
const WS_BASE = 'ws://localhost:8080/ws/chat';

// no login, so "self" is just whatever username was typed on the join screen
const Api = {
  async listContacts(self) {
    return Api._get(`/users?self=${encodeURIComponent(self)}`);
  },
  async history(self, otherUsername) {
    return Api._get(`/messages/${encodeURIComponent(otherUsername)}?self=${encodeURIComponent(self)}`);
  },
  async _get(path) {
    const res = await fetch(`${API_BASE}${path}`);
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error || `request_failed_${res.status}`);
    return data;
  }
};
