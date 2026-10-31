// The rental phones' part of the dashboard (added to portal-core.js for the products that have phones).
window.RECEIPT_TITLE = 'PISOPHONE REVENUE COLLECTION';
window.SAVED_NOTE = 'Saved. The phones have the new settings.';

function devicesOf(data) {
    return Array.isArray(data) ? data : (data.devices || []);
}

function batteryHtml(level, charging) {
    if (!(typeof level === 'number' && level >= 0)) return charging ? ic('bolt') + 'Charging' : '–';
    const cls = level <= 15 ? 'low' : (level <= 30 ? 'mid' : '');
    return '<span class="row-batt ' + cls + '">' + (charging ? ic('bolt') : ic('battery')) + level + '%' +
           '<span class="mini-bar"><i style="width:' + level + '%"></i></span></span>';
}

window.bannerHooks.push(function(data) {
    setBanner('legacy_key_banner',
        data.legacy_key
            ? ic('alert') + '<span class="grow"><b>This box still uses the old shared key.</b> Switch it to its own key. Phones must be set up again afterwards.</span>' +
              '<button type="button" class="btn sm" onclick="switchToOwnKey()">Switch key</button>'
            : '',
        'note warn');
});
window.checklistHooks.push(function(data) {
    return [
        { done: !data.legacy_key, text: 'Switch the box to its own key (see above).' },
        { done: devicesOf(data).some(d => d.isBound), text: 'Pair at least one rental phone (Tools, Phone slots).' }
    ];
});

const ROW_HEAD = '<div class="row head"><span>Slot</span><span>Phone</span><span>Status</span><span>Time left</span><span>Battery</span><span></span></div>';

function pendingRow(u) {
    const name = escHtml(u.name || 'New phone');
    const bat = (typeof u.battery === 'number' && u.battery >= 0) ? u.battery : -1;
    return '<div class="row pending">' +
        '<span class="tag warn">New</span>' +
        '<div class="ident"><div class="row-name">' + name + '</div><div class="row-sub">' + escHtml(u.ip) + ' · ' + escHtml(u.id) + '</div></div>' +
        '<span class="pill warn">Wants to connect</span><span></span>' +
        '<span>' + batteryHtml(bat, !!u.charging) + '</span>' +
        '<div class="row-actions"><button type="button" class="btn primary sm" data-id="' + escHtml(u.id) + '" data-ip="' + escHtml(u.ip) + '" data-name="' + name + '" onclick="pairFromRow(this)">Pair…</button></div>' +
        '</div>';
}

window.pairFromRow = function(btn) {
    showSelectSlotModalForDevice(btn.dataset.id, btn.dataset.ip, btn.dataset.name);
};

function deviceDisplayName(dev) {
    return (dev.name && dev.name !== dev.id && !dev.name.startsWith('Terminal') && (!dev.id || !dev.name.includes(dev.id))) ? dev.name : ('PisoPhone ' + dev.slotNum);
}

function deviceRow(dev) {
    const name = escHtml(deviceDisplayName(dev));
    const slot = '<span class="tag">Slot ' + dev.slotNum + '</span>';
    const unpairBtn = '<button type="button" class="btn sm" onclick="unpairSlot(' + dev.slotNum + ')">Unpair</button>';
    if (!dev.isBound) {
        return '<div class="row empty">' + slot +
            '<div class="ident"><div class="row-name muted">Empty slot</div></div>' +
            '<span class="muted">Ready for a phone</span><span></span><span></span>' +
            '<div class="row-actions"><button type="button" class="btn sm" onclick="occupySlot(' + dev.slotNum + ')">Set up</button></div></div>';
    }
    const restarts = (dev.restarts > 0) ? ' <span class="pill warn" title="The phone\'s app restarted after a crash ' + dev.restarts + (dev.restarts === 1 ? ' time' : ' times') + ' in the last 24 hours">↻ ' + dev.restarts + '</span>' : '';
    const ident = '<div class="ident"><div class="row-name">' + name + restarts + '</div><div class="row-sub">' + escHtml(dev.ip) + '</div></div>';
    if (!dev.online) {
        return '<div class="row">' + slot + ident + '<span class="pill bad">Offline</span><span></span><span></span><div class="row-actions">' + voiceCellHtml(dev) + unpairBtn + '</div></div>';
    }
    const renting = dev.time > 0;
    return '<div class="row">' + slot + ident +
        '<span class="pill ' + (renting ? 'ok' : '') + '">' + (renting ? 'Renting' : 'Locked') + '</span>' +
        '<span class="row-time">' + fmtTime(dev.time) + '</span>' +
        '<span>' + batteryHtml(typeof dev.battery === 'number' ? dev.battery : -1, !!dev.charging) + '</span>' +
        '<div class="row-actions">' + voiceCellHtml(dev) + '<button type="button" class="btn sm' + (window.locating[dev.ip] ? ' danger' : '') +
        '" data-ip="' + escHtml(dev.ip) + '" onclick="toggleLocate(this)">' + (window.locating[dev.ip] ? 'Stop' : 'Locate') +
        '</button><button type="button" class="btn sm" onclick="unpairSlot(' + dev.slotNum + ')">Unpair</button></div></div>';
}

window.locating = window.locating || {};

window.toggleLocate = function(btn) {
    const ip = btn.dataset.ip;
    const stop = !!window.locating[ip];
    btn.disabled = true;
    fetch('/api/locate?ip=' + encodeURIComponent(ip) + (stop ? '&mode=stop' : ''), { method: 'POST' })
        .then(res => res.json())
        .then(data => {
            if (!data.success) throw new Error(data.error || 'failed');
            if (stop) delete window.locating[ip];
            else window.locating[ip] = true;
            btn.textContent = stop ? 'Locate' : 'Stop';
            btn.classList.toggle('danger', !stop);
            notify(stop ? 'Alarm stopped.' : 'Phone is ringing. It stops by itself after a minute.', 'ok');
            if (!stop) setTimeout(() => { delete window.locating[ip]; }, 60000);
        })
        .catch(err => notify('Could not reach the phone: ' + err.message, 'bad'))
        .finally(() => { btn.disabled = false; });
};

// ---- Voice message (feature 056): hold the mic on a phone's row (up to 15 s), let go for a paper plane, tap it to send ----
// The recording stays in this browser until the plane is tapped; the box passes it on to the phone and keeps nothing.
// The rows are drawn again every 3 s, so a row's voice cell holds all its parts and only its data-phase changes; the release of the
// mic is caught on the document, and the rows are not redrawn while a recording runs.
const VOICE_MAX_MS = 15000;
const VOICE_MIN_MS = 500;
const VOICE_MAX_SAMPLES = 120000;
window.voiceState = window.voiceState || {};
let voiceRec = null;

window.voiceIsRecording = function() { return !!voiceRec; };

function voiceGet(slot) {
    if (!window.voiceState[slot]) window.voiceState[slot] = { phase: 'idle', seconds: 0, note: '', noteKind: '', clip: null };
    return window.voiceState[slot];
}

function voiceIcon(name) {
    const paths = {
        mic: '<rect x="9" y="2" width="6" height="12" rx="3"/><path d="M5 11a7 7 0 0 0 14 0M12 18v4"/>',
        plane: '<path d="M22 2 11 13"/><path d="M22 2 15 22l-4-9-9-4 20-7z"/>',
        x: '<path d="M18 6 6 18M6 6l12 12"/>'
    };
    return '<svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + paths[name] + '</svg>';
}

// IMA ADPCM, 8 kHz mono, 256-byte blocks of 505 samples: the same bytes as protocol/gen_voice_vectors.py (protocol/fixtures/box_phone_voice_v1.json).
const VOICE_STEP = [7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
    130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166,
    1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845,
    8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767];
const VOICE_INDEX = [-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8];

window.voiceEncode = function(pcm) {
    const n = pcm.length;
    const blocks = Math.ceil(n / 505);
    const last = n - 505 * (blocks - 1);
    const out = new Uint8Array(8 + 256 * (blocks - 1) + 4 + (last >> 1));
    out.set([1, 1, 8000 >> 8, 8000 & 255, (n >>> 24) & 255, (n >>> 16) & 255, (n >>> 8) & 255, n & 255]);
    let pos = 8;
    let index = 0;
    for (let start = 0; start < n; start += 505) {
        const count = Math.min(505, n - start);
        let pred = pcm[start];
        out[pos++] = pred & 255;
        out[pos++] = (pred >> 8) & 255;
        out[pos++] = index;
        out[pos++] = 0;
        let half = -1;
        for (let i = 1; i < count; i++) {
            let step = VOICE_STEP[index];
            let diff = pcm[start + i] - pred;
            let code = 0;
            if (diff < 0) { code = 8; diff = -diff; }
            let vp = step >> 3;
            if (diff >= step) { code |= 4; diff -= step; vp += step; }
            step >>= 1;
            if (diff >= step) { code |= 2; diff -= step; vp += step; }
            step >>= 1;
            if (diff >= step) { code |= 1; vp += step; }
            pred = Math.max(-32768, Math.min(32767, (code & 8) ? pred - vp : pred + vp));
            index = Math.max(0, Math.min(88, index + VOICE_INDEX[code]));
            if (half < 0) { half = code; } else { out[pos++] = half | (code << 4); half = -1; }
        }
        if (half >= 0) out[pos++] = half;
    }
    return out;
};

// What the owner is told when a recording or a send cannot go on (the words of the box's answers are in WebServerVoice.cpp).
function voiceSendMessage(word, name) {
    switch (word) {
        case 'unknown_phone': return name + ' is not paired to this box any more.';
        case 'old_app': return 'Update the app on this phone to send voice messages.';
        case 'busy':
        case 'low_memory': return 'The box is busy, try again in a few seconds.';
        case 'bad_length':
        case 'bad_clip': return 'This recording could not be sent. Throw it away and record it again.';
        case 'no_answer': return name + ' did not answer. The message is still here: tap the plane to try again.';
        case 'cut_off':
        case 'timeout':
        case 'phone_refused': return 'The message was cut off. ' + name + ' did not get all of it. Tap the plane to send it again.';
        case 'signed_out': return 'You are signed out. Sign in again; the message is still here.';
        default: return 'The box could not send the message (' + word + '). Tap the plane to try again.';
    }
}

function voiceMicMessage(err) {
    if (window.top !== window.self) return 'The microphone does not work inside the router console. Open the coin box page on its own (the link under the box page), then try again.';
    const name = err && err.name;
    if (name === 'NotAllowedError' || name === 'SecurityError') return 'The microphone is blocked for this page. Allow it in the browser (the icon next to the address), then hold the mic again.';
    if (name === 'NotFoundError') return 'No microphone was found on this device.';
    if (name === 'NotReadableError') return 'The microphone is being used by another app.';
    return 'Could not start the microphone' + (name ? ' (' + name + ')' : '') + '.';
}

// Browsers give the microphone only to https:// pages or localhost, and the box page is plain http:// on the shop network.
function voiceBlockedReason() {
    if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia && window.isSecureContext) return '';
    if (window.top !== window.self) return voiceMicMessage(null);
    if (!(window.AudioContext || window.webkitAudioContext)) return 'This browser cannot record. Use Chrome on a computer or an Android phone.';
    return 'The microphone is switched off for this address. In Chrome open chrome://flags/#unsafely-treat-insecure-origin-as-secure, add ' + location.origin +
        ', set it to Enabled and restart Chrome. (An iPhone cannot record on this page.)';
}

function voiceCellHtml(dev) {
    const slot = dev.slotNum;
    const st = voiceGet(slot);
    const off = !dev.voice;
    const phase = off ? 'off' : st.phase;
    const d = ' data-voice-slot="' + slot + '"';
    return '<span class="voice-cell"' + d + ' data-phase="' + phase + '">' +
        '<button type="button" class="btn sm voice-btn mic' + (off ? ' off' : '') + '"' + d + ' data-voice="' + (off ? 'old' : 'mic') + '"' +
        ' aria-label="Voice message" title="' + (off ? 'Update the app on this phone to send voice messages' : 'Hold to record a voice message (up to 15 s)') + '">' +
        voiceIcon('mic') + '<span class="voice-count">' + (st.phase === 'recording' ? st.seconds + ' s' : '') + '</span></button>' +
        '<button type="button" class="btn sm primary voice-btn plane"' + d + ' data-voice="send" aria-label="Send the voice message" title="Send the voice message">' +
        voiceIcon('plane') + '<span class="voice-len">' + (st.clip ? Math.max(1, Math.round(st.lengthSec)) + ' s' : '') + '</span></button>' +
        '<button type="button" class="btn sm voice-btn cross"' + d + ' data-voice="discard" aria-label="Throw the recording away" title="Throw the recording away">' + voiceIcon('x') + '</button>' +
        '<span class="voice-busy">Sending…</span>' +
        '<span class="voice-sent">' + ic('check') + 'Sent</span>' +
        '<span class="voice-note ' + (st.noteKind || '') + '" role="status">' + escHtml(st.note || '') + '</span></span>';
}

// Changes what the voice cells of a phone show, without replacing any element.
function paintVoice(slot) {
    const st = voiceGet(slot);
    document.querySelectorAll('.voice-cell[data-voice-slot="' + slot + '"]').forEach(cell => {
        if (cell.dataset.phase !== 'off') cell.dataset.phase = st.phase;
        const count = cell.querySelector('.voice-count');
        if (count) count.textContent = st.phase === 'recording' ? st.seconds + ' s' : '';
        const len = cell.querySelector('.voice-len');
        if (len) len.textContent = st.clip ? Math.max(1, Math.round(st.lengthSec)) + ' s' : '';
        const note = cell.querySelector('.voice-note');
        if (note) {
            note.textContent = st.note || '';
            note.className = 'voice-note ' + (st.noteKind || '');
        }
    });
}

function voiceNote(slot, text, kind) {
    const st = voiceGet(slot);
    st.note = text;
    st.noteKind = kind || '';
    paintVoice(slot);
    if (text) notify(text, kind === 'bad' ? 'bad' : 'ok');
}

function voiceDeviceOf(slot) {
    return (window.latestDevicesList || []).find(d => d.slotNum === slot) || null;
}

function voiceCapture(rec) {
    const AC = window.AudioContext || window.webkitAudioContext;
    const ctx = new AC();
    rec.ctx = ctx;
    rec.rate = ctx.sampleRate;
    const source = ctx.createMediaStreamSource(rec.stream);
    const keep = function(block) { rec.blocks.push(block); };
    if (window.AudioWorkletNode && ctx.audioWorklet) {
        const code = 'class P extends AudioWorkletProcessor{process(i){const c=i[0][0];if(c)this.port.postMessage(c.slice());return true}}registerProcessor("piso-pcm",P)';
        const url = URL.createObjectURL(new Blob([code], { type: 'application/javascript' }));
        return ctx.audioWorklet.addModule(url).then(function() {
            URL.revokeObjectURL(url);
            const node = new AudioWorkletNode(ctx, 'piso-pcm');
            node.port.onmessage = function(e) { keep(e.data); };
            source.connect(node);
            rec.node = node;
        });
    }
    const node = ctx.createScriptProcessor(4096, 1, 1);
    node.onaudioprocess = function(e) { keep(e.inputBuffer.getChannelData(0).slice()); };
    source.connect(node);
    node.connect(ctx.destination);
    rec.node = node;
    return Promise.resolve();
}

function voiceTo8k(samples, rate) {
    const keepSrc = Math.floor(VOICE_MAX_MS / 1000 * rate);
    if (samples.length > keepSrc) samples = samples.subarray(0, keepSrc);
    const toInt16 = function(f) {
        const n = Math.min(f.length, VOICE_MAX_SAMPLES);
        const out = new Int16Array(n);
        for (let i = 0; i < n; i++) {
            const v = Math.max(-1, Math.min(1, f[i]));
            out[i] = Math.max(-32768, Math.min(32767, Math.round(v * 32767)));
        }
        return out;
    };
    if (rate === 8000) return Promise.resolve(toInt16(samples));
    const outLen = Math.max(1, Math.round(samples.length * 8000 / rate));
    const off = new OfflineAudioContext(1, outLen, 8000);
    const buffer = off.createBuffer(1, samples.length, rate);
    buffer.copyToChannel(samples, 0);
    const source = off.createBufferSource();
    source.buffer = buffer;
    source.connect(off.destination);
    source.start();
    return off.startRendering().then(r => toInt16(r.getChannelData(0)));
}

function voiceStart(slot) {
    if (voiceRec) return;
    const st = voiceGet(slot);
    if (st.phase === 'sending' || st.phase === 'recorded' || st.phase === 'failed') return;
    const blocked = voiceBlockedReason();
    if (blocked) { voiceNote(slot, blocked, 'bad'); return; }
    const rec = { slot: slot, released: false, startedAt: 0, blocks: [], done: false, timer: null, tick: null, stream: null, ctx: null, node: null, rate: 0 };
    voiceRec = rec;
    st.phase = 'recording';
    st.seconds = 0;
    st.note = '';
    st.noteKind = '';
    paintVoice(slot);
    const abandon = function(err) {
        voiceRec = null;
        if (rec.stream) rec.stream.getTracks().forEach(t => t.stop());
        if (rec.ctx) rec.ctx.close().catch(() => {});
        st.phase = 'idle';
        paintVoice(slot);
        voiceNote(slot, voiceMicMessage(err), 'bad');
        window.drawPhoneRows();
    };
    navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true } }).then(function(stream) {
        rec.stream = stream;
        return voiceCapture(rec);
    }).then(function() {
        rec.startedAt = performance.now();
        rec.tick = setInterval(function() {
            st.seconds = Math.floor((performance.now() - rec.startedAt) / 1000);
            paintVoice(slot);
        }, 100);
        rec.timer = setTimeout(voiceRelease, VOICE_MAX_MS);
        if (rec.released) voiceFinish(rec); // let go before the microphone was ready: a short press
    }).catch(abandon);
}

// The mic was let go (or 15 s ran out): the recording ends.
function voiceRelease() {
    const rec = voiceRec;
    if (!rec) return;
    rec.released = true;
    if (rec.startedAt) voiceFinish(rec);
}

function voiceFinish(rec) {
    if (rec.done) return;
    rec.done = true;
    clearTimeout(rec.timer);
    clearInterval(rec.tick);
    const st = voiceGet(rec.slot);
    const heldMs = performance.now() - rec.startedAt;
    const stopAll = function() {
        if (rec.node) { try { rec.node.disconnect(); } catch (e) { /* already gone */ } }
        if (rec.stream) rec.stream.getTracks().forEach(t => t.stop());
        if (rec.ctx) rec.ctx.close().catch(() => {});
    };
    stopAll();
    const total = rec.blocks.reduce((n, b) => n + b.length, 0);
    const samples = new Float32Array(total);
    let at = 0;
    rec.blocks.forEach(b => { samples.set(b, at); at += b.length; });
    const finishUi = function() {
        voiceRec = null;
        paintVoice(rec.slot);
        window.drawPhoneRows();
    };
    if (heldMs < VOICE_MIN_MS || total === 0) {
        st.phase = 'idle';
        finishUi();
        voiceNote(rec.slot, 'Hold the mic while you speak.', '');
        return;
    }
    voiceTo8k(samples, rec.rate).then(function(pcm) {
        if (pcm.length < 1) throw new Error('empty');
        st.clip = window.voiceEncode(pcm);
        st.lengthSec = pcm.length / 8000;
        st.phase = 'recorded';
        finishUi();
    }).catch(function() {
        st.phase = 'idle';
        st.clip = null;
        finishUi();
        voiceNote(rec.slot, 'The recording did not work. Try again.', 'bad');
    });
}

function voiceDiscard(slot) {
    const st = voiceGet(slot);
    if (st.phase === 'sending') return;
    st.phase = 'idle';
    st.clip = null;
    st.note = '';
    st.noteKind = '';
    paintVoice(slot);
}

function voiceFail(slot, word) {
    const st = voiceGet(slot);
    const dev = voiceDeviceOf(slot);
    st.phase = 'failed';
    voiceNote(slot, voiceSendMessage(word, dev ? deviceDisplayName(dev) : 'The phone'), 'bad');
}

function voiceAnswer(res) {
    if (res.status === 401) {
        signedOutAnswer();
        return Promise.reject({ word: 'signed_out' });
    }
    return res.json().catch(() => ({})).then(data => {
        if (!res.ok || !data.success) return Promise.reject({ word: data.error || ('http_' + res.status) });
        return data;
    });
}

function voiceSend(slot) {
    const st = voiceGet(slot);
    const dev = voiceDeviceOf(slot);
    if (!st.clip || (st.phase !== 'recorded' && st.phase !== 'failed')) return;
    if (!dev) { voiceFail(slot, 'unknown_phone'); return; }
    const query = '?ip=' + encodeURIComponent(dev.ip) + '&len=' + st.clip.length;
    st.phase = 'sending';
    st.note = '';
    st.noteKind = '';
    paintVoice(slot);
    // First a question that is answered at once (does the phone answer, is the box free), then the clip itself.
    fetch('/api/voice/ready' + query, { method: 'POST' }).then(voiceAnswer).then(function() {
        const form = new FormData();
        form.append('clip', new Blob([st.clip], { type: 'application/octet-stream' }), 'clip.bin');
        return fetch('/api/voice' + query, { method: 'POST', body: form });
    }).then(voiceAnswer).then(function() {
        st.phase = 'sent';
        st.clip = null;
        paintVoice(slot);
        notify('Voice message sent to ' + deviceDisplayName(dev) + '.', 'ok');
        setTimeout(function() {
            if (st.phase === 'sent') { st.phase = 'idle'; paintVoice(slot); }
        }, 2000);
    }).catch(function(err) {
        voiceFail(slot, err && err.word ? err.word : 'unreachable');
        if (!(err && err.word)) voiceNote(slot, 'Could not reach the box. The message is still here: tap the plane to try again.', 'bad');
    });
}

document.addEventListener('pointerdown', function(e) {
    const btn = e.target.closest ? e.target.closest('[data-voice="mic"]') : null;
    if (!btn || e.button > 0) return;
    e.preventDefault();
    voiceStart(Number(btn.dataset.voiceSlot));
});
['pointerup', 'pointercancel'].forEach(type => document.addEventListener(type, voiceRelease));
window.addEventListener('blur', voiceRelease);
document.addEventListener('visibilitychange', function() { if (document.hidden) voiceRelease(); });
document.addEventListener('contextmenu', function(e) {
    if (e.target.closest && e.target.closest('.voice-btn')) e.preventDefault();
});
document.addEventListener('keydown', function(e) {
    const btn = e.target.closest ? e.target.closest('[data-voice="mic"]') : null;
    if (btn && (e.key === ' ' || e.key === 'Enter') && !e.repeat) { e.preventDefault(); voiceStart(Number(btn.dataset.voiceSlot)); }
});
document.addEventListener('keyup', function(e) {
    if ((e.key === ' ' || e.key === 'Enter') && voiceRec) voiceRelease();
});
document.addEventListener('click', function(e) {
    const btn = e.target.closest ? e.target.closest('[data-voice]') : null;
    if (!btn) return;
    const slot = Number(btn.dataset.voiceSlot);
    const kind = btn.dataset.voice;
    if (kind === 'send') voiceSend(slot);
    else if (kind === 'discard') voiceDiscard(slot);
    else if (kind === 'old') voiceNote(slot, 'Update the app on this phone to send voice messages.', 'bad');
});

const ACCT_HEAD = '<div class="row head acct"><span>Player</span><span>Time left</span><span>Status</span><span></span></div>';

function acctRow(a) {
    const who = a.name ? escHtml(a.name) : '<span class="muted">No name yet</span>';
    let status;
    if (a.slot > 0) status = '<span class="pill ok">Playing on slot ' + a.slot + '</span>';
    else if (a.idle_min < 0) status = '<span class="muted">–</span>';
    else status = '<span class="muted">Last used ' + (a.idle_min < 60 ? a.idle_min + ' min' : (a.idle_min < 2880 ? Math.round(a.idle_min / 60) + ' h' : Math.round(a.idle_min / 1440) + ' days')) + ' ago</span>';
    const busy = a.slot > 0 ? ' disabled title="Signed in on a phone"' : '';
    const id = Number(a.id);
    return '<div class="row acct">' +
        '<div class="ident"><div class="row-name">' + who + '</div><div class="row-sub">Card No. ' + String(id).padStart(5, '0') + '</div></div>' +
        '<span class="row-time">' + fmtTime(a.balance_sec) + '</span>' +
        status +
        '<div class="row-actions">' +
        '<button type="button" class="btn sm" data-id="' + id + '"' + busy + ' onclick="accountAdjust(this, 1)">+ Time</button>' +
        '<button type="button" class="btn sm" data-id="' + id + '"' + busy + ' onclick="accountAdjust(this, -1)">– Time</button>' +
        '<button type="button" class="btn sm danger" data-id="' + id + '"' + busy + ' onclick="accountDelete(this)">Delete</button>' +
        '</div></div>';
}

window.fetchAccounts = function() {
    const box = document.getElementById('accounts_container');
    if (!box) return;
    fetch('/api/accounts')
        .then(res => { if (res.status === 401) { signedOutAnswer(); throw new Error('signed out'); } signedOutStrikes = 0; if (!res.ok) throw new Error(res.status === 503 ? 'Accounts are off on this box' : 'HTTP ' + res.status); return res.json(); })
        .then(data => {
            const list = data.accounts || [];
            const badge = document.getElementById('accounts_count');
            if (badge) badge.textContent = list.length + ' of ' + data.max;
            const idEl = document.getElementById('accounts_box_id');
            if (idEl) idEl.textContent = data.box_id || '';
            let notes = '';
            if (!data.cards_ready) {
                notes += '<div class="note bad"><svg class="ic"><use href="#i-alert"/></svg><span class="grow"><b>No card key is installed, so every QR card is refused.</b> Run scripts/make_card_key.py on your computer, commit the public key it writes, then rebuild and flash this box.</span></div>';
            }
            if (data.failing) {
                notes += '<div class="note bad"><svg class="ic"><use href="#i-alert"/></svg><span class="grow"><b>Accounts are not being saved to the box\'s memory.</b> Changes since the last good save would be lost if the box lost power. See Tools, Diagnostics.</span></div>';
            }
            box.innerHTML = notes + (list.length
                ? ACCT_HEAD + list.map(acctRow).join('')
                : '<div class="row-empty-msg">No accounts yet. A player\'s account is created the first time they scan a card on a phone.</div>');
        })
        .catch(err => { box.innerHTML = '<div class="row-empty-msg">' + escHtml(err.message) + '</div>'; });
};
document.addEventListener('DOMContentLoaded', fetchAccounts);
pollTimers.push(setInterval(fetchAccounts, 15000));

function accountPost(path, id, extra, done) {
    fetch(path, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: 'id=' + encodeURIComponent(id) + (extra || '')
    })
        .then(res => res.json())
        .then(data => {
            if (data.success) { notify(done, 'ok'); fetchAccounts(); }
            else notify('Not changed: ' + (data.error === 'ALREADY_SIGNED_IN' ? 'the player is signed in on a phone.' : (data.error || 'unknown error')), 'bad');
        })
        .catch(err => notify('Could not reach the box: ' + err.message, 'bad'));
}

window.accountAdjust = function(btn, sign) {
    const id = btn.dataset.id;
    const m = parseInt(prompt((sign > 0 ? 'Add how many minutes to card ' : 'Remove how many minutes from card ') + id + '?', '30'), 10);
    if (!m || m < 1) return;
    accountPost('/api/accounts/adjust', id, '&minutes=' + (sign * m), (sign > 0 ? 'Added ' : 'Removed ') + m + ' min ' + (sign > 0 ? 'to ' : 'from ') + 'card ' + id + '.');
};

window.accountDelete = function(btn) {
    const id = btn.dataset.id;
    if (!confirm('Delete the account for card ' + id + '? Any time left in it is lost, and the card gets no new starter time: scanning it again gives an empty account.')) return;
    accountPost('/api/accounts/delete', id, '', 'Deleted the account for card ' + id + '.');
};

window.statusHooks.push(function(data) {
    const devices = devicesOf(data);
    const container = document.getElementById('live_devices_container');
        if (!container) return;

        window.latestDevicesList = devices;
        const unassigned = data.unassigned_devices || [];
        window.unassignedDevices = unassigned;

        const paired = devices.filter(d => d.isBound);
        const online = paired.filter(d => d.online).length;
        const stat = document.getElementById('stat_phones');
        if (stat) stat.textContent = paired.length;
        const sub = document.getElementById('stat_phones_sub');
        if (sub) sub.textContent = paired.length === 0 ? 'None paired yet' : (online + ' online');

        const reqBadge = document.getElementById('pending_requests_badge');
        if (reqBadge) {
            reqBadge.classList.toggle('hidden', unassigned.length === 0);
            reqBadge.textContent = unassigned.length + ' pair request' + (unassigned.length > 1 ? 's' : '');
        }

        // (a recording in progress keeps the button under the finger: the rows are drawn again when it ends)
        if (!window.voiceIsRecording()) window.drawPhoneRows();
});

window.drawPhoneRows = function() {
    const container = document.getElementById('live_devices_container');
    if (!container) return;
    let html = (window.unassignedDevices || []).map(pendingRow).join('');
    (window.latestDevicesList || []).forEach(dev => {
        html += deviceRow(dev);
    });
    container.innerHTML = html
        ? ROW_HEAD + html
        : '<div class="row-empty-msg">No phones yet. Open Tools, Phone slots, to set one up.</div>';
};

function showMatchResult(kind, html) {
    const el = document.getElementById('match_qual_result');
    if (!el) return;
    el.className = 'note ' + kind;
    el.innerHTML = ic(kind === 'ok' ? 'check' : (kind === 'bad' ? 'alert' : 'clock')) + '<span class="grow">' + html + '</span>';
}

window.checkMatchQualification = function() {
    const p1 = document.getElementById('p1_select') ? document.getElementById('p1_select').value : '';
    const p2 = document.getElementById('p2_select') ? document.getElementById('p2_select').value : '';
    const mins = document.getElementById('match_mins_input') ? document.getElementById('match_mins_input').value : '15';
    if (!p1 || !p2) return showMatchResult('bad', 'Choose both players.');
    if (p1 === p2) return showMatchResult('bad', 'Player 1 and Player 2 must be different phones.');
    showMatchResult('', 'Checking the time on both phones…');

    fetch('/check_qualification?p1=' + encodeURIComponent(p1) + '&p2=' + encodeURIComponent(p2) + '&minutes=' + encodeURIComponent(mins))
        .then(res => res.json())
        .then(data => {
            if (data && data.success) {
                const lines = '<br>Player 1 (' + escHtml(data.p1_ip || p1) + '): <b>' + escHtml(data.p1_formatted) + '</b><br>Player 2 (' + escHtml(data.p2_ip || p2) + '): <b>' + escHtml(data.p2_formatted) + '</b>';
                if (data.qualified) {
                    showMatchResult('ok', '<b>Both players can stake ' + escHtml(data.stake_minutes || mins) + ' minutes.</b>' + lines);
                } else {
                    showMatchResult('bad', '<b>Not enough time to stake.</b>' + lines + '<br>' + escHtml(data.message || data.error || ''));
                }
            } else {
                showMatchResult('bad', escHtml(data && data.error ? data.error : 'Could not check the players.'));
            }
        }).catch(err => showMatchResult('bad', 'Could not reach the box: ' + escHtml(err)));
};

window.toggle1v1MatchBox = function() {
    const content = document.getElementById('match_collapsible_content');
    const btn = document.getElementById('match_toggle_btn');
    if (!content) return;
    const open = content.classList.toggle('hidden') === false;
    if (btn) btn.innerHTML = open ? ic('x') + 'Close' : ic('users') + 'Start a match';
};
