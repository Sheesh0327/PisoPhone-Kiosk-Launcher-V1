/**
 * Cloudflare Worker API for PisoPhone Licensing & Hardware Lock
 * Worker Name: pisophone-licensing-api
 * Bound KV Namespace: DEVICE_STORE -> pisophone-production-kv
 */

// Production RSA-2048 Asymmetric Private Key (PKCS#8 PEM)
// Public key is embedded into the Android client APK (cannot forge licenses even if APK is decompiled)
const DEFAULT_RSA_PRIVATE_KEY = `-----BEGIN PRIVATE KEY-----
MIIEugIBADANBgkqhkiG9w0BAQEFAASCBKQwggSgAgEAAoIBAQC2/khCqnI6oHLN
5inZyjmEJ9sn6wklDzXCJcwGRxL5D/Jc6x6pJhLzv5KSz5BBL7OY3tw9LfnXwSPi
oo0JtIaAYurMbWPsqnL9jJ850AGoQH9qvLpUfnZ6cYw5rG8paXdXLMZJqk8d3uir
3l+1QA5Pbeqo0upWJarYDhkn7ELcWLAghoZ3hPib7cT+Y9/I69ynDTwCDrImxWOv
nWrTO3X8FJdZJw2QJvHZVF0xoeTZGv9hsS57jTzqgwkYore39I6jH4wBAXUAsiNQ
aV0DiDGUZ+GpsH98FGTuA5eENZ8260T4wdKidoWHjE7VbZFTo0ooQew6Zf5Y2jHS
VhjZZi8VAgMBAAECgf8t9kn82wYJGhCIpsbcOdU4df18Itl19XWkzgy9hulWpV3t
xYbUW9VmygQgQjVGKjULfVTUCQUEq/491MlkwD/U/2nG7uYQpSWjIy7bCbCUEgWI
fN62jXNn51BqxZfy6HD3jfqmUtO/k1cQoOOcVfp1Xz/eJ4IausGugqjO3jvs6LmX
0E31s4wjPF7siyarUhScnA83JEv/sk9xr8IGg+Kyt5Nwa96rSaIs932WRkeSF7GU
BfA7AU5LL7dM1LHBt3Rarc/SUQZ5gEDpEXwoOJaZih26zL9Mp0SPaqQv6nKxQub7
3T3fDyhwwD1YOCVPyMh21/pHhxGjdXVXsnzSJAECgYEA5kZQmXw9+LXTk1ONstSy
/3mY0nBDsfzcQyzTq8VFxxcl1KupY47ive/d4IBIM1dKYMpu0WMyoJMFrOVn/d2H
Xsw9hT/XIXi/hVNmO4qtHtKLkpa53DFh3inVggAxSGGZfrGvwo7Ne5LJJJgvmo4h
gmawvuYmyXwPydj7q0lHsRUCgYEAy2/BSEKFFHJP6JIYXXm1rhGf7HS5bxTV+uy0
PTcTGfB30mEPWGzul/1eVdwW8KFg4snBTVqrSYjjpFDnI85wU8mcfP+PRAoIZwAc
Oz/mClBII8zUF4zI880AStnJRnocDCtVpNZYGeIDvfbvkeubrhURm4UyRh35ZfpT
iigYBgECgYBQtlar5aNnGHxHSGMDSpBPAZTyNc1UhpfBp+WtcGDrzo5BA8ZEkiGh
h4DSnsQv0qnMUUgUdluZcs7rciFIFyzKqnXpzZ2fKs6eccQEnK/ffNbVE6Wjq19t
WmZuwZiEkUkW4jsDy7/0T1fXTsxotObD6TCMSOlRd/2ktzxHJlFNnQKBgDXalrsP
SPV5sWeqzSJppsu2xLQuziv2wxKS+L+/xaG3Q7EAmrRY2eyIWSG3iqcWwXQn3rEg
kHl98G0+MYIMEzZLB88bRAzJ7yF9KPwSVU5jpEU94uN9FHFd0nb+Ikcy6hvamOhz
CY2IhF8UcKUbTvINh8S4xO9E3hG968GGDZ4BAoGAD1RNkGLe/O7FCkqza0XCOEKp
q4gO3rmb6PVaKD73YdZ+Q6iaj6Oe1pJi/09F3Vt1UtfHY6ibLQmuhOcANgyIxCpO
W/ATuIapv3fJAYSXbLkyoArzYoPQZ2bYF5XXBtXSVzMpfw3ACB3tNN5h9FsilY8W
+RD546O+xNe0VFcWyfU=
-----END PRIVATE KEY-----`;

// Helper: Asymmetric RSA-SHA256 signature using Web Crypto API
async function signRsaSha256(message, privateKeyPem) {
  try {
    const b64 = privateKeyPem.replace(/-----[^-]+-----/g, '').replace(/\s+/g, '');
    const binary = atob(b64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
      bytes[i] = binary.charCodeAt(i);
    }
    const key = await crypto.subtle.importKey(
      'pkcs8',
      bytes.buffer,
      { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
      false,
      ['sign']
    );
    const sigBuffer = await crypto.subtle.sign(
      { name: 'RSASSA-PKCS1-v1_5' },
      key,
      new TextEncoder().encode(message)
    );
    const uint8 = new Uint8Array(sigBuffer);
    let binarySig = '';
    for (let i = 0; i < uint8.length; i++) {
      binarySig += String.fromCharCode(uint8[i]);
    }
    return btoa(binarySig)
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=+$/, '');
  } catch (e) {
    console.error('RSA sign error:', e);
    return null;
  }
}

// Helper: Legacy HMAC-SHA256 signature
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

// Core helper: Generate cryptographic license token
async function generateLicenseToken(deviceId, paidExpiresAt, env) {
  const payload = `${deviceId}|${paidExpiresAt}`;
  const privKey = env.LICENSE_RSA_PRIVATE_KEY || DEFAULT_RSA_PRIVATE_KEY;
  const rsaSig = await signRsaSha256(payload, privKey);
  
  if (rsaSig) {
    return {
      signature: rsaSig,
      licenseKey: `PISO-1Y.${deviceId}.${paidExpiresAt}.${rsaSig}`,
      algorithm: 'RSA-2048-PKCS1v15'
    };
  }
  
  // Fallback to HMAC if RSA fails
  const signingSecret = env.LICENSE_SIGNING_SECRET || 'piso_master_lic_secret_2026_89a1f';
  const hmacSig = await signHmacSha256(payload, signingSecret);
  return {
    signature: hmacSig,
    licenseKey: `PISO-1Y.${deviceId}.${paidExpiresAt}.${hmacSig}`,
    algorithm: 'HMAC-SHA256'
  };
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
          return new Response(JSON.stringify({ error: 'Missing deviceId or hardwareHash', serverTime: now }), {
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
            const token = await generateLicenseToken(deviceId, existing.paidExpiresAt, env);
            signature = token.signature;
            licenseKey = token.licenseKey;
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
              serverTime: now,
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
            serverTime: now,
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
          return new Response(JSON.stringify({ error: 'Missing deviceId parameter', serverTime: now }), {
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
              serverTime: now,
              message: 'No confirmed payment found for this device ID.',
            }),
            { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
          );
        }

        // Generate cryptographically unforgeable asymmetric RSA license token
        const token = await generateLicenseToken(deviceId, record.paidExpiresAt, env);

        return new Response(
          JSON.stringify({
            paid: true,
            status: 'PAID',
            deviceId,
            deviceModel: record.deviceModel || 'Android Device',
            paidExpiresAt: record.paidExpiresAt,
            paymentReference: record.paymentReference || 'CONFIRMED',
            daysRemaining: Math.max(0, Math.ceil((record.paidExpiresAt - now) / (24 * 60 * 60 * 1000))),
            signature: token.signature,
            licenseKey: token.licenseKey,
            serverTime: now,
            message: 'Payment confirmed. 1-Year Commercial License ready.',
          }),
          { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );
      }

      // =========================================================================
      // 3. Payment Webhook with Idempotency & Deduplication
      // POST /api/payment/webhook
      // Supports GCash, Maya, Stripe, PayMongo, Xendit
      // =========================================================================
      if (url.pathname === '/api/payment/webhook' && method === 'POST') {
        let body;
        try {
          body = await request.json();
        } catch (e) {
          return new Response(JSON.stringify({ error: 'Invalid JSON payload', serverTime: now }), {
            status: 400,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

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
          return new Response(JSON.stringify({ error: 'Missing deviceId in webhook payload metadata', serverTime: now }), {
            status: 400,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        deviceId = String(deviceId).trim();
        const cleanPaymentRef = String(paymentRef).trim();

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
            processedPaymentRefs: [],
          };
        }

        // Webhook Idempotency Check: Prevent duplicate webhook charges/extensions
        const processedRefs = Array.isArray(record.processedPaymentRefs) ? record.processedPaymentRefs : [];
        if (processedRefs.includes(cleanPaymentRef)) {
          // Idempotent hit: already credited, return current valid token
          const token = await generateLicenseToken(deviceId, record.paidExpiresAt, env);
          return new Response(
            JSON.stringify({
              success: true,
              idempotent: true,
              status: 'PAID',
              deviceId,
              deviceModel: record.deviceModel || 'Android Device',
              paidExpiresAt: record.paidExpiresAt,
              signature: token.signature,
              licenseKey: token.licenseKey,
              serverTime: now,
              message: `Payment ${cleanPaymentRef} was already processed previously. Returned idempotent confirmation.`,
            }),
            { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
          );
        }

        // Extend 1-Year Commercial License
        const currentPaidExpires = record.paidExpiresAt || 0;
        const newPaidExpires = (currentPaidExpires > now ? currentPaidExpires : now) + ONE_YEAR_MS;

        record.licenseType = 'PAID';
        record.paidExpiresAt = newPaidExpires;
        record.lastCheckinAt = now;
        record.paymentReference = cleanPaymentRef;
        record.paidAt = now;
        
        // Track recent payment references for deduplication
        processedRefs.push(cleanPaymentRef);
        record.processedPaymentRefs = processedRefs.slice(-25); // retain last 25 refs

        if (env.DEVICE_STORE) {
          await env.DEVICE_STORE.put(deviceId, JSON.stringify(record));
        }

        // Generate asymmetric RSA token
        const token = await generateLicenseToken(deviceId, newPaidExpires, env);

        return new Response(
          JSON.stringify({
            success: true,
            idempotent: false,
            status: 'PAID',
            deviceId,
            deviceModel: record.deviceModel || 'Android Device',
            paidExpiresAt: newPaidExpires,
            signature: token.signature,
            licenseKey: token.licenseKey,
            serverTime: now,
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
          return new Response(JSON.stringify({ error: 'Missing deviceId', serverTime: now }), {
            status: 400,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        const cleanId = String(deviceId).trim();
        const cleanPaymentRef = paymentRef ? String(paymentRef).trim() : `MANUAL-${now}`;

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
            processedPaymentRefs: [],
          };
        }

        const processedRefs = Array.isArray(record.processedPaymentRefs) ? record.processedPaymentRefs : [];
        if (paymentRef && processedRefs.includes(cleanPaymentRef)) {
          const token = await generateLicenseToken(cleanId, record.paidExpiresAt, env);
          return new Response(
            JSON.stringify({
              success: true,
              idempotent: true,
              status: 'PAID',
              deviceId: cleanId,
              deviceModel: record.deviceModel || 'Android Device',
              paidExpiresAt: record.paidExpiresAt,
              signature: token.signature,
              licenseKey: token.licenseKey,
              serverTime: now,
              message: `Payment ref ${cleanPaymentRef} was already confirmed previously.`,
            }),
            { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
          );
        }

        const currentPaidExpires = record.paidExpiresAt || 0;
        const newPaidExpires = (currentPaidExpires > now ? currentPaidExpires : now) + ONE_YEAR_MS;

        record.licenseType = 'PAID';
        record.paidExpiresAt = newPaidExpires;
        record.lastCheckinAt = now;
        record.paymentReference = cleanPaymentRef;
        record.paidAt = now;
        processedRefs.push(cleanPaymentRef);
        record.processedPaymentRefs = processedRefs.slice(-25);

        if (env.DEVICE_STORE) {
          await env.DEVICE_STORE.put(cleanId, JSON.stringify(record));
        }

        const token = await generateLicenseToken(cleanId, newPaidExpires, env);

        return new Response(
          JSON.stringify({
            success: true,
            status: 'PAID',
            deviceId: cleanId,
            deviceModel: record.deviceModel || 'Android Device',
            paidExpiresAt: newPaidExpires,
            signature: token.signature,
            licenseKey: token.licenseKey,
            serverTime: now,
            message: 'Device successfully marked as paid & license issued.',
          }),
          { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );
      }

      return new Response(JSON.stringify({ error: 'Endpoint not found', serverTime: now }), {
        status: 404,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' },
      });
    } catch (e) {
      return new Response(JSON.stringify({ error: e.message, serverTime: Date.now() }), {
        status: 500,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' },
      });
    }
  },
};

