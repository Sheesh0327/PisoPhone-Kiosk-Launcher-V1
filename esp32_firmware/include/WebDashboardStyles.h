#ifndef WEB_DASHBOARD_STYLES_H
#define WEB_DASHBOARD_STYLES_H

#include <Arduino.h>

const char PORTAL_CSS[] PROGMEM = R"CSS(
:root {
    --bg: #F1F5F9;
    --card-bg: #FFFFFF;
    --input-bg: #F8FAFC;
    --text-main: #0F172A;
    --text-muted: #64748B;
    --primary: #059669;
    --primary-hover: #047857;
    --primary-glow: rgba(5, 150, 105, 0.15);
    --border: #E2E8F0;
    --border-focus: #10B981;
    --danger: #EF4444;
    --danger-hover: #DC2626;
    --warning: #F59E0B;
    --warning-hover: #D97706;
    --success: #10B981;
    --card-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.05), 0 2px 4px -1px rgba(0, 0, 0, 0.03);
    --radius-lg: 16px;
    --radius-md: 12px;
    --radius-sm: 8px;
    
    --status-good: #10B981;
    --status-good-bg: rgba(16, 185, 129, 0.1);
    --status-good-border: rgba(16, 185, 129, 0.2);
    --status-warning: #F59E0B;
    --status-warning-bg: rgba(245, 158, 11, 0.1);
    --status-warning-border: rgba(245, 158, 11, 0.2);
    --status-critical: #EF4444;
    --status-critical-bg: rgba(239, 68, 68, 0.1);
    --status-critical-border: rgba(239, 68, 68, 0.2);
}
[data-theme="dark"] {
    --bg: #060B14;
    --card-bg: #0F172A;
    --input-bg: #0B0F19;
    --text-main: #F8FAFC;
    --text-muted: #94A3B8;
    --primary: #10B981;
    --primary-hover: #34D399;
    --primary-glow: rgba(16, 185, 129, 0.2);
    --border: rgba(255, 255, 255, 0.08);
    --border-focus: #10B981;
    --danger: #EF4444;
    --danger-hover: #F87171;
    --warning: #F59E0B;
    --warning-hover: #FBBF24;
    --success: #10B981;
    --card-shadow: 0 10px 30px -10px rgba(0, 0, 0, 0.5);
    
    --status-good: #34D399;
    --status-good-bg: rgba(52, 211, 153, 0.1);
    --status-good-border: rgba(52, 211, 153, 0.2);
    --status-warning: #FBBF24;
    --status-warning-bg: rgba(251, 191, 36, 0.1);
    --status-warning-border: rgba(251, 191, 36, 0.2);
    --status-critical: #F87171;
    --status-critical-bg: rgba(248, 113, 113, 0.1);
    --status-critical-border: rgba(248, 113, 113, 0.2);
}
* { box-sizing: border-box; margin: 0; padding: 0; }
body {
    font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
    background: var(--bg);
    color: var(--text-main);
    padding: 24px 16px;
    line-height: 1.6;
    transition: background-color 0.3s ease, color 0.3s ease;
    -webkit-font-smoothing: antialiased;
}
.app-container {
    max-width: 960px;
    margin: 0 auto;
    display: flex;
    flex-direction: column;
    gap: 24px;
}
.header-bar {
    display: flex;
    justify-content: space-between;
    align-items: center;
    background: var(--card-bg);
    padding: 16px 24px;
    border-radius: var(--radius-lg);
    box-shadow: var(--card-shadow);
    border: 1px solid var(--border);
    flex-wrap: wrap;
    gap: 16px;
}
.logo-container {
    display: flex;
    align-items: center;
    gap: 10px;
}
.logo-icon {
    color: var(--primary);
    filter: drop-shadow(0 0 4px var(--primary-glow));
    animation: pulse-glow 2s infinite alternate;
}
@keyframes pulse-glow {
    0% { filter: drop-shadow(0 0 2px var(--primary-glow)); }
    100% { filter: drop-shadow(0 0 8px var(--primary)); }
}
.logo-text {
    font-size: 20px;
    font-weight: 800;
    letter-spacing: -0.5px;
    text-transform: uppercase;
    display: flex;
    align-items: center;
}
.logo-piso {
    color: var(--text-main);
}
.logo-phone {
    color: var(--primary);
}
.badge-pill {
    background: var(--primary-glow);
    color: var(--primary);
    border: 1px solid var(--border-focus);
    padding: 3px 10px;
    border-radius: 999px;
    font-size: 11px;
    font-weight: 700;
    letter-spacing: 0.5px;
    text-transform: uppercase;
}
.btn {
    background: var(--primary);
    color: #ffffff;
    border: none;
    padding: 12px 20px;
    border-radius: var(--radius-md);
    font-weight: 600;
    cursor: pointer;
    transition: all 0.2s ease;
    font-size: 14px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    text-decoration: none;
    min-height: 48px;
    box-shadow: 0 4px 12px var(--primary-glow);
}
.btn:hover {
    background: var(--primary-hover);
    transform: translateY(-1px);
    box-shadow: 0 6px 16px var(--primary-glow);
}
.btn:active {
    transform: translateY(1px);
}
.btn-sm {
    padding: 8px 16px;
    min-height: 38px;
    font-size: 13px;
    border-radius: var(--radius-sm);
}
.btn-danger {
    background: var(--danger);
    box-shadow: 0 4px 12px rgba(239, 68, 68, 0.15);
}
.btn-danger:hover {
    background: var(--danger-hover);
    box-shadow: 0 6px 16px rgba(239, 68, 68, 0.25);
}
.btn-warning {
    background: var(--warning);
    color: #0F172A;
    box-shadow: 0 4px 12px rgba(245, 158, 11, 0.15);
}
.btn-warning:hover {
    background: var(--warning-hover);
    box-shadow: 0 6px 16px rgba(245, 158, 11, 0.25);
}
.btn-outline {
    background: transparent;
    border: 1.5px solid var(--border);
    color: var(--text-main);
    box-shadow: none;
}
.btn-outline:hover {
    background: var(--input-bg);
    border-color: var(--text-muted);
}
.tabs {
    display: flex;
    background: var(--card-bg);
    border: 1px solid var(--border);
    padding: 6px;
    border-radius: var(--radius-md);
    gap: 4px;
    overflow-x: auto;
    box-shadow: var(--card-shadow);
}
.tab {
    flex: 1;
    padding: 10px 16px;
    border-radius: var(--radius-sm);
    font-weight: 600;
    font-size: 14px;
    color: var(--text-muted);
    cursor: pointer;
    transition: all 0.2s ease;
    text-align: center;
    white-space: nowrap;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 6px;
}
.tab:hover:not(.active) {
    background: var(--input-bg);
    color: var(--text-main);
}
.tab.active {
    background: var(--primary);
    color: #ffffff;
    box-shadow: 0 4px 12px var(--primary-glow);
}
.tab-content {
    display: none;
    animation: scaleIn 0.25s cubic-bezier(0.16, 1, 0.3, 1);
}
.tab-content.active {
    display: block;
}
@keyframes scaleIn {
    from { opacity: 0; transform: scale(0.98) translateY(4px); }
    to { opacity: 1; transform: scale(1) translateY(0); }
}
.grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
    gap: 20px;
}
.grid-full {
    grid-column: 1 / -1;
}
.card {
    background: var(--card-bg);
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    padding: 24px;
    box-shadow: var(--card-shadow);
    display: flex;
    flex-direction: column;
    gap: 20px;
}
.card-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    border-bottom: 1px solid var(--border);
    padding-bottom: 12px;
    margin-bottom: 4px;
}
.card-title {
    margin: 0;
    font-size: 16px;
    font-weight: 700;
    color: var(--text-main);
    display: flex;
    align-items: center;
    gap: 8px;
}
.form-group {
    display: flex;
    flex-direction: column;
    gap: 6px;
}
label {
    font-weight: 700;
    font-size: 11px;
    color: var(--text-muted);
    text-transform: uppercase;
    letter-spacing: 0.75px;
}
input[type=text], input[type=password], input[type=number], select {
    width: 100%;
    height: 48px;
    padding: 0 16px;
    border: 1.5px solid var(--border);
    border-radius: var(--radius-md);
    font-size: 15px;
    background: var(--input-bg);
    transition: all 0.2s ease;
    color: var(--text-main);
    font-family: inherit;
}
input:focus, select:focus {
    outline: none;
    border-color: var(--border-focus);
    background: var(--card-bg);
    box-shadow: 0 0 0 3px var(--primary-glow);
}
.hint {
    font-size: 12px;
    color: var(--text-muted);
    line-height: 1.5;
}
.danger-hint {
    color: var(--danger);
    font-weight: 500;
}
.status-badge {
    background: var(--primary-glow);
    color: var(--primary);
    border: 1px solid var(--border-focus);
    padding: 6px 12px;
    border-radius: 20px;
    font-size: 11px;
    font-weight: 700;
    letter-spacing: 0.5px;
    text-transform: uppercase;
}
.status-badge.accent {
    background: rgba(126, 34, 206, 0.1);
    color: #A78BFA;
    border: 1px solid rgba(126, 34, 206, 0.3);
}
[data-theme="light"] .status-badge.accent {
    background: #F3E8FF;
    color: #7E22CE;
    border: 1px solid #E9D5FF;
}
.device-list {
    display: flex;
    flex-direction: column;
    gap: 12px;
}
.device-row {
    background: var(--card-bg);
    border: 1px solid var(--border);
    border-radius: var(--radius-md);
    padding: 14px 18px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    box-shadow: var(--card-shadow);
    transition: all 0.2s ease;
}
.device-row:hover {
    border-color: var(--primary);
    box-shadow: 0 4px 16px -2px rgba(16, 185, 129, 0.15);
}
.device-row.empty {
    border: 1.5px dashed var(--border);
    background: var(--input-bg);
}
.device-row.offline {
    opacity: 0.7;
}
.device-row-identity {
    display: flex;
    align-items: center;
    gap: 14px;
    min-width: 220px;
}
.device-slot-badge {
    background: rgba(16, 185, 129, 0.15);
    color: var(--primary);
    font-size: 11px;
    font-weight: 800;
    padding: 5px 10px;
    border-radius: 8px;
    border: 1px solid rgba(16, 185, 129, 0.25);
    white-space: nowrap;
}
.device-row-info {
    display: flex;
    flex-direction: column;
}
.device-row-name {
    font-size: 15px;
    font-weight: 700;
    color: var(--text-main);
}
.device-row-sub {
    font-size: 12px;
    font-family: monospace;
    color: var(--text-muted);
}
.device-row-metrics {
    display: flex;
    align-items: center;
    gap: 20px;
    flex-wrap: wrap;
}
.device-row-timer {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 15px;
    font-weight: 800;
    color: var(--text-main);
    font-variant-numeric: tabular-nums;
}
.device-row-battery {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 5px 10px;
    border-radius: 8px;
    background: var(--input-bg);
    border: 1px solid var(--border);
}
.device-row-actions {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-shrink: 0;
}
.device-badge {
    display: inline-block;
    padding: 4px 8px;
    border-radius: 6px;
    font-size: 11px;
    font-weight: 700;
    text-transform: uppercase;
}
.device-badge.active {
    background: rgba(16, 185, 129, 0.15);
    color: #10B981;
    border: 1px solid rgba(16, 185, 129, 0.3);
}
.device-badge.standby {
    background: rgba(148, 163, 184, 0.15);
    color: var(--text-muted);
    border: 1px solid rgba(148, 163, 184, 0.3);
}
.device-badge.offline {
    background: rgba(239, 68, 68, 0.15);
    color: #EF4444;
    border: 1px solid rgba(239, 68, 68, 0.3);
}
.battery-section {
    padding: 12px;
    border-radius: 12px;
}
.battery-bar-bg {
    height: 8px;
    background: var(--border);
    border-radius: 999px;
    overflow: hidden;
}
.battery-bar-fill {
    height: 100%;
    border-radius: 999px;
    transition: width 0.4s ease-in-out, background-color 0.3s ease;
}
.battery-section.status-good, .device-row-battery.status-good {
    background: var(--status-good-bg);
    border: 1px solid var(--status-good-border);
}
.battery-section.status-good .battery-label,
.battery-section.status-good .battery-status-tag {
    color: var(--status-good);
}
.battery-section.status-good .battery-bar-fill,
.device-row-battery.status-good .battery-bar-fill {
    background: var(--status-good);
}
.battery-section.status-warning, .device-row-battery.status-warning {
    background: var(--status-warning-bg);
    border: 1px solid var(--status-warning-border);
}
.battery-section.status-warning .battery-label,
.battery-section.status-warning .battery-status-tag {
    color: var(--status-warning);
}
.battery-section.status-warning .battery-bar-fill,
.device-row-battery.status-warning .battery-bar-fill {
    background: var(--status-warning);
}
.battery-section.status-critical, .device-row-battery.status-critical {
    background: var(--status-critical-bg);
    border: 1px solid var(--status-critical-border);
}
.battery-section.status-critical .battery-label,
.battery-section.status-critical .battery-status-tag {
    color: var(--status-critical);
}
.battery-section.status-critical .battery-bar-fill,
.device-row-battery.status-critical .battery-bar-fill {
    background: var(--status-critical);
}
.alert-box {
    padding: 16px;
    border-radius: var(--radius-md);
    font-size: 14px;
    line-height: 1.5;
    margin-top: 16px;
    display: none;
}
.alert-box.info {
    background: var(--input-bg);
    border: 1px solid var(--border);
    color: var(--text-main);
}
.alert-box.success {
    background: var(--status-good-bg);
    border: 1px solid var(--status-good-border);
    color: var(--status-good);
}
.alert-box.error {
    background: var(--status-critical-bg);
    border: 1px solid var(--status-critical-border);
    color: var(--status-critical);
}
.dev-ip-row {
    display: flex;
    align-items: center;
    gap: 12px;
    background: var(--input-bg);
    padding: 12px 16px;
    border: 1px solid var(--border);
    border-radius: var(--radius-md);
    margin-bottom: 12px;
    flex-wrap: wrap;
}
.dev-ip-row input {
    flex: 1;
    min-width: 140px;
}
.remove-btn {
    background: transparent;
    border: none;
    color: var(--danger);
    font-size: 22px;
    font-weight: bold;
    cursor: pointer;
    width: 44px;
    height: 44px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    border-radius: var(--radius-sm);
    transition: all 0.2s;
}
.remove-btn:hover {
    background: rgba(239, 68, 68, 0.1);
}
@media (max-width: 640px) {
    body {
        padding: 10px 8px;
    }
    .app-container {
        gap: 14px;
    }
    .header-bar {
        flex-direction: column;
        align-items: stretch;
        gap: 12px;
        padding: 14px 16px;
    }
    .tabs {
        display: flex;
        overflow-x: auto;
        -webkit-overflow-scrolling: touch;
        scrollbar-width: none;
        gap: 4px;
        padding: 4px;
    }
    .tabs::-webkit-scrollbar {
        display: none;
    }
    .tab {
        flex: 0 0 auto;
        padding: 8px 14px;
        font-size: 13px;
        white-space: nowrap;
    }
    .grid {
        grid-template-columns: 1fr !important;
        gap: 14px;
    }
    .card {
        padding: 16px;
    }
    .device-row {
        flex-direction: column;
        align-items: stretch;
        gap: 12px;
        padding: 14px;
    }
    .device-row-identity {
        width: 100%;
        min-width: unset;
        justify-content: flex-start;
    }
    .device-row-metrics {
        width: 100%;
        justify-content: space-between;
        gap: 8px;
    }
    .device-row-actions {
        width: 100%;
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: 8px;
    }
    .device-row-actions .btn {
        width: 100%;
        text-align: center;
        justify-content: center;
    }
    input, select, textarea {
        font-size: 16px !important;
    }
}
)CSS";

#endif // WEB_DASHBOARD_STYLES_H
