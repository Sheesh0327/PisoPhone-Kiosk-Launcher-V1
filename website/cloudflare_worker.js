/**
 * Cloudflare Worker API for PisoPhone Licensing & Hardware Lock
 * Worker Name: pisophone-licensing-api
 * Bound KV Namespace: DEVICE_STORE -> pisophone-production-kv
 */

// Helper: HMAC-SHA256 signature using Web Crypto API
async function signHmacSha256(message, secret) {
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey(
    'raw',
    enc.encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign']
  );
  const sigBuffer = await crypto.subtle.sign('HMAC', key, enc.encode(message));
  return Array.from(new Uint8Array(sigBuffer))
    .map(b => b.toString(16).padStart(2, '0'))
    .join('');
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const method = request.method;

    const signingSecret = env.LICENSE_SIGNING_SECRET || 'piso_master_lic_secret_2026_89a1f';
    const adminSecret = env.ADMIN_SECRET || 'piso_admin_secret_2026';

    const corsHeaders = {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, Authorization, X-Signature',
    };

    if (method === 'OPTIONS') {
      return new Response(null, { headers: corsHeaders });
    }

    try {
      const now = Date.now();
      const SEVEN_DAYS_MS = 7 * 24 * 60 * 60 * 1000;
      const ONE_YEAR_MS = 365 * 24 * 60 * 60 * 1000;

      // =========================================================================
      // 1. Device Registration / Trial Status Check
      // POST /api/device/register
      // Body: { deviceId: string, hardwareHash: string, deviceModel: string }
      // =========================================================================
      if (url.pathname === '/api/device/register' && method === 'POST') {
        const body = await request.json();
        const { deviceId, hardwareHash, deviceModel } = body;

        if (!deviceId || !hardwareHash) {
          return new Response(JSON.stringify({ error: 'Missing deviceId or hardwareHash' }), {
            status: 400,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        let existing = null;
        if (env.DEVICE_STORE) {
          const raw = await env.DEVICE_STORE.get(deviceId);
          if (raw) existing = JSON.parse(raw);
        }

        if (existing) {
          const isPaid = existing.licenseType === 'PAID' && existing.paidExpiresAt > now;
          const isTrialValid = existing.trialExpiresAt > now;
          const isLocked = !isPaid && !isTrialValid;

          existing.lastCheckinAt = now;
          existing.installCount = (existing.installCount || 1) + 1;
          existing.deviceModel = deviceModel || existing.deviceModel;

          if (env.DEVICE_STORE) {
            await env.DEVICE_STORE.put(deviceId, JSON.stringify(existing));
          }

          let signature = '';
          let licenseKey = '';
          if (isPaid) {
            signature = await signHmacSha256(`${deviceId}|${existing.paidExpiresAt}`, signingSecret);
            licenseKey = `PISO-1Y.${deviceId}.${existing.paidExpiresAt}.${signature}`;
          }

          return new Response(
            JSON.stringify({
              status: isLocked ? 'LOCKED' : (isPaid ? 'PAID' : 'TRIAL'),
              licenseType: existing.licenseType,
              deviceId: existing.deviceId,
              deviceModel: existing.deviceModel || deviceModel || 'Unknown Device',
              trialExpiresAt: existing.trialExpiresAt,
              paidExpiresAt: existing.paidExpiresAt || 0,
              daysRemaining: isPaid
                ? Math.max(0, Math.ceil((existing.paidExpiresAt - now) / (24 * 60 * 60 * 1000)))
                : Math.max(0, Math.ceil((existing.trialExpiresAt - now) / (24 * 60 * 60 * 1000))),
              signature,
              licenseKey,
              message: isLocked
                ? 'Free trial has ended. Please complete payment.'
                : (isPaid ? 'Active 1-Year Commercial License' : '7-Day Free Trial Active'),
            }),
            { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
          );
        }

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
            deviceModel: newRecord.deviceModel,
            trialExpiresAt: newRecord.trialExpiresAt,
            paidExpiresAt: 0,
            daysRemaining: 7,
            message: '7-Day Free Trial activated for this hardware.',
          }),
          { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );
      }

      // =========================================================================
      // 2. Query Payment & Activation Status for a Specific Device
      // GET /api/payment/check?deviceId=...
      // =========================================================================
      if (url.pathname === '/api/payment/check' && method === 'GET') {
        const deviceId = url.searchParams.get('deviceId');
        if (!deviceId) {
          return new Response(JSON.stringify({ error: 'Missing deviceId parameter' }), {
            status: 400,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        let record = null;
        if (env.DEVICE_STORE) {
          const raw = await env.DEVICE_STORE.get(deviceId);
          if (raw) record = JSON.parse(raw);
        }

        if (!record || record.licenseType !== 'PAID' || record.paidExpiresAt <= now) {
          return new Response(
            JSON.stringify({
              paid: false,
              deviceId,
              deviceModel: record ? (record.deviceModel || 'Android Device') : 'Unknown Device',
              status: record ? (record.trialExpiresAt > now ? 'TRIAL' : 'LOCKED') : 'UNREGISTERED',
              message: 'No confirmed payment found for this device ID.',
            }),
            { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
          );
        }

        // Generate signed license payload
        const signature = await signHmacSha256(`${deviceId}|${record.paidExpiresAt}`, signingSecret);
        const licenseKey = `PISO-1Y.${deviceId}.${record.paidExpiresAt}.${signature}`;

        return new Response(
          JSON.stringify({
            paid: true,
            status: 'PAID',
            deviceId,
            deviceModel: record.deviceModel || 'Android Device',
            paidExpiresAt: record.paidExpiresAt,
            paymentReference: record.paymentReference || 'CONFIRMED',
            daysRemaining: Math.max(0, Math.ceil((record.paidExpiresAt - now) / (24 * 60 * 60 * 1000))),
            signature,
            licenseKey,
            message: 'Payment confirmed. 1-Year Commercial License ready.',
          }),
          { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );
      }

      // =========================================================================
      // 3. Payment Webhook (Linked to Payment Gateway: GCash/Maya/Stripe/PayMongo/Xendit)
      // POST /api/payment/webhook
      // Body can be direct JSON or standard gateway payload:
      // { deviceId: string, paymentRef?: string, orderId?: string, amount?: number }
      // =========================================================================
      if (url.pathname === '/api/payment/webhook' && method === 'POST') {
        let body;
        try {
          body = await request.json();
        } catch (e) {
          return new Response(JSON.stringify({ error: 'Invalid JSON payload' }), {
            status: 400,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        // Support standard gateways (extract deviceId from custom metadata/reference)
        let deviceId = body.deviceId || 
                       body.data?.attributes?.metadata?.deviceId || 
                       body.metadata?.deviceId || 
                       body.reference_number || 
                       body.orderId;

        const paymentRef = body.paymentRef || 
                           body.id || 
                           body.data?.id || 
                           body.transaction_id || 
                           `PAY-${now}`;

        if (!deviceId) {
          return new Response(JSON.stringify({ error: 'Missing deviceId in webhook payload metadata' }), {
            status: 400,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        deviceId = String(deviceId).trim();

        let record = null;
        if (env.DEVICE_STORE) {
          const raw = await env.DEVICE_STORE.get(deviceId);
          if (raw) record = JSON.parse(raw);
        }

        if (!record) {
          record = {
            deviceId,
            hardwareHash: `HW-${deviceId.toUpperCase()}`,
            deviceModel: 'Licensed via Webhook',
            firstRegisteredAt: now,
            trialExpiresAt: now,
            installCount: 1,
          };
        }

        const currentPaidExpires = record.paidExpiresAt || 0;
        const newPaidExpires = (currentPaidExpires > now ? currentPaidExpires : now) + ONE_YEAR_MS;

        record.licenseType = 'PAID';
        record.paidExpiresAt = newPaidExpires;
        record.lastCheckinAt = now;
        record.paymentReference = paymentRef;
        record.paidAt = now;

        if (env.DEVICE_STORE) {
          await env.DEVICE_STORE.put(deviceId, JSON.stringify(record));
        }

        // Cryptographically sign the license payload: deviceId|paidExpiresAt
        const signature = await signHmacSha256(`${deviceId}|${newPaidExpires}`, signingSecret);
        const licenseKey = `PISO-1Y.${deviceId}.${newPaidExpires}.${signature}`;

        return new Response(
          JSON.stringify({
            success: true,
            status: 'PAID',
            deviceId,
            deviceModel: record.deviceModel || 'Android Device',
            paidExpiresAt: newPaidExpires,
            signature,
            licenseKey,
            message: `Payment confirmed for device ${deviceId}. 1-Year Commercial License issued.`,
          }),
          { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );
      }

      // =========================================================================
      // 4. Manual Payment Confirmation / Admin Trigger
      // POST /api/payment/confirm
      // Body: { adminSecret?: string, deviceId: string, paymentRef?: string }
      // =========================================================================
      if (url.pathname === '/api/payment/confirm' && method === 'POST') {
        const body = await request.json();
        const { deviceId, paymentRef, adminSecret: reqSecret } = body;

        if (!deviceId) {
          return new Response(JSON.stringify({ error: 'Missing deviceId' }), {
            status: 400,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        const cleanId = String(deviceId).trim();

        let record = null;
        if (env.DEVICE_STORE) {
          const raw = await env.DEVICE_STORE.get(cleanId);
          if (raw) record = JSON.parse(raw);
        }

        if (!record) {
          record = {
            deviceId: cleanId,
            hardwareHash: `HW-${cleanId.toUpperCase()}`,
            deviceModel: 'Manual Activation',
            firstRegisteredAt: now,
            trialExpiresAt: now,
            installCount: 1,
          };
        }

        const currentPaidExpires = record.paidExpiresAt || 0;
        const newPaidExpires = (currentPaidExpires > now ? currentPaidExpires : now) + ONE_YEAR_MS;

        record.licenseType = 'PAID';
        record.paidExpiresAt = newPaidExpires;
        record.lastCheckinAt = now;
        record.paymentReference = paymentRef || `MANUAL-${now}`;
        record.paidAt = now;

        if (env.DEVICE_STORE) {
          await env.DEVICE_STORE.put(cleanId, JSON.stringify(record));
        }

        const signature = await signHmacSha256(`${cleanId}|${newPaidExpires}`, signingSecret);
        const licenseKey = `PISO-1Y.${cleanId}.${newPaidExpires}.${signature}`;

        return new Response(
          JSON.stringify({
            success: true,
            status: 'PAID',
            deviceId: cleanId,
            deviceModel: record.deviceModel || 'Android Device',
            paidExpiresAt: newPaidExpires,
            signature,
            licenseKey,
            message: 'Device successfully marked as paid & license issued.',
          }),
          { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );
      }

      return new Response(JSON.stringify({ error: 'Endpoint not found' }), {
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
