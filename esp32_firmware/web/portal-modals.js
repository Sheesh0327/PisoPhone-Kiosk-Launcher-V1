// Shared by every product: the dialogs and the list of payments the box gave up on.
function showModal(id) {
    const m = document.getElementById(id);
    if (m) m.style.display = 'flex';
}
function hideModal(id) {
    const m = document.getElementById(id);
    if (m) m.style.display = 'none';
}
// Payments the box gave up on (a customer paid and never got the time). The operator refunds the money or adds the
// time by hand, then marks the payment handled; the banner on the overview stays until all of them are.
const ORPHAN_REASONS = {
    'expired': 'Nobody confirmed it for 24 hours.',
    'expired-queue-full': 'Nobody confirmed it for 30 minutes and the queue was almost full.',
    'cleared': 'Removed with the "clear payments" request.',
    'not-queued': 'The coin was accepted but the box could not record it. It may still have been credited: check before refunding.'
};
window.ORPHAN_UNKNOWN = 'unknown customer';
window.showOrphans = function() {
    showModal('orphans_modal');
    loadOrphans();
};

function loadOrphans() {
    const body = document.getElementById('orphans_body');
    if (!body) return;
    fetch('/api/payments/orphans')
        .then(res => res.json())
        .then(renderOrphans)
        .catch(() => { body.textContent = 'Could not load the list. Try again.'; });
}

function renderOrphans(d) {
    const body = document.getElementById('orphans_body');
    const all = document.getElementById('orphans_all_btn');
    if (!body) return;
    const rows = (d.entries || []).map(e => {
        const when = e.time_s > 0 ? new Date(e.time_s * 1000).toLocaleString() : 'time unknown';
        const worth = e.credit_seconds > 0 ? ' · worth ' + fmtTime(e.credit_seconds) + ' of time' : '';
        const action = e.handled
            ? '<span class="tag ok">Handled</span>'
            : '<button type="button" class="btn sm" onclick="handleOrphan(' + e.seq + ')">Handled</button>';
        return '<div class="kv"><span><b>₱' + e.pulses + '</b>' + worth + '<br>' +
               '<span class="muted">' + escHtml(when) + ' · ' + escHtml(e.target || window.ORPHAN_UNKNOWN) + ' · ' + escHtml(e.tx_id) + '</span><br>' +
               '<span class="hint">' + escHtml(ORPHAN_REASONS[e.reason] || e.reason) + '</span></span>' + action + '</div>';
    });
    let html = '<div class="hint">A customer put in these coins but the box could not turn them into time. Add the time by hand ' +
               'or refund the money, then press Handled.</div>';
    if (d.unlisted > 0) {
        html += '<div class="note warn">' + ic('alert') + '<span class="grow">' + d.unlisted + ' older ' + (d.unlisted === 1 ? 'payment' : 'payments') +
                ' (₱' + d.unlisted_pulses + ') no longer fit in this list. They are still counted in the total; "Mark all handled" clears them too.</span></div>';
    }
    html += rows.length ? rows.join('') : '<div class="muted">Nothing here.</div>';
    body.innerHTML = html;
    if (all) all.style.display = d.unhandled > 0 ? '' : 'none';
}

function postOrphanAction(params, doneMessage) {
    fetch('/api/payments/orphans/handle', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: params
    })
        .then(res => res.json())
        .then(data => {
            if (data.status === 'ok') notify(doneMessage);
            else notify(data.message || 'Could not save that.', 'bad');
            loadOrphans();
        })
        .catch(() => notify('Could not reach the box.', 'bad'));
}

window.handleOrphan = function(seq) {
    postOrphanAction('seq=' + encodeURIComponent(seq), 'Marked as handled.');
};

window.handleAllOrphans = function() {
    if (!confirm('Mark every listed payment as handled? Do this only after you have refunded or credited them.')) return;
    postOrphanAction('all=1', 'All payments marked as handled.');
};

// Click on the dark area or press Escape to close any dialog.
document.addEventListener('click', function(e) {
    if (e.target.classList && e.target.classList.contains('modal-overlay')) e.target.style.display = 'none';
});
document.addEventListener('keydown', function(e) {
    if (e.key === 'Escape') document.querySelectorAll('.modal-overlay').forEach(m => { m.style.display = 'none'; });
});
