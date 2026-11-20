'use strict';
const $ = id => document.getElementById(id);
const audio = $('audio');
const pairingParameters = new URLSearchParams(location.search);
const approvedCodeConnection = pairingParameters.get('paired') === '1';
let invitation = location.hash.slice(1); history.replaceState(null, '', location.pathname);
let state = null, library = {}, collection = null, connected = false, busyPoll = false, lyrics = [], lyricTrack = '';
let phoneQueue = [], phoneIndex = -1, loading = false, playbackEpoch = 0, pendingVoice = null;
let target = approvedCodeConnection ? 'pc' : 'phone', currentView = 'home', libraryFilter = 'favorites';
const read = (key, fallback) => { try { return JSON.parse(localStorage.getItem('bitchord.' + key)) ?? fallback; } catch { return fallback; } };
const save = (key, value) => { try { localStorage.setItem('bitchord.' + key, JSON.stringify(value)); } catch {} };
const prefs = read('prefs', { quality: 256, speed: 1, repeat: 'off', shuffle: false, voice: true, motion: true });
const phoneRecent = read('recent', []), phoneFavorites = read('favorites', []), phoneLists = read('playlists', []);
// Cached metadata is refreshed against the host before use; invitations and media tickets are never persisted.
const tracks = new Map();
const remember = rows => { for (const song of rows || []) if (song?.id) tracks.set(song.id, song); return rows || []; };
const notify = text => { $('notice').textContent = text || ''; };
const safe = fn => (...args) => { try { return Promise.resolve(fn(...args)).catch(error => notify(error.message || 'Please try again.')); } catch (error) { notify(error.message || 'Please try again.'); return Promise.resolve(); } };
async function api(path, body) {
  const response = await fetch('/api/' + path, { method: body === undefined ? 'GET' : 'POST', credentials: 'same-origin', cache: 'no-store',
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) });
  const result = await response.json();
  if (!response.ok) { if (response.status === 401) showJoin(); throw new Error(result.error || 'The PC could not complete this request.'); }
  return result;
}
const command = (action, extra = {}) => api('command', { action, ...extra });
function el(tag, text, cls) { const node = document.createElement(tag); if (text !== undefined) node.textContent = text; if (cls) node.className = cls; return node; }
function button(text, fn, title) { const b = el('button', text); b.type = 'button'; if (title) b.setAttribute('aria-label', title); b.onclick = safe(fn); return b; }
function image(node, url) { if (url && /^https:\/\/(i\.ytimg\.com|[^/]+\.googleusercontent\.com|i\.scdn\.co|[^/]+\.mzstatic\.com)\//.test(url)) { node.src = url; node.hidden = false; } else { node.removeAttribute('src'); node.hidden = true; } }
function view(name) {
  currentView = name;
  document.querySelectorAll('.view').forEach(node => { node.hidden = node.id !== name; });
  document.querySelectorAll('[data-view]').forEach(node => node.classList.toggle('selected', node.dataset.view === name));
  $('pageTitle').textContent = ({ home: 'Listen Now', library: 'Library', dj: 'Your DJ', search: 'Search', queue: 'Up Next', settings: 'Settings', collection: 'Your Music' })[name] || 'Music';
  if (name === 'queue') renderQueue(); if (name === 'library') renderLibrary();
}
function showJoin() { connected = false; $('join').hidden = false; $('app').hidden = true; $('tabs').hidden = true; $('mini').hidden = true; audio.pause(); }
function showApp() { connected = true; invitation = ''; $('join').hidden = true; $('app').hidden = false; $('tabs').hidden = false; $('mini').hidden = false; view(currentView); }
function current() { return target === 'pc' ? state?.playback.current : phoneQueue[phoneIndex]; }
function position() { return target === 'pc' ? (state?.playback.positionMs || 0) / 1000 : (audio.currentTime || 0); }
function duration() { return target === 'pc' ? (state?.playback.durationMs || 0) / 1000 : (Number.isFinite(audio.duration) ? audio.duration : 0); }
function playing() { return target === 'pc' ? !!state?.playback.playing : !audio.paused; }
const time = seconds => { const n = Math.max(0, Math.floor(seconds || 0)); return Math.floor(n / 60) + ':' + String(n % 60).padStart(2, '0'); };
function renderPlayer() {
  const song = current(), isPlaying = playing();
  $('title').textContent = $('miniTitle').textContent = song?.title || 'Choose a song';
  $('artist').textContent = $('miniArtist').textContent = song?.artist || 'Search for something you love.';
  image($('artwork'), song?.artwork); image($('miniArt'), song?.artwork);
  $('playPause').textContent = isPlaying ? 'Ⅱ' : '▶'; $('miniPlaying').textContent = isPlaying ? 'Ⅱ' : '▷';
  $('playerDevice').textContent = target === 'pc' ? 'PLAYING ON YOUR PC' : 'PLAYING ON THIS DEVICE';
  $('playerHint').textContent = loading ? 'Preparing audio…' : target === 'phone' ? 'Route audio to headphones, Bluetooth or AirPlay with iOS. Keep your PC open.' : state?.playback.locked ? 'Leave Listen Together on the PC before using these controls.' : 'Shared PC playback. Guests can add songs to the Jam.';
  const d = duration(), p = position();
  $('elapsed').textContent = time(p); $('duration').textContent = time(d);
  if (document.activeElement !== $('seek')) $('seek').value = d ? Math.round(1000 * p / d) : 0;
  if (document.activeElement !== $('volume')) $('volume').value = Math.round(100 * (target === 'pc' ? state?.playback.volume ?? 1 : audio.volume));
  $('favorite').textContent = (library.favorites || []).some(t => t.id === song?.id) || phoneFavorites.some(t => t.id === song?.id) ? '♥ Favorited' : '♡ Favorite';
  const disabled = target === 'pc' && (!state?.canControl || state?.playback.locked);
  ['playPause', 'previous', 'next', 'seek', 'volume'].forEach(id => { $(id).disabled = disabled; });
  $('clearPhoneQueue').hidden = target === 'pc';
  document.body.classList.toggle('motion', prefs.motion && isPlaying);
  if ('mediaSession' in navigator && song && typeof MediaMetadata !== 'undefined') {
    if (navigator.mediaSession.metadata?.title !== song.title) navigator.mediaSession.metadata = new MediaMetadata({ title: song.title, artist: song.artist, artwork: song.artwork ? [{ src: song.artwork }] : [] });
    navigator.mediaSession.playbackState = isPlaying ? 'playing' : 'paused';
    if (target === 'phone' && d > 0 && p <= d) try { navigator.mediaSession.setPositionState({ duration: d, playbackRate: audio.playbackRate, position: p }); } catch {}
  }
  if (lyricTrack === song?.id) {
    let active = -1; lyrics.forEach((line, i) => { if (line.time != null && line.time <= p * 1000) active = i; });
    [...$('lyricsContent').children].forEach((node, i) => node.classList.toggle('active', i === active));
  } else { $('lyricsContent').hidden = true; lyrics = []; }
}
async function poll() {
  if (!connected || busyPoll) return;
  busyPoll = true;
  try { state = await api('state'); remember([state.playback.current, ...state.playback.queue.map(x => x.track)]); renderPlayer(); if (currentView === 'queue') renderQueue(); }
  catch (error) { notify(error.message); } finally { busyPoll = false; }
}
function row(song, list, index, options = {}) {
  remember([song]); const node = el('div', undefined, 'track'), art = el('img'); art.alt = ''; art.loading = 'lazy'; image(art, song.artwork); node.append(art);
  const meta = button('', () => playList(list || [song], index ?? 0)); meta.className = 'metadata'; meta.append(el('strong', song.title), el('small', song.artist)); node.append(meta);
  const actions = el('div', undefined, 'actions');
  actions.append(button('+', async () => {
    if (target === 'pc') { await command('queue', { id: song.id }); notify('Added to the shared PC queue.'); await poll(); }
    else { phoneQueue.push(song); notify('Added to your iPhone queue.'); renderQueue(); }
  }, 'Add ' + song.title + ' to queue'));
  if (options.remove) actions.append(button('−', options.remove, 'Remove ' + song.title));
  else actions.append(button('•••', () => songActions(song), 'More options for ' + song.title));
  node.append(actions); return node;
}
function renderTracks(container, rows, remove) {
  remember(rows); container.replaceChildren();
  if (!rows?.length) { container.append(el('p', 'No songs here yet.', 'hint')); return; }
  rows.forEach((song, i) => container.append(row(song, rows, i, remove ? { remove: () => remove(song, i) } : {})));
}
async function playList(rows, index = 0) {
  if (!rows.length) throw new Error('No songs are available.');
  if (target === 'pc') { await command('playBatch', { ids: rows.slice(index).slice(0, 90).map(t => t.id) }); await poll(); }
  else { phoneQueue = [...rows]; phoneIndex = index; pendingVoice = null; await loadPhone(); }
}
async function loadPhone(startAt = 0) {
  const song = phoneQueue[phoneIndex]; if (!song) return;
  const epoch = ++playbackEpoch; loading = true; audio.pause(); audio.removeAttribute('src'); audio.load(); notify('Preparing ' + song.title + '…'); renderPlayer();
  try {
    const media = await api('prepare', { id: song.id, quality: Number(prefs.quality) });
    if (epoch !== playbackEpoch || target !== 'phone') return;
    audio.src = media.url; audio.playbackRate = Number(prefs.speed); audio.load();
    if (startAt > 0) audio.addEventListener('loadedmetadata', () => { if (epoch === playbackEpoch) audio.currentTime = Math.min(startAt, audio.duration || startAt); }, { once: true });
    try { await audio.play(); notify(''); } catch { notify('Audio is ready. Tap Play to start on your iPhone.'); }
    if (!phoneRecent.some(t => t.id === song.id)) phoneRecent.unshift(song); else { const at = phoneRecent.findIndex(t => t.id === song.id); phoneRecent.splice(at, 1); phoneRecent.unshift(song); }
    phoneRecent.splice(100); save('recent', phoneRecent);
  } finally { if (epoch === playbackEpoch) { loading = false; renderPlayer(); renderQueue(); } }
}
async function togglePlayback(force) {
  if (target === 'pc') { await command(force || (playing() ? 'pause' : 'play')); await poll(); return; }
  if (force === 'pause' || (force !== 'play' && !audio.paused)) audio.pause();
  else if (pendingVoice) { audio.src = pendingVoice; pendingVoice = false; audio.load(); await audio.play(); }
  else if (audio.currentSrc || audio.src) await audio.play();
  else if (phoneQueue.length) await loadPhone(); else throw new Error('Choose a song first.');
  renderPlayer();
}
async function advance(delta = 1, ended = false) {
  if (target === 'pc') { await command(delta > 0 ? 'next' : 'previous'); await poll(); return; }
  if (pendingVoice === false) { pendingVoice = null; await loadPhone(); return; }
  if (!phoneQueue.length) return;
  if (ended && prefs.repeat === 'one') { audio.currentTime = 0; await audio.play(); return; }
  if (delta < 0 && audio.currentTime > 3) { audio.currentTime = 0; return; }
  let next = prefs.shuffle && phoneQueue.length > 1 && delta > 0 ? (phoneIndex + 1 + Math.floor(Math.random() * (phoneQueue.length - 1))) % phoneQueue.length : phoneIndex + delta;
  if (next >= phoneQueue.length && prefs.repeat === 'all') next = 0;
  if (next < 0) next = 0;
  if (next >= phoneQueue.length) { audio.pause(); renderPlayer(); return; }
  phoneIndex = next; await loadPhone();
}
function renderQueue() {
  $('queueTitle').textContent = target === 'pc' ? 'Shared PC Jam queue' : 'Your iPhone queue';
  $('people').textContent = (state?.members || []).map(p => p.name + (p.controller ? ' · host' : '')).join('  /  ');
  const root = $('queueList'); root.replaceChildren();
  if (target === 'phone') {
    if (phoneIndex >= 0 && phoneQueue[phoneIndex]) root.append(row(phoneQueue[phoneIndex], phoneQueue, phoneIndex));
    phoneQueue.slice(phoneIndex + 1).forEach((song, offset) => {
      const at = phoneIndex + 1 + offset;
      const node = row(song, phoneQueue, at, { remove: () => { phoneQueue.splice(at, 1); renderQueue(); } });
      node.querySelector('.actions').append(button('↑', () => { if (at > phoneIndex + 1) { [phoneQueue[at - 1], phoneQueue[at]] = [phoneQueue[at], phoneQueue[at - 1]]; renderQueue(); } }, 'Move up'));
      root.append(node);
    });
  } else {
    const upcoming = state?.playback.queue || [];
    upcoming.forEach((entry, i) => {
      const node = el('div', undefined, 'track'), meta = button('', async () => { await command('playQueue', { index: entry.index, id: entry.track.id }); await poll(); });
      meta.className = 'metadata'; meta.append(el('strong', entry.track.title), el('small', entry.track.artist)); meta.disabled = !state.canControl; node.append(meta);
      if (state.canControl) {
        node.append(button('−', async () => { await command('remove', { index: entry.index, id: entry.track.id }); await poll(); }, 'Remove song'));
        if (i > 0) node.append(button('↑', async () => { await command('move', { index: entry.index, id: entry.track.id, target: upcoming[i - 1].index, targetId: upcoming[i - 1].track.id }); await poll(); }, 'Move up'));
      } root.append(node);
    });
  }
  if (!root.children.length) root.append(el('p', 'The queue is empty. Search and add something you love.', 'hint'));
}
function collectionRow(item, fn) { const node = el('div', undefined, 'track'), b = button('', fn); b.className = 'metadata'; b.append(el('strong', item.title), el('small', item.artist || (item.count != null ? item.count + ' songs' : item.type || 'Playlist'))); node.append(b); return node; }
async function refreshLibrary() {
  if (state?.role !== 'Controller') { library = {}; renderHome(); renderLibrary(); renderSettings(); return; }
  library = await api('library'); ['recent', 'favorites', 'local', 'downloads'].forEach(k => remember(library[k]));
  renderHome(); renderLibrary(); renderSettings();
}
function renderHome() {
  const root = $('homeContent'); root.replaceChildren();
  const recent = [...phoneRecent, ...(library.recent || [])].filter((t, i, rows) => rows.findIndex(x => x.id === t.id) === i).slice(0, 8);
  if (recent.length) { root.append(el('h2', 'Recently played')); renderTracksInto(root, recent); }
  for (const shelf of library.home || []) {
    const section = el('div', undefined, 'shelf'); section.append(el('h2', shelf.title));
    const songs = (shelf.items || []).filter(x => x.kind === 'track').map(x => x.track); remember(songs);
    for (const item of (shelf.items || []).slice(0, 8)) section.append(item.kind === 'track' ? row(item.track, songs, songs.indexOf(item.track)) : collectionRow(item, () => openCollection(item)));
    root.append(section);
  }
  if (!root.children.length) root.append(el('p', 'Search for music to start listening. Your recent plays appear here.', 'hint'));
}
function renderTracksInto(root, rows) { const group = el('div'); renderTracks(group, rows); root.append(group); }
function renderLibrary() {
  const root = $('libraryContent'); root.replaceChildren();
  if (libraryFilter === 'playlists') {
    root.append(button('＋ New playlist', () => newPlaylist()));
    for (const item of [...phoneLists, ...(library.playlists || [])]) root.append(collectionRow(item, () => openCollection(item)));
  } else {
    let rows = library[libraryFilter] || [];
    if (libraryFilter === 'favorites') rows = [...phoneFavorites, ...rows].filter((t, i, all) => all.findIndex(x => x.id === t.id) === i);
    if (libraryFilter === 'recent') rows = [...phoneRecent, ...rows].filter((t, i, all) => all.findIndex(x => x.id === t.id) === i);
    renderTracks(root, rows);
  }
}
async function openCollection(item) {
  if (item.id.startsWith('phone:')) { collection = phoneLists.find(list => list.id === item.id); if (!collection) throw new Error('That phone playlist no longer exists.'); }
  else { notify('Opening ' + item.title + '…'); collection = await api('browse', { id: item.id }); }
  remember(collection.tracks); $('collectionTitle').textContent = collection.title;
  const editable = collection.id.startsWith('phone:') || collection.id.startsWith('local:');
  $('collectionRename').hidden = $('collectionDelete').hidden = !editable;
  renderTracks($('collectionTracks'), collection.tracks, editable ? song => removeFromPlaylist(song) : null); view('collection'); notify('');
}
async function newPlaylist(song) {
  const title = prompt('Playlist name'); if (!title?.trim()) return;
  const onPC = state?.role === 'Controller' && confirm('Save this playlist on the PC too? Cancel keeps it on this phone.');
  if (onPC) { await command('playlistCreate', { text: title, ids: song ? [song.id] : [] }); await refreshLibrary(); }
  else { phoneLists.push({ id: 'phone:' + Date.now() + Math.random().toString(16).slice(2), title: title.slice(0, 100), tracks: song ? [song] : [] }); save('playlists', phoneLists); renderLibrary(); }
}
async function addToPlaylist(song) {
  if (!song) throw new Error('Choose a song first.');
  const lists = [...phoneLists, ...(library.playlists || [])];
  if (!lists.length) return newPlaylist(song);
  const choice = prompt('Choose playlist number, or 0 for a new playlist:\n' + lists.map((x, i) => (i + 1) + '. ' + x.title).join('\n'));
  if (choice == null) return; if (choice === '0') return newPlaylist(song);
  const list = lists[Number(choice) - 1]; if (!list) throw new Error('Choose a playlist from the list.');
  if (list.id.startsWith('phone:')) { if (!list.tracks.some(t => t.id === song.id)) list.tracks.push(song); save('playlists', phoneLists); }
  else await command('playlistAdd', { key: list.id, id: song.id });
  notify('Added to ' + list.title + '.');
}
async function removeFromPlaylist(song) {
  if (collection.id.startsWith('phone:')) { collection.tracks = collection.tracks.filter(t => t.id !== song.id); save('playlists', phoneLists); }
  else { await command('playlistRemove', { key: collection.id, id: song.id }); collection.tracks = collection.tracks.filter(t => t.id !== song.id); }
  await openCollection(collection);
}
async function favorite(song) {
  if (!song) throw new Error('Choose a song first.');
  if (state?.role === 'Controller') { await command('favorite', { id: song.id }); const local = phoneFavorites.findIndex(t => t.id === song.id); if (local >= 0) { phoneFavorites.splice(local, 1); save('favorites', phoneFavorites); } await refreshLibrary(); }
  else { const index = phoneFavorites.findIndex(t => t.id === song.id); if (index >= 0) phoneFavorites.splice(index, 1); else phoneFavorites.unshift(song); save('favorites', phoneFavorites); }
  renderPlayer(); renderLibrary();
}
async function songActions(song) {
  const choice = prompt('1 · Favorite / unfavorite\n2 · Add to playlist\n3 · Add to shared PC Jam\n4 · Start radio\n5 · Save audio file');
  if (choice === '1') await favorite(song); else if (choice === '2') await addToPlaylist(song);
  else if (choice === '3') { await command('queue', { id: song.id }); notify('Added to the shared PC Jam.'); }
  else if (choice === '4') await radio(song); else if (choice === '5') await saveAudio(song);
}
async function radio(song) { if (!song) throw new Error('Choose a song first.'); notify('Finding a station…'); const rows = remember(await api('radio', { id: song.id })); await playList(rows); }
async function saveAudio(song) {
  if (!song) throw new Error('Choose a song first.');
  notify('Preparing the audio file…'); const media = await api('prepare', { id: song.id, quality: Number(prefs.quality) });
  const link = el('a', 'Tap to save audio to Files'); link.href = media.url + '&download=1'; link.download = song.title + '.m4a';
  $('notice').replaceChildren(link); // A real user tap lets Safari open its download UI.
}
function renderSettings() {
  const root = $('pcSettings'); root.replaceChildren();
  if (state?.role !== 'Controller') { root.append(el('p', 'Scan a controller invitation to access the PC library and settings.', 'hint')); return; }
  let group, previous;
  for (const setting of library.settings || []) {
    if (setting.section !== previous) { root.append(el('h3', setting.section)); group = el('div', undefined, 'card settings'); root.append(group); previous = setting.section; }
    const label = el('label'), text = el('span', setting.label); label.append(text); let input;
    if (setting.type === 'select') {
      input = el('select'); for (const value of setting.options) { const option = el('option', setting.key === 'output' ? (library.outputs || []).find(o => o.id === value)?.name || value : value); option.value = value; input.append(option); } input.value = setting.value;
    } else { input = el('input'); input.type = setting.type === 'boolean' ? 'checkbox' : 'number'; if (input.type === 'checkbox') input.checked = setting.value; else { input.min = setting.min; input.max = setting.max; input.step = setting.step; input.value = setting.value; } }
    input.setAttribute('aria-label', setting.label);
    input.onchange = safe(async () => { input.disabled = true; try { await command('setting', { key: setting.key, option: setting.type === 'boolean' ? input.checked : setting.type === 'number' ? Number(input.value) : input.value }); await refreshLibrary(); notify('PC setting saved.'); } finally { input.disabled = false; } });
    label.append(input); group.append(label);
  }
}
function routes() {
  if (target === 'phone' && typeof audio.webkitShowPlaybackTargetPicker === 'function') { audio.webkitShowPlaybackTargetPicker(); notify('For Bluetooth headphones, use the output picker in iPhone Control Center.'); }
  else if (target === 'phone') notify('Use your device’s system audio output picker to choose headphones, Bluetooth or network speakers.');
  else { view('settings'); $('pcSettings').scrollIntoView({ behavior: 'smooth' }); notify('Choose the PC output device below. Paired Bluetooth devices appear as Windows audio outputs.'); }
}
async function askDj(request) {
  if (state?.role !== 'Controller') throw new Error('DJ requests require a controller invitation.');
  const requestedTarget = target;
  $('djSubmit').disabled = true; notify('DJ is choosing your set on the PC…');
  try {
    const result = await api('dj', { request, target: requestedTarget, current: current()?.id || '', voice: prefs.voice });
    remember(result.tracks); $('djReply').hidden = false; $('djReply').textContent = result.spoken; renderTracks($('djTracks'), result.tracks);
    if (requestedTarget !== target) { notify('DJ set is ready. Choose a track to play it on the selected device.'); return; }
    if (target === 'phone') {
      ++playbackEpoch; audio.pause(); phoneQueue = result.tracks; phoneIndex = 0;
      if (result.voice && prefs.voice) {
        audio.src = result.voice; audio.load(); pendingVoice = false;
        try { await audio.play(); notify(''); } catch { pendingVoice = result.voice; notify('Your DJ set is ready. Tap Play to hear the introduction.'); }
        renderPlayer();
      } else await loadPhone();
    } else { await poll(); notify('DJ set started on your PC.'); }
  } finally { $('djSubmit').disabled = false; }
}
// One listener per control; state polling never installs duplicate handlers.
document.querySelectorAll('[data-view]').forEach(b => { b.onclick = () => view(b.dataset.view); });
document.querySelectorAll('[data-library]').forEach(b => { b.onclick = () => { libraryFilter = b.dataset.library; renderLibrary(); }; });
document.querySelectorAll('[data-dj]').forEach(b => { b.onclick = safe(() => askDj(b.dataset.dj)); });
$('joinForm').onsubmit = safe(async event => { event.preventDefault(); if (!invitation) throw new Error('Scan a fresh invitation from the PC.'); await api('join', { token: invitation, name: $('name').value }); showApp(); await poll(); await refreshLibrary(); notify('Connected. Choose where you want to listen.'); });
$('leave').onclick = safe(async () => { await api('leave', {}); ++playbackEpoch; audio.pause(); audio.removeAttribute('src'); showJoin(); notify('Disconnected. Scan a new invitation to reconnect.'); });
$('settingsButton').onclick = () => { if (connected) { view('settings'); safe(refreshLibrary)(); } };
$('refreshLibrary').onclick = safe(refreshLibrary);
$('routes').onclick = $('chooseRoute').onclick = safe(routes);
$('mini').onclick = () => $('player').showModal(); $('closePlayer').onclick = () => $('player').close();
$('playPause').onclick = safe(() => togglePlayback()); $('previous').onclick = safe(() => advance(-1)); $('next').onclick = safe(() => advance());
$('seek').onchange = safe(async () => { const value = Number($('seek').value) / 1000 * duration(); if (target === 'pc') await command('seek', { value: Math.round(value * 1000) }); else audio.currentTime = value; });
$('volume').onchange = safe(async () => { const value = Number($('volume').value) / 100; if (target === 'pc') await command('volume', { value }); else { audio.volume = value; notify('iPhone may require the physical volume buttons.'); } });
$('target').onchange = safe(async () => { const next = $('target').value; ++playbackEpoch; loading = false; if (next === 'pc') { audio.pause(); pendingVoice = null; } target = next; renderPlayer(); renderQueue(); notify(next === 'phone' ? 'Playing on this device. Select a song to start a separate phone session.' : 'Controlling the PC’s current playback.'); });
$('clearPhoneQueue').onclick = () => { if (phoneIndex >= 0) { phoneQueue = phoneQueue.slice(0, phoneIndex + 1); } else phoneQueue = []; renderQueue(); };
$('favorite').onclick = safe(() => favorite(current())); $('addPlaylist').onclick = safe(() => addToPlaylist(current())); $('radio').onclick = safe(() => radio(current())); $('saveAudio').onclick = safe(() => saveAudio(current()));
$('lyrics').onclick = safe(async () => { const song = current(); if (!song) throw new Error('Choose a song first.'); notify('Finding lyrics…'); const result = await api('lyrics', { id: song.id }); if (current()?.id !== song.id) return; lyrics = result.lines; lyricTrack = song.id; $('lyricsContent').replaceChildren(...lyrics.map(line => el('p', line.text, 'lyric')), el('small', result.source, 'hint')); $('lyricsContent').hidden = false; notify(''); });
$('searchForm').onsubmit = safe(async event => { event.preventDefault(); notify('Searching…'); const found = await api('catalogSearch', { query: $('query').value, filter: $('searchFilter').value }); const songs = remember(found.filter(x => x.kind === 'track').map(x => x.track)); $('results').replaceChildren(); for (const item of found) $('results').append(item.kind === 'track' ? row(item.track, songs, songs.indexOf(item.track)) : collectionRow(item, () => openCollection(item))); notify(found.length ? '' : 'No results. Try a different search.'); });
$('djForm').onsubmit = safe(event => { event.preventDefault(); return askDj($('djRequest').value); });
$('startDj').onclick = safe(() => { view('dj'); return askDj('Start my DJ'); });
$('collectionBack').onclick = () => view('library'); $('collectionPlay').onclick = safe(() => playList(collection.tracks)); $('collectionShuffle').onclick = safe(() => playList([...collection.tracks].sort(() => Math.random() - .5)));
$('collectionRename').onclick = safe(async () => { const title = prompt('Playlist name', collection.title); if (!title?.trim()) return; if (collection.id.startsWith('phone:')) { collection.title = title; save('playlists', phoneLists); } else await command('playlistRename', { key: collection.id, text: title }); await openCollection({ ...collection, title }); await refreshLibrary(); });
$('collectionDelete').onclick = safe(async () => { if (!confirm('Delete playlist “' + collection.title + '”?')) return; if (collection.id.startsWith('phone:')) { phoneLists.splice(phoneLists.findIndex(x => x.id === collection.id), 1); save('playlists', phoneLists); } else await command('playlistDelete', { key: collection.id }); collection = null; await refreshLibrary(); view('library'); });
for (const [id, key] of Object.entries({ phoneQuality: 'quality', phoneSpeed: 'speed', phoneRepeat: 'repeat', phoneShuffle: 'shuffle', phoneVoice: 'voice', phoneMotion: 'motion' })) {
  const node = $(id); if (node.type === 'checkbox') node.checked = !!prefs[key]; else node.value = prefs[key];
  node.onchange = () => { prefs[key] = node.type === 'checkbox' ? node.checked : node.value; save('prefs', prefs); audio.playbackRate = Number(prefs.speed); renderPlayer(); };
}
audio.addEventListener('ended', safe(() => advance(1, true))); audio.addEventListener('timeupdate', renderPlayer); audio.addEventListener('play', renderPlayer); audio.addEventListener('pause', renderPlayer); audio.addEventListener('loadedmetadata', renderPlayer);
audio.addEventListener('error', () => { if (audio.getAttribute('src')) notify('This stream could not play. Select the song again to renew it, or choose another song.'); });
if ('mediaSession' in navigator) for (const [name, handler] of Object.entries({ play: () => togglePlayback('play'), pause: () => togglePlayback('pause'), nexttrack: () => advance(), previoustrack: () => advance(-1), seekto: event => { if (target === 'phone') audio.currentTime = event.seekTime; else return command('seek', { value: Math.round(event.seekTime * 1000) }); } })) try { navigator.mediaSession.setActionHandler(name, safe(handler)); } catch {}
window.addEventListener('pagehide', () => { /* Native media session owns background audio; no keepalive timers or microphone capture. */ });
setInterval(poll, 2000);
safe(async () => { $('target').value = target; if (invitation && approvedCodeConnection) { await api('join', { token: invitation, name: pairingParameters.get('name') || 'Paired phone' }); showApp(); await poll(); await refreshLibrary(); notify('Connected to your PC.'); } else if (invitation) showJoin(); else { state = await api('state'); showApp(); await poll(); await refreshLibrary(); } })();
