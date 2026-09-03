/**
 * Cloudflare Worker / D1 API for PisoPhone Licensing & Hardware Lock
 * Compatible with Cloudflare Workers + KV or D1
 *
 * Database Schema (Cloudflare D1):
 * CREATE TABLE IF NOT EXISTS devices (
 *   device_id TEXT PRIMARY KEY,
 *   hardware_hash TEXT NOT NULL,
 *   device_model TEXT,
 *   license_type TEXT NOT NULL, -- 'TRIAL' | 'PAID'
 *   first_registered_at INTEGER NOT NULL,
 *   trial_expires_at INTEGER NOT NULL,
 *   paid_expires_at INTEGER NOT NULL,
 *   last_checkin_at INTEGER NOT NULL,
 *   install_count INTEGER DEFAULT 1,
 *   payment_reference TEXT,
 *   notes TEXT
 * );
 *
 * CREATE TABLE IF NOT EXISTS payment_orders (
 *   order_id TEXT PRIMARY KEY,
 *   device_id TEXT NOT NULL,
 *   amount REAL NOT NULL,
 *   currency TEXT DEFAULT 'PHP',
 *   status TEXT NOT NULL, -- 'PENDING' | 'COMPLETED'
 *   payment_method TEXT, -- 'BKASH' | 'GCASH'
 *   created_at INTEGER NOT NULL,
 *   completed_at INTEGER
 * );
 */

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const method = request.method;

    // CORS Headers
    const corsHeaders = {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, Authorization',
    };

    if (method === 'OPTIONS') {
      return new Response(null, { headers: corsHeaders });
    }

    try {
      // 1. Register / Sync Hardware Device (Install / First Boot)
      // POST /api/device/register
      // Body: { deviceId: string, hardwareHash: string, deviceModel: string }
      if (url.pathname === '/api/device/register' && method === 'POST') {
        const body = await request.json();
        const { deviceId, hardwareHash, deviceModel } = body;

        if (!deviceId || !hardwareHash) {
          return new Response(JSON.stringify({ error: 'Missing deviceId or hardwareHash' }), {
            status: 400,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        const now = Date.now();
        const SEVEN_DAYS_MS = 7 * 24 * 60 * 60 * 1000;

        // KV or In-Memory/D1 Lookup
        let existing = null;
        if (env.DEVICE_STORE) {
          const raw = await env.DEVICE_STORE.get(deviceId);
          if (raw) existing = JSON.parse(raw);
        }

        if (existing) {
          // Existing device detected! Check if trial expired or paid
          const isPaid = existing.licenseType === 'PAID' && existing.paidExpiresAt > now;
          const isTrialValid = existing.trialExpiresAt > now;
          const isLocked = !isPaid && !isTrialValid;

          existing.lastCheckinAt = now;
          existing.installCount = (existing.installCount || 1) + 1;
          // Update deviceModel / hardwareHash if changed
          existing.deviceModel = deviceModel || existing.deviceModel;

          if (env.DEVICE_STORE) {
            await env.DEVICE_STORE.put(deviceId, JSON.stringify(existing));
          }

          return new Response(
            JSON.stringify({
              status: isLocked ? 'LOCKED' : (isPaid ? 'PAID' : 'TRIAL'),
              licenseType: existing.licenseType,
              deviceId: existing.deviceId,
              trialExpiresAt: existing.trialExpiresAt,
              paidExpiresAt: existing.paidExpiresAt,
              daysRemaining: isPaid
                ? Math.max(0, Math.ceil((existing.paidExpiresAt - now) / (24 * 60 * 60 * 1000)))
                : Math.max(0, Math.ceil((existing.trialExpiresAt - now) / (24 * 60 * 60 * 1000))),
              message: isLocked
                ? 'Free trial has ended. Please purchase a license to continue using PisoPhone.'
                : (isPaid ? 'Active 1-Year Commercial License' : '7-Day Free Trial Active'),
            }),
            { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
          );
        }

        // New Device: grant initial 7-day trial
        const newRecord = {
          deviceId,
          hardwareHash,
          deviceModel: deviceModel || 'Unknown Device',
          licenseType: 'TRIAL',
          firstRegisteredAt: now,
          trialExpiresAt: now + SEVEN_DAYS_MS,
          paidExpiresAt: 0,
          lastCheckinAt: now,
          installCount: 1,
        };

        if (env.DEVICE_STORE) {
          await env.DEVICE_STORE.put(deviceId, JSON.stringify(newRecord));
        }

        return new Response(
          JSON.stringify({
            status: 'TRIAL',
            licenseType: 'TRIAL',
            deviceId,
            trialExpiresAt: newRecord.trialExpiresAt,
            paidExpiresAt: 0,
            daysRemaining: 7,
            message: '7-Day Free Trial activated for this hardware.',
          }),
          { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );
      }

      // 2. Device Check Status (Heartbeat / Verification)
      // GET /api/device/status?deviceId=...
      if (url.pathname === '/api/device/status' && method === 'GET') {
        const deviceId = url.searchParams.get('deviceId');
        if (!deviceId) {
          return new Response(JSON.stringify({ error: 'Missing deviceId parameter' }), {
            status: 400,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        const now = Date.now();
        let record = null;
        if (env.DEVICE_STORE) {
          const raw = await env.DEVICE_STORE.get(deviceId);
          if (raw) record = JSON.parse(raw);
        }

        if (!record) {
          // Unregistered device -> automatically assign 7-day trial
          const SEVEN_DAYS_MS = 7 * 24 * 60 * 60 * 1000;
          record = {
            deviceId,
            hardwareHash: 'HW-UNHASHED',
            deviceModel: 'Unknown Device',
            licenseType: 'TRIAL',
            firstRegisteredAt: now,
            trialExpiresAt: now + SEVEN_DAYS_MS,
            paidExpiresAt: 0,
            lastCheckinAt: now,
            installCount: 1,
          };
          if (env.DEVICE_STORE) {
            await env.DEVICE_STORE.put(deviceId, JSON.stringify(record));
          }
        }

        const isPaid = record.licenseType === 'PAID' && record.paidExpiresAt > now;
        const isTrialValid = record.trialExpiresAt > now;
        const isLocked = !isPaid && !isTrialValid;

        return new Response(
          JSON.stringify({
            status: isLocked ? 'LOCKED' : (isPaid ? 'PAID' : 'TRIAL'),
            licenseType: record.licenseType,
            deviceId: record.deviceId,
            trialExpiresAt: record.trialExpiresAt,
            paidExpiresAt: record.paidExpiresAt,
            daysRemaining: isPaid
              ? Math.max(0, Math.ceil((record.paidExpiresAt - now) / (24 * 60 * 60 * 1000)))
              : Math.max(0, Math.ceil((record.trialExpiresAt - now) / (24 * 60 * 60 * 1000))),
          }),
          { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );
      }

      // 3. Confirm Payment / Activate 1-Year Commercial License
      // POST /api/payment/confirm
      // Body: { deviceId: string, orderId?: string, paymentRef?: string }
      if (url.pathname === '/api/payment/confirm' && method === 'POST') {
        const body = await request.json();
        const { deviceId, orderId, paymentRef } = body;

        if (!deviceId) {
          return new Response(JSON.stringify({ error: 'Missing deviceId' }), {
            status: 400,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        const now = Date.now();
        const ONE_YEAR_MS = 365 * 24 * 60 * 60 * 1000;

        let record = null;
        if (env.DEVICE_STORE) {
          const raw = await env.DEVICE_STORE.get(deviceId);
          if (raw) record = JSON.parse(raw);
        }

        if (!record) {
          record = {
            deviceId,
            hardwareHash: 'HW-MANUAL',
            deviceModel: 'Manual Activation',
            firstRegisteredAt: now,
            trialExpiresAt: now,
            installCount: 1,
          };
        }

        record.licenseType = 'PAID';
        record.paidExpiresAt = (record.paidExpiresAt && record.paidExpiresAt > now ? record.paidExpiresAt : now) + ONE_YEAR_MS;
        record.lastCheckinAt = now;
        record.paymentReference = paymentRef || orderId || `PAY-${now}`;

        if (env.DEVICE_STORE) {
          await env.DEVICE_STORE.put(deviceId, JSON.stringify(record));
        }

        return new Response(
          JSON.stringify({
            success: true,
            status: 'PAID',
            deviceId,
            paidExpiresAt: record.paidExpiresAt,
            message: 'Device successfully activated with 1-Year Commercial License.',
          }),
          { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );
      }

      return new Response(JSON.stringify({ error: 'Not found' }), {
        status: 404,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' },
      });
    } catch (e) {
      return new Response(JSON.stringify({ error: e.message }), {
        status: 500,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' },
      });
    }
  },
};
