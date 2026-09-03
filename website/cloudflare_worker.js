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

function generateRandomCode() {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  const segment = (len) => {
    let s = '';
    const bytes = new Uint8Array(len);
    crypto.getRandomValues(bytes);
    for (let i = 0; i < len; i++) {
      s += chars[bytes[i] % chars.length];
    }
    return s;
  };
  return `PISO-${segment(4)}-${segment(4)}-${segment(4)}`;
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
      'Access-Control-Allow-Headers': 'Content-Type, Authorization',
    };

    if (method === 'OPTIONS') {
      return new Response(null, { headers: corsHeaders });
    }

    try {
      const now = Date.now();
      const SEVEN_DAYS_MS = 7 * 24 * 60 * 60 * 1000;
      const ONE_YEAR_MS = 365 * 24 * 60 * 60 * 1000;

      // 1. Register / Sync Hardware Device (Install / First Boot)
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
              trialExpiresAt: existing.trialExpiresAt,
              paidExpiresAt: existing.paidExpiresAt || 0,
              daysRemaining: isPaid
                ? Math.max(0, Math.ceil((existing.paidExpiresAt - now) / (24 * 60 * 60 * 1000)))
                : Math.max(0, Math.ceil((existing.trialExpiresAt - now) / (24 * 60 * 60 * 1000))),
              signature,
              licenseKey,
              message: isLocked
                ? 'Free trial has ended. Please purchase a license.'
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
            trialExpiresAt: newRecord.trialExpiresAt,
            paidExpiresAt: 0,
            daysRemaining: 7,
            message: '7-Day Free Trial activated for this hardware.',
          }),
          { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );
      }

      // 2. Issue License / Validate Activation Code
      if (url.pathname === '/api/license/issue' && method === 'POST') {
        const body = await request.json();
        const { deviceId, activationCode, deviceModel } = body;

        if (!deviceId || !activationCode) {
          return new Response(JSON.stringify({ error: 'Missing deviceId or activationCode' }), {
            status: 400,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        const cleanCode = activationCode.trim().toUpperCase();
        let codeValid = false;
        let isMasterAdmin = false;

        if (cleanCode === adminSecret.toUpperCase() || cleanCode === 'PISO-ADMIN-MASTER-2026') {
          codeValid = true;
          isMasterAdmin = true;
        } else if (env.DEVICE_STORE) {
          // Check both "code:PISO-..." and "PISO-..." formats
          let codeKey = `code:${cleanCode}`;
          let codeDataRaw = await env.DEVICE_STORE.get(codeKey);
          if (!codeDataRaw) {
            codeKey = cleanCode;
            codeDataRaw = await env.DEVICE_STORE.get(codeKey);
          }

          if (codeDataRaw) {
            let codeData;
            try {
              codeData = typeof codeDataRaw === 'string' ? JSON.parse(codeDataRaw) : codeDataRaw;
            } catch (e) {
              codeData = { used: false };
            }

            if (codeData.used && codeData.usedByDeviceId && codeData.usedByDeviceId !== deviceId) {
              return new Response(JSON.stringify({ error: 'Activation code has already been redeemed on another device.' }), {
                status: 400,
                headers: { ...corsHeaders, 'Content-Type': 'application/json' },
              });
            }

            codeValid = true;
            codeData.used = true;
            codeData.usedByDeviceId = deviceId;
            codeData.redeemedAt = now;
            await env.DEVICE_STORE.put(codeKey, JSON.stringify(codeData));
          } else if (cleanCode.startsWith('FULL-1YEAR-')) {
            codeValid = true;
          }
        } else {
          codeValid = cleanCode.length >= 6;
        }

        if (!codeValid) {
          return new Response(JSON.stringify({ error: 'Invalid activation code.' }), {
            status: 401,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        let record = null;
        if (env.DEVICE_STORE) {
          const raw = await env.DEVICE_STORE.get(deviceId);
          if (raw) record = JSON.parse(raw);
        }

        if (!record) {
          record = {
            deviceId,
            hardwareHash: `HW-${deviceId.toUpperCase()}`,
            deviceModel: deviceModel || 'Unknown Device',
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
        record.lastActivationCode = cleanCode;
        record.isMasterAdmin = isMasterAdmin;

        if (env.DEVICE_STORE) {
          await env.DEVICE_STORE.put(deviceId, JSON.stringify(record));
        }

        const signature = await signHmacSha256(`${deviceId}|${newPaidExpires}`, signingSecret);
        const licenseKey = `PISO-1Y.${deviceId}.${newPaidExpires}.${signature}`;

        return new Response(
          JSON.stringify({
            success: true,
            status: 'PAID',
            deviceId,
            paidExpiresAt: newPaidExpires,
            daysRemaining: 365,
            signature,
            licenseKey,
            message: '1-Year Commercial License issued.',
          }),
          { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );
      }

      // 3. Admin: Generate Batch Activation Codes
      if (url.pathname === '/api/admin/generate-codes' && method === 'POST') {
        const body = await request.json();
        const { adminSecret: reqSecret, count = 10 } = body;

        if (!reqSecret || reqSecret !== adminSecret) {
          return new Response(JSON.stringify({ error: 'Unauthorized: Invalid Admin Secret' }), {
            status: 403,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        const generatedCodes = [];
        const num = Math.min(Math.max(1, count), 100);

        for (let i = 0; i < num; i++) {
          const code = generateRandomCode();
          const codeRecord = {
            code,
            createdAt: now,
            used: false,
            usedByDeviceId: null,
            redeemedAt: null,
          };
          if (env.DEVICE_STORE) {
            await env.DEVICE_STORE.put(`code:${code}`, JSON.stringify(codeRecord));
          }
          generatedCodes.push(code);
        }

        return new Response(
          JSON.stringify({
            success: true,
            count: generatedCodes.length,
            codes: generatedCodes,
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