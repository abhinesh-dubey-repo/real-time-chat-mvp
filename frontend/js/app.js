(() => {
  const state = {
    username: localStorage.getItem('chat_username') || null,
    activeContact: null,
    contacts: [],
    chatSocket: null,
  };

  const el = {
    authScreen: document.getElementById('auth-screen'),
    chatScreen: document.getElementById('chat-screen'),
    authUsername: document.getElementById('auth-username'),
    authError: document.getElementById('auth-error'),
    btnJoin: document.getElementById('btn-join'),
    btnLogout: document.getElementById('btn-logout'),
    meUsername: document.getElementById('me-username'),
    connStatus: document.getElementById('conn-status'),
    contactList: document.getElementById('contact-list'),
    convHeader: document.getElementById('conv-header'),
    messageList: document.getElementById('message-list'),
    sendForm: document.getElementById('send-form'),
    messageInput: document.getElementById('message-input'),
  };

  // ---- Join screen (no accounts, just pick a name) ----

  el.btnJoin.addEventListener('click', join);
  el.authUsername.addEventListener('keydown', (e) => { if (e.key === 'Enter') join(); });

  function join() {
    el.authError.textContent = '';
    const username = el.authUsername.value.trim();
    if (!username) { el.authError.textContent = 'Enter a username.'; return; }
    if (username.length > 32) { el.authError.textContent = 'Username is too long.'; return; }
    state.username = username;
    localStorage.setItem('chat_username', username);
    enterChat();
  }

  el.btnLogout.addEventListener('click', () => {
    state.chatSocket?.close();
    localStorage.removeItem('chat_username');
    location.reload();
  });

  // ---- Chat screen bootstrap ----

  async function enterChat() {
    el.authScreen.classList.add('hidden');
    el.chatScreen.classList.remove('hidden');
    el.meUsername.textContent = state.username;

    await refreshContacts();
    connectSocket();
  }

  async function refreshContacts() {
    try {
      state.contacts = await Api.listContacts(state.username);
      renderContacts();
    } catch (e) {
      console.error('Failed to load contacts', e);
    }
  }

  function renderContacts() {
    el.contactList.innerHTML = '';
    state.contacts.forEach(c => {
      const li = document.createElement('li');
      li.className = c.username === state.activeContact ? 'active' : '';
      li.innerHTML = `<span>${escapeHtml(c.username)}</span><span class="dot ${c.online ? 'online' : ''}"></span>`;
      li.addEventListener('click', () => selectContact(c.username));
      el.contactList.appendChild(li);
    });
  }

  async function selectContact(username) {
    state.activeContact = username;
    el.convHeader.textContent = username;
    el.messageInput.disabled = false;
    el.sendForm.querySelector('button').disabled = false;
    renderContacts();
    await loadHistory(username);
  }

  async function loadHistory(username) {
    el.messageList.innerHTML = '';
    try {
      const history = await Api.history(state.username, username);
      history.forEach(renderMessage);
      scrollToBottom();
    } catch (e) {
      console.error('Failed to load history', e);
    }
  }

  // ---- Sending ----

  el.sendForm.addEventListener('submit', (e) => {
    e.preventDefault();
    const content = el.messageInput.value.trim();
    if (!content || !state.activeContact) return;
    const clientMessageId = state.chatSocket.sendChat(state.activeContact, content);
    renderMessage({
      from: state.username, to: state.activeContact, content,
      timestamp: Date.now(), status: 'SENDING', clientMessageId
    });
    el.messageInput.value = '';
    scrollToBottom();
  });

  // ---- WebSocket wiring ----

  function connectSocket() {
    state.chatSocket = new ChatSocket(state.username, {
      onOpen: () => setConnStatus(true),
      onClose: () => setConnStatus(false),
      onChat: (msg) => {
        // only render if it's the conversation we've got open, otherwise just refresh the
        // sidebar so a new sender shows up there
        if (msg.from === state.activeContact || msg.to === state.activeContact) {
          renderMessage(msg);
          scrollToBottom();
        }
        refreshContacts();
      },
      onAck: (msg) => {
        const bubble = document.querySelector(`[data-client-id="${msg.clientMessageId}"] .meta`);
        if (bubble) bubble.textContent = formatMeta(msg.timestamp, msg.status);
      },
      onError: (msg) => console.error('Server error:', msg.content),
    });
    state.chatSocket.connect();
  }

  function setConnStatus(online) {
    el.connStatus.className = 'status-dot ' + (online ? 'online' : 'offline');
  }

  // ---- Rendering helpers ----

  function renderMessage(msg) {
    const li = document.createElement('li');
    li.className = msg.from === state.username ? 'mine' : '';
    li.dataset.clientId = msg.clientMessageId || '';
    li.innerHTML = `${escapeHtml(msg.content)}<span class="meta">${formatMeta(msg.timestamp, msg.status)}</span>`;
    el.messageList.appendChild(li);
  }

  function formatMeta(ts, status) {
    const time = new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    return status ? `${time} · ${status.toLowerCase()}` : time;
  }

  function scrollToBottom() {
    el.messageList.scrollTop = el.messageList.scrollHeight;
  }

  function escapeHtml(str) {
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
  }

  // ---- Boot ----
  if (state.username) {
    enterChat();
  }
})();
