#ifndef SUPER_ADMIN_TEMPLATE_H
#define SUPER_ADMIN_TEMPLATE_H

#include <Arduino.h>

const char SUPER_ADMIN_HTML[] PROGMEM = R"HTML(
        <!-- VENDOR -->
        <section id="tab-superadmin" class="tab-content">
            <!-- Sign-in (shown until the vendor password is accepted) -->
            <div id="sa_auth_gate" class="panel" style="max-width: 520px;">
                <div class="panel-head"><h2><svg class="ic"><use href="#i-shield"/></svg>Vendor access</h2></div>
                <div class="panel-body">
                    <p class="hint">For the vendor who supplied this box. Unlocks revenue collection and the revenue split calculator.</p>
                    <div class="field">
                        <label for="sa_login_pw">Vendor password</label>
                        <input type="password" id="sa_login_pw" autocomplete="off" onkeydown="if(event.key==='Enter') unlockSuperAdmin()">
                    </div>
                </div>
                <div class="panel-foot">
                    <button type="button" class="btn primary" onclick="unlockSuperAdmin()"><svg class="ic"><use href="#i-unlock"/></svg>Unlock</button>
                </div>
            </div>

            <!-- Console (shown once unlocked) -->
            <div id="sa_main_console" class="stack hidden">
                <div class="note">
                    <svg class="ic"><use href="#i-shield"/></svg>
                    <span class="grow"><b>Vendor mode is on.</b> It locks again in <b id="sa_session_timer" class="mono">05:00</b>.</span>
                    <button type="button" class="btn sm" onclick="lockSuperAdmin()"><svg class="ic"><use href="#i-lock"/></svg>Lock now</button>
                </div>

                <div class="panel">
                    <div class="panel-head">
                        <h2><svg class="ic"><use href="#i-receipt"/></svg>Collect revenue</h2>
                        <span id="sa_vault_status_badge" class="tag warn">Hidden</span>
                    </div>
                    <div class="panel-body">
                        <!-- Hidden state -->
                        <div id="sa_masked_box" class="vault-mask" onclick="promptUnmaskVault()">
                            <div class="muted">Revenue in the coin box</div>
                            <div class="big">₱ • • • •</div>
                            <div class="hint">Tap to show it and start a 5-minute collection window.</div>
                        </div>

                        <!-- Shown state -->
                        <div id="sa_unmasked_box" class="stack hidden">
                            <div class="field">
                                <div class="inline" style="justify-content: space-between;">
                                    <span class="lbl">Split</span>
                                    <span id="sa_split_label" class="lbl">50% vendor / 50% operator</span>
                                </div>
                                <div class="presets">
                                    <button type="button" class="btn sm" onclick="setSplitPercent(50)">50 / 50</button>
                                    <button type="button" class="btn sm" onclick="setSplitPercent(60)">60 / 40</button>
                                    <button type="button" class="btn sm" onclick="setSplitPercent(70)">70 / 30</button>
                                    <button type="button" class="btn sm" onclick="setSplitPercent(80)">80 / 20</button>
                                </div>
                                <input type="range" id="sa_split_slider" min="0" max="100" value="50" oninput="onSplitSliderChange(this.value)" aria-label="Vendor share">
                            </div>

                            <div class="shares">
                                <div class="share">
                                    <div class="k">Vendor share</div>
                                    <div id="sa_vendor_payout" class="v">₱0.00</div>
                                    <div id="sa_vendor_share_sub" class="hint">50% of revenue</div>
                                </div>
                                <div class="share">
                                    <div class="k">Operator share</div>
                                    <div id="sa_operator_payout" class="v">₱0.00</div>
                                    <div id="sa_operator_share_sub" class="hint">50% of revenue</div>
                                </div>
                            </div>

                            <div class="note bad">
                                <svg class="ic"><use href="#i-clock"/></svg>
                                <span class="grow">
                                    <b>Collection window:</b> <span id="sa_countdown_timer" class="mono">05:00</span><br>
                                    <span class="hint">The revenue counter resets to ₱0 when the window ends or you lock the page, which confirms the coins were taken out.</span>
                                    <span class="timer-bar" style="display: block; margin-top: 8px;"><i id="sa_timer_bar"></i></span>
                                </span>
                            </div>

                            <div class="actions">
                                <button type="button" class="btn" onclick="saveDefaultSplit()"><svg class="ic"><use href="#i-save"/></svg>Save as default split</button>
                                <button type="button" class="btn" onclick="copySplitReceipt()"><svg class="ic"><use href="#i-copy"/></svg>Copy receipt</button>
                                <button type="button" class="btn danger solid" onclick="confirmResetVaultNow()"><svg class="ic"><use href="#i-check"/></svg>Finish and reset counter</button>
                            </div>
                        </div>
                    </div>
                </div>

                <div class="panel">
                    <div class="panel-head"><h2><svg class="ic"><use href="#i-key"/></svg>What vendor mode can do</h2></div>
                    <div class="panel-body">
                        <ul class="checklist">
                            <li><svg class="ic"><use href="#i-check"/></svg>See the revenue counter and reset it after collecting.</li>
                            <li><svg class="ic"><use href="#i-check"/></svg>Set the default revenue split.</li>
                            <li><svg class="ic"><use href="#i-check"/></svg>Everything the normal admin can do.</li>
                            <li><svg class="ic"><use href="#i-info"/></svg>The vendor password is managed by the vendor and cannot be changed here.</li>
                        </ul>
                    </div>
                </div>
            </div>
        </section>
)HTML";

#endif // SUPER_ADMIN_TEMPLATE_H
