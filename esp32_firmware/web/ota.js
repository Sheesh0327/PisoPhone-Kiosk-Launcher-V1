    // kind: 'success', 'error' or 'info'
    window.showStatus = function(text, type) {
        const box = document.getElementById('status_message');
        box.className = 'note ' + (type === 'success' ? 'ok' : (type === 'error' ? 'bad' : ''));
        box.innerHTML = ic(type === 'success' ? 'check' : (type === 'error' ? 'alert' : 'info')) + '<span class="grow">' + text + '</span>';
    };

    function setProgress(pct, state) {
        const wrap = document.getElementById('progress_wrapper');
        const bar = document.getElementById('progress_bar');
        wrap.className = 'progress' + (state ? ' ' + state : '');
        wrap.style.display = 'block';
        bar.style.width = pct + '%';
    }

    // Which firmware file this board needs and which version it runs; filled in by the ESP32
    // when it serves this page.
    const CHIP_ID = (window.PISO_OTA && window.PISO_OTA.chip) || '';
    const RUNNING_VERSION = (window.PISO_OTA && window.PISO_OTA.version) || '';

    // SHA-256 for the OTA page. crypto.subtle only exists on https/localhost pages, and this page is
    // served over plain http by the ESP32, so a small pure-JS fallback is needed.
    function sha256Bytes(bytes) {
        const K = new Uint32Array([
            0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
            0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
            0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
            0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
            0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
            0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
            0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
            0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2]);
        const H = new Uint32Array([0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19]);
        const len = bytes.length;
        const padded = new Uint8Array(((len + 9 + 63) >> 6) << 6);
        padded.set(bytes);
        padded[len] = 0x80;
        const view = new DataView(padded.buffer);
        view.setUint32(padded.length - 8, Math.floor(len / 0x20000000), false);
        view.setUint32(padded.length - 4, (len << 3) >>> 0, false);
        const W = new Uint32Array(64);
        const rotr = (x, n) => (x >>> n) | (x << (32 - n));
        for (let off = 0; off < padded.length; off += 64) {
            for (let i = 0; i < 16; i++) W[i] = view.getUint32(off + i * 4, false);
            for (let i = 16; i < 64; i++) {
                const s0 = rotr(W[i-15], 7) ^ rotr(W[i-15], 18) ^ (W[i-15] >>> 3);
                const s1 = rotr(W[i-2], 17) ^ rotr(W[i-2], 19) ^ (W[i-2] >>> 10);
                W[i] = (W[i-16] + s0 + W[i-7] + s1) >>> 0;
            }
            let a=H[0],b=H[1],c=H[2],d=H[3],e=H[4],f=H[5],g=H[6],h=H[7];
            for (let i = 0; i < 64; i++) {
                const S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
                const ch = (e & f) ^ (~e & g);
                const t1 = (h + S1 + ch + K[i] + W[i]) >>> 0;
                const S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
                const maj = (a & b) ^ (a & c) ^ (b & c);
                const t2 = (S0 + maj) >>> 0;
                h=g; g=f; f=e; e=(d + t1) >>> 0; d=c; c=b; b=a; a=(t1 + t2) >>> 0;
            }
            H[0]=(H[0]+a)>>>0; H[1]=(H[1]+b)>>>0; H[2]=(H[2]+c)>>>0; H[3]=(H[3]+d)>>>0;
            H[4]=(H[4]+e)>>>0; H[5]=(H[5]+f)>>>0; H[6]=(H[6]+g)>>>0; H[7]=(H[7]+h)>>>0;
        }
        return Array.from(H).map(x => x.toString(16).padStart(8, '0')).join('');
    }

    async function sha256Hex(buffer) {
        const bytes = new Uint8Array(buffer);
        if (window.crypto && window.crypto.subtle) {
            const digest = await window.crypto.subtle.digest('SHA-256', bytes);
            return Array.from(new Uint8Array(digest)).map(b => b.toString(16).padStart(2, '0')).join('');
        }
        return sha256Bytes(bytes);
    }


    // The controller only flashes an image that matches a manifest signed with the owner's key. Send it first.
    async function postOtaManifest(m) {
        const body = new URLSearchParams();
        ['chip', 'version', 'sha256', 'size', 'sig'].forEach(function(k) { body.append(k, String(m[k] === undefined ? '' : m[k])); });
        const res = await fetch('/api/ota/manifest', { method: 'POST', body: body });
        let msg = '';
        try { msg = (await res.json()).message || ''; } catch (e) { /* not JSON */ }
        if (!res.ok) throw new Error(msg || ('The controller refused the update manifest (HTTP ' + res.status + ').'));
    }

    async function fetchChipFirmwareInfo() {
        const res = await fetch('https://pisophone.pages.dev/update/firmware.json', { cache: 'no-store' });
        if (!res.ok) throw new Error('firmware.json returned HTTP ' + res.status);
        const data = await res.json();
        const entry = data.chips && data.chips[CHIP_ID];
        if (!entry || !entry.url) throw new Error('No firmware is published for chip "' + CHIP_ID + '" yet.');
        return { data: data, entry: entry };
    }

    window.checkCloudUpdate = async function() {
        const badge = document.getElementById('cloud_ver_badge');
        if (badge) badge.textContent = 'Checking…';
        try {
            const info = await fetchChipFirmwareInfo();
            const data = info.data;
            if (badge) badge.textContent = 'Latest v' + (data.version || '?');
            if (data.version === RUNNING_VERSION) {
                showStatus('<b>Up to date.</b> This box already runs v' + RUNNING_VERSION + ' (' + CHIP_ID + ').', 'success');
            } else {
                showStatus('<b>Update available:</b> v' + (data.version || '?') + ' (this box runs v' + RUNNING_VERSION + ', chip ' + CHIP_ID + ')<br>' + escHtml(data.changelog || 'Ready to install.'), 'info');
            }
        } catch(e) {
            if (badge) badge.textContent = 'Unknown';
            showStatus('Could not check the version. You can still try to install.', 'info');
        }
    };

    window.installCloudFirmware = async function() {
        const cloudBtn = document.getElementById('cloud_update_btn');
        const progressWrapper = document.getElementById('progress_wrapper');
        const progressBar = document.getElementById('progress_bar');
        
        let startInfo;
        try {
            startInfo = await fetchChipFirmwareInfo();
        } catch (e) {
            showStatus('<b>Cannot update.</b> ' + escHtml(e.message), 'error');
            return;
        }
        const sameVersion = startInfo.data.version === RUNNING_VERSION;
        const question = sameVersion
            ? 'This controller already runs v' + RUNNING_VERSION + '. Download and flash it again anyway?'
            : 'Download and flash v' + startInfo.data.version + ' (currently v' + RUNNING_VERSION + ')?';
        if (!confirm(question)) return;
        
        if (cloudBtn) cloudBtn.disabled = true;
        
        setProgress(0);
        
        showStatus('Downloading the update…', 'info');
        
        try {
            const info = startInfo;
            const expectedSha = (info.entry.sha256 || '').toLowerCase();
            if (!/^[0-9a-f]{64}$/.test(expectedSha)) {
                throw new Error('No checksum is published for this firmware yet, so it cannot be verified. Nothing was flashed.');
            }
            const fwRes = await fetch(info.entry.url, { cache: 'no-store' });
            if (!fwRes.ok) {
                throw new Error('Server returned HTTP ' + fwRes.status + ' when downloading update.');
            }
            const fwBlob = await fwRes.blob();
            // Every ESP32 app image starts with magic byte 0xE9. Anything else is a corrupt file or
            // an HTML error page, and must never reach the flash.
            const magic = new Uint8Array(await fwBlob.slice(0, 1).arrayBuffer());
            if (fwBlob.size < 100000 || magic[0] !== 0xE9) {
                throw new Error('Downloaded file is not a valid ESP32 firmware image (' + fwBlob.size + ' bytes). Nothing was flashed.');
            }
            showStatus('Checking the download…', 'info');
            const actualSha = await sha256Hex(await fwBlob.arrayBuffer());
            if (actualSha !== expectedSha) {
                throw new Error('Checksum mismatch: the download is corrupt or not the published build. Nothing was flashed.');
            }
            
            showStatus('Checking the signature…', 'info');
            await postOtaManifest({ chip: CHIP_ID, version: info.data.version, sha256: expectedSha, size: fwBlob.size, sig: info.entry.sig || '' });

            showStatus('Download complete (' + (fwBlob.size/1024).toFixed(1) + ' KB). Installing…', 'info');
            
            const formData = new FormData();
            formData.append('update', fwBlob, 'firmware.bin');
            
            const xhr = new XMLHttpRequest();
            xhr.open('POST', '/update', true);
            
            xhr.upload.addEventListener('progress', function(e) {
                if (e.lengthComputable) {
                    const percent = (e.loaded / e.total) * 100;
                    setProgress(percent);
                    showStatus('Installing: ' + Math.round(percent) + '%' + (percent >= 99 ? '. Do not switch the box off.' : ''), 'info');
                }
            });
            
            xhr.onload = function() {
                if (xhr.status === 200) {
                    setProgress(100, 'ok');
                    showStatus('<b>Updated.</b> The box is restarting and this page returns to the dashboard in 5 seconds.', 'success');
                    setTimeout(function() { window.location.href = '/'; }, 5000);
                } else {
                    setProgress(100, 'bad');
                    showStatus('<b>The update failed.</b> ' + escHtml(xhr.responseText || 'The box could not install the file.'), 'error');
                    if (cloudBtn) cloudBtn.disabled = false;
                }
            };
            
            xhr.onerror = function() {
                setProgress(100, 'bad');
                showStatus('<b>Lost the connection to the box while installing.</b>', 'error');
                if (cloudBtn) cloudBtn.disabled = false;
            };
            
            xhr.send(formData);
        } catch(err) {
            setProgress(100, 'bad');
            showStatus('<b>The update failed.</b> ' + escHtml(err.message), 'error');
            if (cloudBtn) cloudBtn.disabled = false;
        }
    };

    window.uploadLocalFirmware = async function() {
        const fileInput = document.getElementById('local_file_input');
        const uploadBtn = document.getElementById('local_upload_btn');
        const progressWrapper = document.getElementById('progress_wrapper');
        const progressBar = document.getElementById('progress_bar');

        if (!fileInput.files || fileInput.files.length === 0) {
            notify('Choose a firmware.bin file first.', 'bad');
            return;
        }

        const file = fileInput.files[0];
        if (!confirm('Flash the local firmware file "' + file.name + '"?')) return;

        if (uploadBtn) uploadBtn.disabled = true;

        setProgress(0);

        showStatus('Preparing to install…', 'info');

        const manifestInput = document.getElementById('local_manifest_input');
        if (manifestInput && manifestInput.files && manifestInput.files.length > 0) {
            try {
                await postOtaManifest(JSON.parse(await manifestInput.files[0].text()));
            } catch (e) {
                setProgress(100, 'bad');
                showStatus('<b>Manifest refused.</b> ' + escHtml(e.message), 'error');
                if (uploadBtn) uploadBtn.disabled = false;
                return;
            }
        }

        const formData = new FormData();
        formData.append('update', file, 'firmware.bin');

        const xhr = new XMLHttpRequest();
        xhr.open('POST', '/update', true);

        xhr.upload.addEventListener('progress', function(e) {
            if (e.lengthComputable) {
                const percent = (e.loaded / e.total) * 100;
                setProgress(percent);
                showStatus('Installing: ' + Math.round(percent) + '%' + (percent >= 99 ? '. Do not switch the box off.' : ''), 'info');
            }
        });

        xhr.onload = function() {
            if (xhr.status === 200) {
                setProgress(100, 'ok');
                showStatus('<b>Updated.</b> The box is restarting and this page returns to the dashboard in 5 seconds.', 'success');
                setTimeout(function() { window.location.href = '/'; }, 5000);
            } else {
                setProgress(100, 'bad');
                showStatus('<b>The update failed.</b> ' + escHtml(xhr.responseText || 'The box could not install the file.'), 'error');
                if (uploadBtn) uploadBtn.disabled = false;
            }
        };

        xhr.onerror = function() {
            setProgress(100, 'bad');
            showStatus('<b>Lost the connection to the box while installing.</b>', 'error');
            if (uploadBtn) uploadBtn.disabled = false;
        };

        xhr.send(formData);
    };

    document.addEventListener('DOMContentLoaded', function() { checkCloudUpdate(); });
