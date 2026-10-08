#ifndef WEB_DASHBOARD_MODALS_H
#define WEB_DASHBOARD_MODALS_H

#include <Arduino.h>

const char PORTAL_MODALS_HTML[] PROGMEM = R"HTML(
<!-- Phone slot -->
<div id="slot_activation_modal" class="modal-overlay">
    <div class="modal">
        <div class="modal-head">
            <h3 id="modal_slot_title">Slot 1</h3>
            <button type="button" class="modal-x" onclick="closeSlotActivationModal()" aria-label="Close"><svg class="ic"><use href="#i-x"/></svg></button>
        </div>
        <div class="modal-body">
            <div class="kv">
                <span><span class="muted">Phone</span><br><b id="modal_slot_sub">Empty slot</b></span>
                <span id="modal_slot_status_badge"></span>
            </div>
            <div id="modal_slot_expiry_text" class="hint"></div>
            <div id="modal_pair_request_container" class="stack hidden"></div>
            <button type="button" class="choice" onclick="submitModalFlash()">
                <span>
                    <span class="choice-t">Set up a phone</span><br>
                    <span class="choice-s">Open the setup page for this slot</span>
                </span>
                <svg class="ic"><use href="#i-download"/></svg>
            </button>
        </div>
        <div id="modal_unpair_container" class="modal-foot hidden">
            <button type="button" class="btn danger" onclick="submitModalUnpair()"><svg class="ic"><use href="#i-unlock"/></svg>Unpair this phone</button>
        </div>
    </div>
</div>

<!-- Upgrade capacity -->
<div id="token_modal" class="modal-overlay">
    <div class="modal">
        <div class="modal-head">
            <h3>Add phone slots</h3>
            <button type="button" class="modal-x" onclick="closeTokenModal()" aria-label="Close"><svg class="ic"><use href="#i-x"/></svg></button>
        </div>
        <div class="modal-body">
            <p class="hint">Send the box code below to your vendor. They reply with a license key that adds more slots.</p>
            <div class="kv">
                <span class="code">{BOX_CODE}</span>
                <button type="button" class="btn sm" onclick="copyToClipboard('{BOX_CODE}', this)"><svg class="ic"><use href="#i-copy"/></svg>Copy</button>
            </div>
            <div class="kv"><span class="muted">Slots now</span><b>{MAX_SLOTS} of {MAX_SUPPORTED_SLOTS}</b></div>
            <div class="field">
                <label for="token_input">License key</label>
                <textarea id="token_input" rows="3" placeholder="Paste the key here (starts with PISOLIC1.)"></textarea>
            </div>
            <div id="token_error" class="note bad hidden"></div>
        </div>
        <div class="modal-foot">
            <button type="button" class="btn" onclick="closeTokenModal()">Cancel</button>
            <button type="button" class="btn primary" onclick="submitSlotToken()">Add slots</button>
        </div>
    </div>
</div>

<!-- Pair a connecting phone -->
<div id="unassigned_pair_modal" class="modal-overlay">
    <div class="modal">
        <div class="modal-head">
            <h3 id="unassigned_modal_title">Pair a phone</h3>
            <button type="button" class="modal-x" onclick="closeUnassignedPairModal()" aria-label="Close"><svg class="ic"><use href="#i-x"/></svg></button>
        </div>
        <div id="unassigned_modal_body" class="modal-body"></div>
        <div class="modal-foot">
            <button type="button" class="btn" onclick="openInstallerForActiveSlot()"><svg class="ic"><use href="#i-download"/></svg>Open setup page</button>
            <button type="button" class="btn" onclick="closeUnassignedPairModal()">Close</button>
        </div>
    </div>
</div>
)HTML";

#endif // WEB_DASHBOARD_MODALS_H
