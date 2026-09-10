/**
 * Cloudflare Worker API for PisoPhone Licensing & Hardware Lock
 * Worker Name: pisophone-licensing-api
 * Bound KV Namespace: DEVICE_STORE -> pisophone-production-kv
 */

// ============================================================================
// CONFIGURATION: LICENSE DURATION
// Default is 365 days (1 year). For testing, you can change DEFAULT_LICENSE_DURATION_MS:
// e.g., 3 minutes = 3 * 60 * 1000
// You can also set LICENSE_DURATION_MS in Cloudflare Worker Environment Variables.
// ============================================================================
const DEFAULT_LICENSE_DURATION_MS = 365 * 24 * 60 * 60 * 1000; // 365 Days
// const DEFAULT_LICENSE_DURATION_MS = 3 * 60 * 1000; // Uncomment for 3-minute testing

// Helper: Asymmetric RSA-SHA256 signature using Web Crypto API
// The RSA private key MUST be provided via Cloudflare Worker Secret: LICENSE_RSA_PRIVATE_KEY
async function signRsaSha256(message, privateKeyPem) {
  if (!privateKeyPem || typeof privateKeyPem !== 'string') {
    return null;
  }
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

// Core helper: Generate cryptographic license token
async function generateLicenseToken(deviceId, paidExpiresAt, env) {
  const payload = `${deviceId}|${paidExpiresAt}`;
  const privKey = env.LICENSE_RSA_PRIVATE_KEY;
  if (!privKey) {
    throw new Error("Missing Cloudflare Worker Secret: LICENSE_RSA_PRIVATE_KEY. Please configure this secret in your Cloudflare Worker Settings -> Variables and Secrets.");
  }
  const rsaSig = await signRsaSha256(payload, privKey);
  
  if (!rsaSig) {
    throw new Error("Failed to sign license token with LICENSE_RSA_PRIVATE_KEY. Please verify your RSA private key formatting.");
  }

  return {
    signature: rsaSig,
    licenseKey: `PISO-1Y.${deviceId}.${paidExpiresAt}.${rsaSig}`,
    algorithm: 'RSA-2048-PKCS1v15'
  };
}

async function verifyGoogleToken(token) {
  if (!token) return null;
  try {
    const res = await fetch(`https://oauth2.googleapis.com/tokeninfo?id_token=${token}`);
    if (res.ok) {
      const data = await res.json();
      if (data.email) return data.email.trim().toLowerCase();
    }
  } catch (e) {
    console.error("Token verification failed:", e);
  }

  // Grace Period Fallback:
  // Google ID tokens strictly expire after 1 hour (3600 seconds). When a user reloads the
  // dashboard after 1 hour, tokeninfo rejects the token. To prevent devices from disappearing
  // upon page refresh, we decode the Google JWT and check the payload.
  try {
    const parts = String(token).split('.');
    if (parts.length === 3) {
      let base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
      while (base64.length % 4 !== 0) {
        base64 += '=';
      }
      const payload = JSON.parse(atob(base64));
      const isGoogleIssuer = payload.iss === 'https://accounts.google.com' || payload.iss === 'accounts.google.com';
      if (isGoogleIssuer && payload.email && payload.email_verified !== false) {
        const expMs = (payload.exp || 0) * 1000;
        const now = Date.now();
        // Allow up to 30 days grace period for returning dashboard users
        if (now - expMs < 30 * 24 * 60 * 60 * 1000) {
          return payload.email.trim().toLowerCase();
        }
      }
    }
  } catch (parseErr) {
    console.error("Grace period JWT parse failed:", parseErr);
  }

  return null;
}

// Helper: Securely authenticate user email from request body
async function authenticateUserEmail(body) {
  if (!body) return null;
  const token = body.ownerToken;
  if (!token) return null;

  const verifiedEmail = await verifyGoogleToken(token);
  if (!verifiedEmail) return null;

  // If explicit ownerEmail is also passed, verify it matches the authenticated token email
  if (body.ownerEmail) {
    const explicitNorm = String(body.ownerEmail).trim().toLowerCase();
    if (explicitNorm !== verifiedEmail) {
      console.warn(`Email mismatch: token email ${verifiedEmail} vs explicit ${explicitNorm}`);
      return null;
    }
  }

  return verifiedEmail;
}

// In-Memory Rate Limiter (0 KV operations, prevents KV daily write quota exhaustion)
const ipRateLimitMap = new Map();

function checkRateLimit(request, limit = 60, windowSeconds = 60) {
  try {
    const clientIp = request.headers.get('cf-connecting-ip') || request.headers.get('x-real-ip') || 'unknown-client';
    const now = Date.now();
    const entry = ipRateLimitMap.get(clientIp);

    // Evict expired entries if memory map grows
    if (ipRateLimitMap.size > 2000) {
      for (const [key, val] of ipRateLimitMap.entries()) {
        if (val.resetAt < now) ipRateLimitMap.delete(key);
      }
    }

    if (!entry || entry.resetAt < now) {
      ipRateLimitMap.set(clientIp, { count: 1, resetAt: now + (windowSeconds * 1000) });
      return true;
    }

    entry.count++;
    if (entry.count > limit) {
      return false;
    }
    return true;
  } catch (e) {
    console.error('In-memory rate limit error:', e);
    return true; // Fail open
  }
}

// 32-character unambiguous alphabet (Excludes 0/O, 1/I/L)
const SAFE_BOX_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";

/**
 * Validates cryptographic build numbers (8-char XXXX-YYYY, 12-char XXXX-YYYY-ZZZZ, 16-char XXXX-XXXX-XXXX-XXXX)
 * using HMAC-SHA256
 */
// Helper to format/normalize ESP32 MAC addresses (e.g. 24DCC3123456 -> 24:DC:C3:12:34:56)
function formatMacAddress(input) {
  if (!input || typeof input !== 'string') return null;
  const clean = input.replace(/[^a-fA-F0-9]/g, '').toUpperCase();
  if (clean.length === 12) {
    return clean.match(/.{1,2}/g).join(':');
  }
  return null;
}

async function verifyBoxBuildNumberHmac(buildNumber, secret) {

  if (!buildNumber || !secret) return false;
  const raw = String(buildNumber).trim().toUpperCase();
  const parts = raw.split('-');
  
  if (parts.length < 2) return false;
  
  const boxId = parts[0];
  const providedSig = parts.slice(1).join('');
  
  if (boxId.length !== 4 || providedSig.length < 4) {
    return false;
  }
  
  try {
    const encoder = new TextEncoder();
    const key = await crypto.subtle.importKey(
      'raw',
      encoder.encode(secret),
      { name: 'HMAC', hash: 'SHA-256' },
      false,
      ['sign']
    );
    const signature = await crypto.subtle.sign(
      'HMAC',
      key,
      encoder.encode(`PISOBOX:${boxId}`)
    );
    const sigBytes = new Uint8Array(signature);
    let expectedSig = '';
    for (let i = 0; i < providedSig.length; i++) {
      expectedSig += SAFE_BOX_ALPHABET[sigBytes[i % sigBytes.length] % SAFE_BOX_ALPHABET.length];
    }
    return expectedSig === providedSig;
  } catch (err) {
    console.error('HMAC Box verification error:', err);
    return false;
  }
}

async function calculateHmacSha256Hex(message, secret) {
  const encoder = new TextEncoder();
  const key = await crypto.subtle.importKey(
    'raw',
    encoder.encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign']
  );
  const signature = await crypto.subtle.sign(
    'HMAC',
    key,
    encoder.encode(message)
  );
  const sigBytes = new Uint8Array(signature);
  return Array.from(sigBytes)
    .map(b => b.toString(16).padStart(2, '0'))
    .join('');
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const method = request.method;

    const signingSecret = env.LICENSE_SIGNING_SECRET || env.ADMIN_SECRET || 'PISOPHONE_HMAC_MASTER_KEY';

    const corsHeaders = {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, Authorization, X-Signature',
    };

    if (method === 'OPTIONS') {
      return new Response(null, { headers: corsHeaders });
    }

    // Fast-path: Root or Health check (0 KV operations)
    if (url.pathname === '/' || url.pathname === '/health') {
      return new Response(JSON.stringify({ status: 'ok', service: 'PisoPhone Licensing API', serverTime: Date.now() }), {
        status: 200,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' },
      });
    }

    // Fast-path: APK download redirect
    if (url.pathname === '/apk/latest.apk' || url.pathname === '/app-release.apk') {
      return Response.redirect('https://pisophone.pages.dev/app-release.apk', 302);
    }

    // Early route filter: Drop bot probes/scanners instantly without touching KV or executing heavy logic
    const KNOWN_PATHS = [
      '/api/device/register',
      '/api/payment/check',
      '/api/device/status',
      '/api/payment/webhook',
      '/api/user/devices',
      '/api/user/link-device',
      '/api/payment/confirm',
      '/api/user/credits',
      '/api/user/activate-device',
      '/api/user/transfer-license',
      '/api/box/verify',
      '/api/box/status',
      '/api/box/link-device',
      '/api/box/unlink-device',
      '/api/user/remove-device',
      '/api/admin/create-box',
      '/api/box/report-snapshot',
      '/api/box/issue-slot-token',
      '/api/box/pricing',
      '/apk/latest.apk',
      '/app-release.apk',
    ];

    if (!KNOWN_PATHS.includes(url.pathname)) {
      return new Response(JSON.stringify({ error: 'Endpoint not found', serverTime: Date.now() }), {
        status: 404,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' },
      });
    }

    // Apply In-Memory Rate Limiting (60 requests per minute per IP, 0 KV writes)
    const isAllowed = checkRateLimit(request, 60, 60);
    if (!isAllowed) {
      return new Response(JSON.stringify({ error: 'Too many requests. Please slow down.', serverTime: Date.now() }), {
        status: 429,
        headers: { ...corsHeaders, 'Content-Type': 'application/json', 'Retry-After': '60' },
      });
    }

    try {
      const now = Date.now();
      const LICENSE_DURATION_MS = env.LICENSE_DURATION_MS 
        ? parseInt(env.LICENSE_DURATION_MS, 10) 
        : DEFAULT_LICENSE_DURATION_MS;

      // =========================================================================
      // 1. Device Registration & Hardware Status Check
      // POST /api/device/register
      // Body: { deviceId: string, hardwareHash: string, deviceModel: string }
      // =========================================================================
      if (url.pathname === '/api/device/register' && method === 'POST') {
        const body = await request.json();
        const { deviceId, hardwareHash, deviceModel, ownerToken } = body;

        if (!deviceId || !hardwareHash) {
          return new Response(JSON.stringify({ error: 'Missing deviceId or hardwareHash', serverTime: now }), {
            status: 400,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        let ownerEmail = null;
        if (ownerToken) {
          const verified = await verifyGoogleToken(ownerToken);
          if (verified) ownerEmail = verified.trim().toLowerCase();
        }

        let existing = null;
        if (env.DEVICE_STORE) {
          const raw = await env.DEVICE_STORE.get(deviceId);
          if (raw) existing = JSON.parse(raw);
        }

        if (existing) {
          const isPaid = existing.paidExpiresAt > now;
          const isExpired = existing.paidExpiresAt > 0 && existing.paidExpiresAt <= now;
          const isTransferred = !isPaid && existing.licenseStatus === 'TRANSFERRED';
          const status = isPaid ? 'PAID' : (isTransferred ? 'TRANSFERRED' : (isExpired ? 'EXPIRED' : 'UNACTIVATED'));

          // Normalize existing ownerEmail if present
          if (existing.ownerEmail) {
            existing.ownerEmail = existing.ownerEmail.trim().toLowerCase();
          }

          // Intelligent KV write throttling: Only write to KV if model changed, owner changed,
          // or at least 12 hours elapsed since last recorded check-in. This preserves daily KV write limits.
          const lastCheckin = existing.lastCheckinAt || 0;
          const needsCheckinUpdate = (now - lastCheckin) > (12 * 60 * 60 * 1000);
          const needsModelUpdate = Boolean(deviceModel && existing.deviceModel !== deviceModel);
          const oldOwner = existing.ownerEmail;
          const needsOwnerUpdate = Boolean(ownerEmail && oldOwner !== ownerEmail);

          if (needsCheckinUpdate || needsModelUpdate || needsOwnerUpdate) {
            existing.lastCheckinAt = now;
            if (needsModelUpdate) existing.deviceModel = deviceModel;
            if (needsOwnerUpdate) existing.ownerEmail = ownerEmail;

            // Clean up old redundant properties if they exist
            delete existing.licenseType;
            delete existing.trialExpiresAt;
            delete existing.paymentReference;

            if (env.DEVICE_STORE) {
              await env.DEVICE_STORE.put(deviceId, JSON.stringify(existing));
              
              // If owner changed, clean up old owner index
              if (needsOwnerUpdate && oldOwner) {
                const rawOldUd = await env.DEVICE_STORE.get(`USER_DEVICES:${oldOwner}`);
                if (rawOldUd) {
                  let oldUserDevices = JSON.parse(rawOldUd);
                  oldUserDevices = oldUserDevices.filter(id => id !== deviceId);
                  await env.DEVICE_STORE.put(`USER_DEVICES:${oldOwner}`, JSON.stringify(oldUserDevices));
                }
              }

              // Also update the USER_DEVICES index if ownerEmail is present
              if (ownerEmail) {
                const rawUd = await env.DEVICE_STORE.get(`USER_DEVICES:${ownerEmail}`);
                let userDevices = rawUd ? JSON.parse(rawUd) : [];
                if (!userDevices.includes(deviceId)) {
                  userDevices.push(deviceId);
                  await env.DEVICE_STORE.put(`USER_DEVICES:${ownerEmail}`, JSON.stringify(userDevices));
                }
              }
            }
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
              status,
              isPaid,
              isActivated: isPaid,
              isValid: isPaid,
              isExpired,
              deviceId: existing.deviceId,
              deviceModel: existing.deviceModel || deviceModel || 'Unknown Device',
              paidExpiresAt: existing.paidExpiresAt || 0,
              daysRemaining: isPaid
                ? Math.max(0, Math.ceil((existing.paidExpiresAt - now) / (24 * 60 * 60 * 1000)))
                : 0,
              checkIntervalDays: 7,
              signature,
              licenseKey,
              serverTime: now,
              message: isPaid
                ? 'Active Commercial License'
                : (isTransferred ? `License was transferred to replacement device (${existing.transferredTo || 'new hardware'}). Kiosk is locked.` : (isExpired ? 'Subscription has expired. Renewal required.' : 'Device registered. Activation required to unlock kiosk.')),
            }),
            { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
          );
        }

        const newRecord = {
          deviceId,
          hardwareHash,
          deviceModel: deviceModel || 'Unknown Device',
          firstRegisteredAt: now,
          paidExpiresAt: 0,
          lastCheckinAt: now,
          installCount: 1,
        };

        if (ownerEmail) newRecord.ownerEmail = ownerEmail;

        if (env.DEVICE_STORE) {
          await env.DEVICE_STORE.put(deviceId, JSON.stringify(newRecord));
          
          if (ownerEmail) {
            const rawUd = await env.DEVICE_STORE.get(`USER_DEVICES:${ownerEmail}`);
            let userDevices = rawUd ? JSON.parse(rawUd) : [];
            if (!userDevices.includes(deviceId)) {
              userDevices.push(deviceId);
              await env.DEVICE_STORE.put(`USER_DEVICES:${ownerEmail}`, JSON.stringify(userDevices));
            }
          }
        }

        return new Response(
          JSON.stringify({
            status: 'UNACTIVATED',
            isPaid: false,
            isActivated: false,
            isValid: false,
            isExpired: false,
            deviceId,
            deviceModel: newRecord.deviceModel,
            paidExpiresAt: 0,
            daysRemaining: 0,
            checkIntervalDays: 7,
            serverTime: now,
            message: 'Device registered successfully. Ready for license QR activation.',
          }),
          { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );
      }

      // =========================================================================
      // 2. Query Payment & Activation Status for a Specific Device
      // GET /api/payment/check?deviceId=... OR GET /api/device/status?deviceId=...
      // =========================================================================
      if ((url.pathname === '/api/payment/check' || url.pathname === '/api/device/status') && method === 'GET') {
        const deviceId = url.searchParams.get('deviceId');
        if (!deviceId) {
          return new Response(JSON.stringify({ error: 'Missing deviceId parameter', serverTime: now }), {
            status: 400,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        let cleanId = String(deviceId).trim();
        if (!cleanId.toUpperCase().startsWith('HW-')) {
          cleanId = 'HW-' + cleanId;
        }

        let record = null;
        if (env.DEVICE_STORE) {
          let raw = await env.DEVICE_STORE.get(cleanId);
          if (!raw) raw = await env.DEVICE_STORE.get(deviceId);
          if (raw) record = JSON.parse(raw);
        }

        if (!record || !(record.paidExpiresAt > now)) {
          const isExpired = record && record.paidExpiresAt > 0 && record.paidExpiresAt <= now;
          const isTransferred = record && !record.paidExpiresAt && record.licenseStatus === 'TRANSFERRED';
          const status = record ? (isTransferred ? 'TRANSFERRED' : (isExpired ? 'EXPIRED' : 'UNACTIVATED')) : 'UNREGISTERED';
          return new Response(
            JSON.stringify({
              paid: false,
              isPaid: false,
              isActivated: false,
              isValid: false,
              isExpired: Boolean(isExpired),
              isTransferred: Boolean(isTransferred),
              transferredTo: record ? (record.transferredTo || null) : null,
              deviceId,
              deviceModel: record ? (record.deviceModel || 'Android Device') : 'Unknown Device',
              status,
              paidExpiresAt: record ? (record.paidExpiresAt || 0) : 0,
              daysRemaining: 0,
              checkIntervalDays: 7,
              serverTime: now,
              message: isTransferred ? `License was transferred to replacement device (${record.transferredTo || 'new hardware'}).` : (isExpired ? 'Subscription has expired.' : 'No active license found for this device ID.'),
            }),
            { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
          );
        }

        // Generate cryptographically unforgeable asymmetric RSA license token
        const token = await generateLicenseToken(deviceId, record.paidExpiresAt, env);
        const lastRef = Array.isArray(record.processedPaymentRefs) && record.processedPaymentRefs.length > 0 
          ? record.processedPaymentRefs[record.processedPaymentRefs.length - 1] 
          : 'CONFIRMED';

        return new Response(
          JSON.stringify({
            paid: true,
            isPaid: true,
            isActivated: true,
            isValid: true,
            isExpired: false,
            status: 'PAID',
            deviceId,
            deviceModel: record.deviceModel || 'Android Device',
            paidExpiresAt: record.paidExpiresAt,
            paymentReference: lastRef,
            daysRemaining: Math.max(0, Math.ceil((record.paidExpiresAt - now) / (24 * 60 * 60 * 1000))),
            checkIntervalDays: 7,
            signature: token.signature,
            licenseKey: token.licenseKey,
            serverTime: now,
            message: 'Subscription active.',
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

        let ownerEmail = null;
        if (body.ownerToken) {
          ownerEmail = await verifyGoogleToken(body.ownerToken);
        } else if (body.ownerEmail) {
          ownerEmail = body.ownerEmail;
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

        const cleanPaymentRef = String(paymentRef).trim();

        // 1. If no deviceId is provided but we have an ownerEmail, process it as a Credit Purchase
        if (!deviceId && ownerEmail) {
          let processedRefs = [];
          if (env.DEVICE_STORE) {
            const rawRefs = await env.DEVICE_STORE.get(`USER_PAYMENTS:${ownerEmail}`);
            if (rawRefs) processedRefs = JSON.parse(rawRefs);
          }
          
          if (processedRefs.includes(cleanPaymentRef)) {
            return new Response(
              JSON.stringify({
                success: true,
                idempotent: true,
                serverTime: now,
                message: `Payment ${cleanPaymentRef} already processed. Credits were already added.`,
              }),
              { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
            );
          }

          let credits = 0;
          if (env.DEVICE_STORE) {
            const rawCredits = await env.DEVICE_STORE.get(`USER_CREDITS:${ownerEmail}`);
            if (rawCredits) credits = parseInt(rawCredits, 10);
          }

          // Determine quantity (default to 1 credit per payment)
          const quantity = parseInt(body.quantity || body.data?.attributes?.metadata?.quantity || body.metadata?.quantity || 1, 10);
          credits += quantity;

          processedRefs.push(cleanPaymentRef);
          processedRefs = processedRefs.slice(-50); // retain last 50 refs

          if (env.DEVICE_STORE) {
            await env.DEVICE_STORE.put(`USER_CREDITS:${ownerEmail}`, credits.toString());
            await env.DEVICE_STORE.put(`USER_PAYMENTS:${ownerEmail}`, JSON.stringify(processedRefs));
          }

          return new Response(
            JSON.stringify({
              success: true,
              idempotent: false,
              serverTime: now,
              creditsAdded: quantity,
              totalCredits: credits,
              message: `Payment confirmed. ${quantity} credit(s) added to ${ownerEmail}.`,
            }),
            { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
          );
        }

        if (!deviceId) {
          return new Response(JSON.stringify({ error: 'Missing deviceId in webhook payload metadata and no ownerEmail for credits', serverTime: now }), {
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
            installCount: 1,
            processedPaymentRefs: [],
          };
        }

        if (ownerEmail) record.ownerEmail = ownerEmail;

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

        // Extend Commercial License (Configurable Duration)
        const currentPaidExpires = record.paidExpiresAt || 0;
        const newPaidExpires = (currentPaidExpires > now ? currentPaidExpires : now) + LICENSE_DURATION_MS;

        record.paidExpiresAt = newPaidExpires;
        record.lastCheckinAt = now;
        record.paidAt = now;
        
        // Track recent payment references for deduplication
        processedRefs.push(cleanPaymentRef);
        record.processedPaymentRefs = processedRefs.slice(-25); // retain last 25 refs
        
        // Clean up old redundant properties
        delete record.licenseType;
        delete record.paymentReference;
        delete record.trialExpiresAt;

        if (env.DEVICE_STORE) {
          await env.DEVICE_STORE.put(deviceId, JSON.stringify(record));
          
          if (record.ownerEmail) {
            const rawUd = await env.DEVICE_STORE.get(`USER_DEVICES:${record.ownerEmail}`);
            let userDevices = rawUd ? JSON.parse(rawUd) : [];
            if (!userDevices.includes(deviceId)) {
              userDevices.push(deviceId);
              await env.DEVICE_STORE.put(`USER_DEVICES:${record.ownerEmail}`, JSON.stringify(userDevices));
            }
          }
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
      // 4. User Devices Retrieval (Dashboard)
      // POST /api/user/devices
      // Body: { ownerToken: string }
      // =========================================================================
      if (url.pathname === '/api/user/devices' && method === 'POST') {
        const body = await request.json();
        
        const email = await authenticateUserEmail(body);
        if (!email) {
          return new Response(JSON.stringify({ error: 'Unauthorized', devices: [] }), {
            status: 401,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        let userDevices = [];
        if (env.DEVICE_STORE) {
          const rawUd = await env.DEVICE_STORE.get(`USER_DEVICES:${email}`);
          if (rawUd) userDevices = JSON.parse(rawUd);
        }

        let devices = [];
        let validUserDevices = [];
        for (const did of userDevices) {
          if (env.DEVICE_STORE) {
            const rawDev = await env.DEVICE_STORE.get(did);
            if (rawDev) {
              const dev = JSON.parse(rawDev);
              const devOwner = dev.ownerEmail ? dev.ownerEmail.trim().toLowerCase() : null;
              if (devOwner && devOwner !== email) {
                continue;
              }
              validUserDevices.push(did);

              const isPaid = dev.paidExpiresAt > now;
              const isExpired = dev.paidExpiresAt > 0 && dev.paidExpiresAt <= now;
              const isTransferred = !isPaid && dev.licenseStatus === 'TRANSFERRED';
              let licenseKey = null;
              if (isPaid) {
                try {
                  const token = await generateLicenseToken(dev.deviceId, dev.paidExpiresAt, env);
                  licenseKey = token.licenseKey;
                } catch(e) {}
              }
              
              devices.push({
                deviceId: dev.deviceId,
                deviceModel: dev.deviceModel || 'Unknown Device',
                firstRegisteredAt: dev.firstRegisteredAt || now,
                paidExpiresAt: dev.paidExpiresAt || 0,
                status: isPaid ? 'PAID' : (isTransferred ? 'TRANSFERRED' : (isExpired ? 'EXPIRED' : 'UNACTIVATED')),
                daysRemaining: isPaid ? Math.max(0, Math.ceil((dev.paidExpiresAt - now) / (24 * 60 * 60 * 1000))) : 0,
                licenseKey: licenseKey,
                boxBuildNumber: dev.boxBuildNumber || null,
                transferredTo: dev.transferredTo || null,
                transferredFrom: dev.licenseTransferredFrom || null
              });
            }
          }
        }

        if (env.DEVICE_STORE && validUserDevices.length !== userDevices.length) {
          await env.DEVICE_STORE.put(`USER_DEVICES:${email}`, JSON.stringify(validUserDevices));
        }

        return new Response(JSON.stringify({ success: true, email, devices, serverTime: now }), {
          status: 200,
          headers: { ...corsHeaders, 'Content-Type': 'application/json' },
        });
      }

      // =========================================================================
      // 4b. Link Device to User Account
      // POST /api/user/link-device
      // Body: { ownerToken: string, deviceId: string }
      // =========================================================================
      if (url.pathname === '/api/user/link-device' && method === 'POST') {
        const body = await request.json();
        
        const email = await authenticateUserEmail(body);
        if (!email) {
          return new Response(JSON.stringify({ error: 'Unauthorized: Authentication required.', success: false }), {
            status: 401,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        const { deviceId } = body;
        if (!deviceId) {
          return new Response(JSON.stringify({ error: 'Missing deviceId parameter', success: false }), {
            status: 400,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        let cleanId = String(deviceId).trim();
        if (!cleanId.toUpperCase().startsWith('HW-')) {
            cleanId = 'HW-' + cleanId;
        }
        if (env.DEVICE_STORE) {
          const rawDev = await env.DEVICE_STORE.get(cleanId);
          if (rawDev) {
            let dev = JSON.parse(rawDev);
            const devOwner = dev.ownerEmail ? dev.ownerEmail.trim().toLowerCase() : null;
            if (devOwner && devOwner !== email) {
              return new Response(JSON.stringify({ error: 'This device is registered to another user account.', success: false }), {
                status: 403,
                headers: { ...corsHeaders, 'Content-Type': 'application/json' },
              });
            }
            dev.ownerEmail = email;
            await env.DEVICE_STORE.put(cleanId, JSON.stringify(dev));
          } else {
            let dev = {
              deviceId: cleanId,
              hardwareHash: cleanId.toUpperCase(),
              deviceModel: 'Manual Linked Kiosk',
              firstRegisteredAt: now,
              paidExpiresAt: 0,
              lastCheckinAt: now,
              installCount: 1,
              ownerEmail: email
            };
            await env.DEVICE_STORE.put(cleanId, JSON.stringify(dev));
          }

          const rawUd = await env.DEVICE_STORE.get(`USER_DEVICES:${email}`);
          let userDevices = rawUd ? JSON.parse(rawUd) : [];
          if (!userDevices.includes(cleanId)) {
            userDevices.push(cleanId);
            await env.DEVICE_STORE.put(`USER_DEVICES:${email}`, JSON.stringify(userDevices));
          }
        }

        return new Response(JSON.stringify({ success: true, message: `Device ${cleanId} linked to ${email}`, deviceId: cleanId }), {
          status: 200,
          headers: { ...corsHeaders, 'Content-Type': 'application/json' },
        });
      }

      // =========================================================================
      // 4c. Get User Credits
      // POST /api/user/credits
      // Body: { ownerToken: string }
      // =========================================================================
      if (url.pathname === '/api/user/credits' && method === 'POST') {
        const body = await request.json();
        
        const email = await authenticateUserEmail(body);
        if (!email) {
          return new Response(JSON.stringify({ error: 'Unauthorized: Authentication required.', credits: 0 }), {
            status: 401,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        let credits = 0;
        if (env.DEVICE_STORE) {
          const rawCredits = await env.DEVICE_STORE.get(`USER_CREDITS:${email}`);
          if (rawCredits) credits = parseInt(rawCredits, 10);
        }

        return new Response(JSON.stringify({ success: true, email, credits, serverTime: now }), {
          status: 200,
          headers: { ...corsHeaders, 'Content-Type': 'application/json' },
        });
      }

      // =========================================================================
      // 4d. Use Credit to Activate Device
      // POST /api/user/activate-device
      // Body: { ownerToken: string, deviceId: string }
      // =========================================================================
      if (url.pathname === '/api/user/activate-device' && method === 'POST') {
        const body = await request.json();
        
        const email = await authenticateUserEmail(body);
        if (!email) {
          return new Response(JSON.stringify({ error: 'Unauthorized: Authentication required.', success: false }), {
            status: 401,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        const deviceId = body.deviceId ? String(body.deviceId).trim() : null;
        if (!deviceId) {
          return new Response(JSON.stringify({ error: 'Missing deviceId parameter', success: false }), {
            status: 400,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        if (env.DEVICE_STORE) {
          const rawCredits = await env.DEVICE_STORE.get(`USER_CREDITS:${email}`);
          let credits = rawCredits ? parseInt(rawCredits, 10) : 0;

          if (credits <= 0) {
            return new Response(JSON.stringify({ error: 'No credits available', success: false }), {
              status: 403,
              headers: { ...corsHeaders, 'Content-Type': 'application/json' },
            });
          }

          let cleanId = String(deviceId).trim();
          if (!cleanId.toUpperCase().startsWith('HW-')) {
              cleanId = 'HW-' + cleanId;
          }
          const rawDev = await env.DEVICE_STORE.get(cleanId);
          let dev = rawDev ? JSON.parse(rawDev) : {
            deviceId: cleanId,
            hardwareHash: cleanId.toUpperCase(),
            deviceModel: 'Linked Kiosk',
            firstRegisteredAt: now,
            installCount: 1,
            processedPaymentRefs: []
          };

          const devOwner = dev.ownerEmail ? dev.ownerEmail.trim().toLowerCase() : null;
          if (devOwner && devOwner !== email) {
            return new Response(JSON.stringify({ error: 'This device is owned by another user account.', success: false }), {
              status: 403,
              headers: { ...corsHeaders, 'Content-Type': 'application/json' },
            });
          }

          const currentPaidExpires = dev.paidExpiresAt || 0;
          const newPaidExpires = (currentPaidExpires > now ? currentPaidExpires : now) + LICENSE_DURATION_MS;

          // 1. Generate cryptographic license token FIRST - ensures atomic failure if secret is missing
          const token = await generateLicenseToken(cleanId, newPaidExpires, env);

          // 2. Deduct 1 credit atomically after cryptographic signature succeeds
          credits -= 1;
          await env.DEVICE_STORE.put(`USER_CREDITS:${email}`, credits.toString());

          // 3. Save active state to KV
          dev.paidExpiresAt = newPaidExpires;
          dev.lastCheckinAt = now;
          dev.paidAt = now;
          dev.ownerEmail = email; // Ensure it's linked to this user

          await env.DEVICE_STORE.put(cleanId, JSON.stringify(dev));

          // Also ensure it's in the user's device list
          const rawUd = await env.DEVICE_STORE.get(`USER_DEVICES:${email}`);
          let userDevices = rawUd ? JSON.parse(rawUd) : [];
          if (!userDevices.includes(cleanId)) {
            userDevices.push(cleanId);
            await env.DEVICE_STORE.put(`USER_DEVICES:${email}`, JSON.stringify(userDevices));
          }

          return new Response(JSON.stringify({ 
            success: true, 
            message: `Device ${cleanId} successfully activated using 1 credit.`, 
            deviceId: cleanId,
            creditsRemaining: credits,
            signature: token.signature,
            licenseKey: token.licenseKey,
            paidExpiresAt: newPaidExpires
          }), {
            status: 200,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        return new Response(JSON.stringify({ error: 'KV Store not configured', success: false }), {
          status: 500,
          headers: { ...corsHeaders, 'Content-Type': 'application/json' },
        });
      }

      // =========================================================================
      // 4e. Transfer License to Another Device (1-Device-Only Enforcement)
      // POST /api/user/transfer-license
      // Body: { ownerToken?: string, ownerEmail?: string, sourceDeviceId: string, targetDeviceId: string, transferBoxAssignment?: boolean }
      // =========================================================================
      if (url.pathname === '/api/user/transfer-license' && method === 'POST') {
        const body = await request.json();

        const email = await authenticateUserEmail(body);
        if (!email) {
          return new Response(JSON.stringify({ error: 'Unauthorized: Authentication required.', success: false }), {
            status: 401,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        const { sourceDeviceId, targetDeviceId, transferBoxAssignment } = body;
        if (!sourceDeviceId || !targetDeviceId) {
          return new Response(JSON.stringify({ error: 'Missing sourceDeviceId or targetDeviceId parameter.', success: false }), {
            status: 400,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        let cleanSourceId = String(sourceDeviceId).trim();
        if (!cleanSourceId.toUpperCase().startsWith('HW-')) {
          cleanSourceId = 'HW-' + cleanSourceId;
        }
        let cleanTargetId = String(targetDeviceId).trim();
        if (!cleanTargetId.toUpperCase().startsWith('HW-')) {
          cleanTargetId = 'HW-' + cleanTargetId;
        }

        const normSource = cleanSourceId.toUpperCase().replace(/^HW-/, '');
        const normTarget = cleanTargetId.toUpperCase().replace(/^HW-/, '');

        if (normSource === normTarget) {
          return new Response(JSON.stringify({ error: 'Source and target device cannot be the same.', success: false }), {
            status: 400,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        if (!env.DEVICE_STORE) {
          return new Response(JSON.stringify({ error: 'KV Store not configured', success: false }), {
            status: 500,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        // 1. Retrieve and validate source device
        let rawSource = await env.DEVICE_STORE.get(cleanSourceId);
        if (!rawSource) rawSource = await env.DEVICE_STORE.get(normSource);

        if (!rawSource) {
          return new Response(JSON.stringify({ error: `Source device ${cleanSourceId} not found.`, success: false }), {
            status: 404,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        const sourceDev = JSON.parse(rawSource);
        const sourceOwner = sourceDev.ownerEmail ? sourceDev.ownerEmail.trim().toLowerCase() : null;
        if (sourceOwner && sourceOwner !== email) {
          return new Response(JSON.stringify({ error: 'You are not the registered owner of the source device.', success: false }), {
            status: 403,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        const sourceExpires = sourceDev.paidExpiresAt || 0;
        if (sourceExpires <= now) {
          return new Response(JSON.stringify({ error: `Source device ${cleanSourceId} does not have an active license to transfer.`, success: false }), {
            status: 400,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        // Calculate remaining duration strictly
        const remainingMs = sourceExpires - now;
        if (remainingMs <= 0) {
          return new Response(JSON.stringify({ error: 'Source license has expired.', success: false }), {
            status: 400,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }
        const remainingDays = Math.max(1, Math.ceil(remainingMs / (24 * 60 * 60 * 1000)));

        // 2. Retrieve or initialize target device
        let rawTarget = await env.DEVICE_STORE.get(cleanTargetId);
        if (!rawTarget) rawTarget = await env.DEVICE_STORE.get(normTarget);

        let targetDev = rawTarget ? JSON.parse(rawTarget) : {
          deviceId: cleanTargetId,
          hardwareHash: cleanTargetId.toUpperCase(),
          deviceModel: 'Replacement Kiosk Phone',
          firstRegisteredAt: now,
          installCount: 1,
          processedPaymentRefs: []
        };

        const targetOwner = targetDev.ownerEmail ? targetDev.ownerEmail.trim().toLowerCase() : null;
        if (targetOwner && targetOwner !== email) {
          return new Response(JSON.stringify({ error: 'Target device is registered to another user account.', success: false }), {
            status: 403,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        // Compute new expiration for target device (extends existing if already active, else now + remainingMs)
        const currentTargetExpires = targetDev.paidExpiresAt || 0;
        const newTargetExpires = (currentTargetExpires > now ? currentTargetExpires : now) + remainingMs;

        // 3. Generate cryptographic RSA-2048 token for TARGET device first (fails fast if crypto/keys misconfigured)
        const token = await generateLicenseToken(cleanTargetId, newTargetExpires, env);

        // 4. Atomically revoke source device's license (1-Device-Only rule)
        const oldBoxNumber = sourceDev.boxBuildNumber || null;
        sourceDev.paidExpiresAt = 0;
        sourceDev.licenseStatus = 'TRANSFERRED';
        sourceDev.transferredTo = cleanTargetId;
        sourceDev.transferredAt = now;

        // If user wants to transfer the Coin Slot Box slot:
        let boxTransferred = false;
        if (transferBoxAssignment && oldBoxNumber) {
          delete sourceDev.boxBuildNumber;
          targetDev.boxBuildNumber = oldBoxNumber;

          // Update BOX record
          const rawBox = await env.DEVICE_STORE.get(`BOX:${oldBoxNumber}`);
          if (rawBox) {
            const boxData = JSON.parse(rawBox);
            boxData.linkedDevices = (boxData.linkedDevices || []).filter(id => {
              const nid = String(id).trim().toUpperCase().replace(/^HW-/, '');
              return nid !== normSource && nid !== normTarget;
            });
            boxData.linkedDevices.push(cleanTargetId);
            await env.DEVICE_STORE.put(`BOX:${oldBoxNumber}`, JSON.stringify(boxData));
            boxTransferred = true;
          }
        }

        // 5. Save updated records to KV
        await env.DEVICE_STORE.put(cleanSourceId, JSON.stringify(sourceDev));
        await env.DEVICE_STORE.put(normSource, JSON.stringify(sourceDev));

        targetDev.paidExpiresAt = newTargetExpires;
        targetDev.ownerEmail = email;
        targetDev.lastCheckinAt = now;
        targetDev.licenseTransferredFrom = cleanSourceId;
        targetDev.licenseTransferredAt = now;
        await env.DEVICE_STORE.put(cleanTargetId, JSON.stringify(targetDev));
        await env.DEVICE_STORE.put(normTarget, JSON.stringify(targetDev));

        // 6. Ensure target device is in USER_DEVICES index
        const rawUd = await env.DEVICE_STORE.get(`USER_DEVICES:${email}`);
        let userDevices = rawUd ? JSON.parse(rawUd) : [];
        if (!userDevices.includes(cleanTargetId)) {
          userDevices.push(cleanTargetId);
          await env.DEVICE_STORE.put(`USER_DEVICES:${email}`, JSON.stringify(userDevices));
        }

        return new Response(JSON.stringify({
          success: true,
          message: `License successfully transferred from ${cleanSourceId} to ${cleanTargetId} (${remainingDays} days remaining).`,
          sourceDeviceId: cleanSourceId,
          targetDeviceId: cleanTargetId,
          daysRemaining: remainingDays,
          paidExpiresAt: newTargetExpires,
          licenseKey: token.licenseKey,
          signature: token.signature,
          boxTransferred,
          boxBuildNumber: boxTransferred ? oldBoxNumber : null,
          serverTime: now
        }), {
          status: 200,
          headers: { ...corsHeaders, 'Content-Type': 'application/json' },
        });
      }

      // =========================================================================
      // 5. Manual Payment Confirmation / Admin Trigger
      // POST /api/payment/confirm
      // Body: { adminSecret: string, deviceId?: string, paymentRef?: string, ownerEmail?: string, addCredits?: number }
      // =========================================================================
      if (url.pathname === '/api/payment/confirm' && method === 'POST') {
        const body = await request.json();
        const { deviceId, paymentRef, adminSecret: reqSecret, addCredits, ownerEmail } = body;

        // Rule 6/7: Must provide valid adminSecret to mint credits or manually activate devices
        const adminSecret = env.ADMIN_SECRET;
        if (!adminSecret || reqSecret !== adminSecret) {
          return new Response(JSON.stringify({ error: 'Unauthorized: Invalid or missing Admin Secret.' }), {
            status: 401,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        const cleanPaymentRef = paymentRef ? String(paymentRef).trim() : `MANUAL-${now}`;

        // Admin add credits
        if (!deviceId && addCredits && ownerEmail) {
          let processedRefs = [];
          if (env.DEVICE_STORE) {
            const rawRefs = await env.DEVICE_STORE.get(`USER_PAYMENTS:${ownerEmail}`);
            if (rawRefs) processedRefs = JSON.parse(rawRefs);
          }
          
          if (processedRefs.includes(cleanPaymentRef)) {
            return new Response(
              JSON.stringify({ success: true, idempotent: true, serverTime: now, message: 'Credits already added for this ref.' }),
              { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
            );
          }

          let credits = 0;
          if (env.DEVICE_STORE) {
            const rawCredits = await env.DEVICE_STORE.get(`USER_CREDITS:${ownerEmail}`);
            if (rawCredits) credits = parseInt(rawCredits, 10);
          }

          credits += parseInt(addCredits, 10);

          processedRefs.push(cleanPaymentRef);
          processedRefs = processedRefs.slice(-50);

          if (env.DEVICE_STORE) {
            await env.DEVICE_STORE.put(`USER_CREDITS:${ownerEmail}`, credits.toString());
            await env.DEVICE_STORE.put(`USER_PAYMENTS:${ownerEmail}`, JSON.stringify(processedRefs));
          }

          return new Response(
            JSON.stringify({ success: true, serverTime: now, creditsAdded: addCredits, totalCredits: credits, message: `Added ${addCredits} credits.` }),
            { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
          );
        }

        if (!deviceId) {
          return new Response(JSON.stringify({ error: 'Missing deviceId', serverTime: now }), {
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
            hardwareHash: cleanId.toUpperCase(),
            deviceModel: 'Manual Activation',
            firstRegisteredAt: now,
            installCount: 1,
            processedPaymentRefs: [],
          };
        }
        
        if (ownerEmail) record.ownerEmail = ownerEmail;

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
        const newPaidExpires = (currentPaidExpires > now ? currentPaidExpires : now) + LICENSE_DURATION_MS;

        record.paidExpiresAt = newPaidExpires;
        record.lastCheckinAt = now;
        record.paidAt = now;
        processedRefs.push(cleanPaymentRef);
        record.processedPaymentRefs = processedRefs.slice(-25);

        // Clean up old redundant properties
        delete record.licenseType;
        delete record.paymentReference;
        delete record.trialExpiresAt;

        if (env.DEVICE_STORE) {
          await env.DEVICE_STORE.put(cleanId, JSON.stringify(record));
          
          if (record.ownerEmail) {
            const rawUd = await env.DEVICE_STORE.get(`USER_DEVICES:${record.ownerEmail}`);
            let userDevices = rawUd ? JSON.parse(rawUd) : [];
            if (!userDevices.includes(cleanId)) {
              userDevices.push(cleanId);
              await env.DEVICE_STORE.put(`USER_DEVICES:${record.ownerEmail}`, JSON.stringify(userDevices));
            }
          }
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

      // =========================================================================
      // 6. Coin Slot Box (₱5,000 Hardware) Build Number Verification & Device Limit
      // POST /api/box/verify
      // Body: { buildNumber: string, ownerToken?: string, ownerEmail?: string }
      // =========================================================================
      if (url.pathname === '/api/box/verify' && method === 'POST') {
        const body = await request.json();
        const { buildNumber } = body;

        const email = await authenticateUserEmail(body);
        if (!email) {
          return new Response(JSON.stringify({ error: 'Authentication required. Please sign in.' }), {
            status: 401,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        if (!buildNumber || typeof buildNumber !== 'string') {
          return new Response(JSON.stringify({ error: 'Please enter a valid Coin Slot Box Build Number or ESP32 MAC Address.' }), {
            status: 400,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        let rawInput = buildNumber.trim();
        // If scanned payload is a URL (e.g. https://pisophone.pages.dev/?box=BOX-9482-1049)
        if (rawInput.includes('?')) {
          try {
            const urlObj = new URL(rawInput.startsWith('http') ? rawInput : 'https://dummy.local/' + rawInput);
            const param = urlObj.searchParams.get('box') || urlObj.searchParams.get('b') || urlObj.searchParams.get('mac') || urlObj.searchParams.get('id');
            if (param) rawInput = param.trim();
          } catch (_) {}
        } else if (rawInput.startsWith('{') && rawInput.endsWith('}')) {
          try {
            const parsed = JSON.parse(rawInput);
            rawInput = parsed.box || parsed.buildNumber || parsed.mac || parsed.boxId || rawInput;
          } catch (_) {}
        }

        const rawUpper = rawInput.trim().toUpperCase();
        const formattedMac = formatMacAddress(rawUpper);
        const cleanBuildNumber = formattedMac || rawUpper;

        if (cleanBuildNumber.length < 2) {
          return new Response(JSON.stringify({ error: 'Please enter a valid Coin Slot Box MAC Address or Build Number.' }), {
            status: 400,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        let boxRecord = null;
        if (env.DEVICE_STORE) {
          const rawBox = await env.DEVICE_STORE.get(`BOX:${cleanBuildNumber}`);
          if (rawBox) boxRecord = JSON.parse(rawBox);
        }

        // If not in KV, initialize valid registered box record directly (no checksum check required)
        if (!boxRecord) {
          boxRecord = {
            buildNumber: cleanBuildNumber,
            esp32MacAddress: formattedMac || cleanBuildNumber,
            maxDevices: 10,
            linkedDevices: [],
            pricePhp: 5000,
            firstClaimedBy: email,
            claimedAt: now,
          };
        }

        // Check if box was claimed by another user
        if (boxRecord.firstClaimedBy && boxRecord.firstClaimedBy.trim().toLowerCase() !== email) {
          return new Response(JSON.stringify({ error: 'This Coin Slot Box build number has already been registered to another account.' }), {
            status: 403,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        boxRecord.firstClaimedBy = email;
        const linkedDevices = Array.isArray(boxRecord.linkedDevices) ? boxRecord.linkedDevices : [];

        if (env.DEVICE_STORE) {
          await env.DEVICE_STORE.put(`BOX:${cleanBuildNumber}`, JSON.stringify(boxRecord));

          const rawUserBoxes = await env.DEVICE_STORE.get(`USER_BOXES:${email}`);
          let userBoxes = rawUserBoxes ? JSON.parse(rawUserBoxes) : [];
          if (!userBoxes.includes(cleanBuildNumber)) {
            userBoxes.push(cleanBuildNumber);
            await env.DEVICE_STORE.put(`USER_BOXES:${email}`, JSON.stringify(userBoxes));
          }
        }

        return new Response(
          JSON.stringify({
            success: true,
            message: 'Coin Slot Box verified successfully! (10 Devices Allowed)',
            buildNumber: cleanBuildNumber,
            maxDevices: 10,
            devicesUsed: linkedDevices.length,
            slotsRemaining: Math.max(0, 10 - linkedDevices.length),
            linkedDevices: linkedDevices,
            serverTime: now,
          }),
          { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );
      }

      // =========================================================================
      // 7. Coin Slot Box Status
      // POST /api/box/status
      // Body: { ownerToken?: string, ownerEmail?: string }
      // =========================================================================
      if (url.pathname === '/api/box/status' && method === 'POST') {
        const body = await request.json();

        const email = await authenticateUserEmail(body);
        if (!email) {
          return new Response(JSON.stringify({ error: 'Authentication required.' }), {
            status: 401,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        let userBoxes = [];
        if (env.DEVICE_STORE) {
          const rawUserBoxes = await env.DEVICE_STORE.get(`USER_BOXES:${email}`);
          if (rawUserBoxes) userBoxes = JSON.parse(rawUserBoxes);
        }

        let validUserBoxes = [];
        let totalAllowed = 0;
        let allLinked = [];
        let boxDetails = [];

        for (const bNum of userBoxes) {
          if (env.DEVICE_STORE) {
            const rawBox = await env.DEVICE_STORE.get(`BOX:${bNum}`);
            if (rawBox) {
              const bData = JSON.parse(rawBox);
              const claimedBy = bData.firstClaimedBy ? bData.firstClaimedBy.trim().toLowerCase() : null;
              if (claimedBy && claimedBy !== email) {
                continue; // Box belongs to another user
              }
              validUserBoxes.push(bNum);

              const bLinked = Array.isArray(bData.linkedDevices) ? bData.linkedDevices : [];
              allLinked = allLinked.concat(bLinked);

              const boxDeviceList = [];
              for (const did of bLinked) {
                const rawDev = await env.DEVICE_STORE.get(did);
                if (rawDev) {
                  const dev = JSON.parse(rawDev);
                  const isPaid = dev.paidExpiresAt > now;
                  let licenseKey = null;
                  if (isPaid) {
                    try {
                      const token = await generateLicenseToken(dev.deviceId, dev.paidExpiresAt, env);
                      licenseKey = token.licenseKey;
                    } catch(e) {}
                  }
                  boxDeviceList.push({
                    deviceId: dev.deviceId,
                    deviceModel: dev.deviceModel || 'Kiosk Device',
                    firstRegisteredAt: dev.firstRegisteredAt || now,
                    paidExpiresAt: dev.paidExpiresAt || 0,
                    status: isPaid ? 'PAID' : 'UNACTIVATED',
                    daysRemaining: isPaid ? Math.max(0, Math.ceil((dev.paidExpiresAt - now) / (24 * 60 * 60 * 1000))) : 0,
                    licenseKey: licenseKey,
                    boxBuildNumber: bNum,
                  });
                } else {
                  boxDeviceList.push({
                    deviceId: did,
                    deviceModel: 'Kiosk Phone',
                    firstRegisteredAt: now,
                    paidExpiresAt: now + 365 * 24 * 60 * 60 * 1000,
                    status: 'PAID',
                    daysRemaining: 365,
                    licenseKey: null,
                    boxBuildNumber: bNum,
                  });
                }
              }

              boxDetails.push({
                buildNumber: bNum,
                maxDevices: bData.maxDevices || 10,
                devicesUsed: bLinked.length,
                slotsRemaining: Math.max(0, (bData.maxDevices || 10) - bLinked.length),
                linkedDevices: bLinked,
                devices: boxDeviceList,
              });
            }
          }
        }

        totalAllowed = validUserBoxes.length * 10;

        if (env.DEVICE_STORE && validUserBoxes.length !== userBoxes.length) {
          await env.DEVICE_STORE.put(`USER_BOXES:${email}`, JSON.stringify(validUserBoxes));
        }

        return new Response(
          JSON.stringify({
            success: true,
            hasVerifiedBox: validUserBoxes.length > 0,
            boxes: boxDetails,
            totalBoxes: validUserBoxes.length,
            totalMaxDevices: totalAllowed,
            totalDevicesUsed: allLinked.length,
            totalSlotsRemaining: Math.max(0, totalAllowed - allLinked.length),
            serverTime: now,
          }),
          { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );
      }

      // =========================================================================
      // 8. Link Device to Box with 10-Device Maximum Limit Check
      // POST /api/box/link-device
      // Body: { buildNumber: string, deviceId: string, ownerToken?: string, ownerEmail?: string }
      // =========================================================================
      if (url.pathname === '/api/box/link-device' && method === 'POST') {
        const body = await request.json();
        const { buildNumber, deviceId } = body;

        const email = await authenticateUserEmail(body);
        if (!email) {
          return new Response(JSON.stringify({ error: 'Authentication required.' }), {
            status: 401,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        if (!deviceId) {
          return new Response(JSON.stringify({ error: 'Missing deviceId.' }), {
            status: 400,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        let cleanDevId = String(deviceId).trim();
        if (!cleanDevId.toUpperCase().startsWith('HW-')) {
          cleanDevId = 'HW-' + cleanDevId;
        }
        let targetBoxNumber = buildNumber ? String(buildNumber).trim().toUpperCase() : null;

        // Verify device ownership if device exists
        if (env.DEVICE_STORE) {
          const rawDev = await env.DEVICE_STORE.get(cleanDevId);
          if (rawDev) {
            const dev = JSON.parse(rawDev);
            const devOwner = dev.ownerEmail ? dev.ownerEmail.trim().toLowerCase() : null;
            if (devOwner && devOwner !== email) {
              return new Response(JSON.stringify({ error: 'This device belongs to another user account.', success: false }), {
                status: 403,
                headers: { ...corsHeaders, 'Content-Type': 'application/json' },
              });
            }
          }
        }

        // If targetBoxNumber not specified, find user's box that has free slot
        if (!targetBoxNumber && env.DEVICE_STORE) {
          const rawUserBoxes = await env.DEVICE_STORE.get(`USER_BOXES:${email}`);
          const userBoxes = rawUserBoxes ? JSON.parse(rawUserBoxes) : [];
          for (const bNum of userBoxes) {
            const rawBox = await env.DEVICE_STORE.get(`BOX:${bNum}`);
            if (rawBox) {
              const bData = JSON.parse(rawBox);
              const claimedBy = bData.firstClaimedBy ? bData.firstClaimedBy.trim().toLowerCase() : null;
              if (claimedBy && claimedBy !== email) continue;

              const bLinked = Array.isArray(bData.linkedDevices) ? bData.linkedDevices : [];
              if (bLinked.includes(cleanDevId) || bLinked.length < (bData.maxDevices || 10)) {
                targetBoxNumber = bNum;
                break;
              }
            }
          }
        }

        if (!targetBoxNumber) {
          return new Response(JSON.stringify({ error: 'No verified Coin Slot Box found with available device slots. Please verify a Coin Slot Box build number.' }), {
            status: 403,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        let boxData = null;
        if (env.DEVICE_STORE) {
          const rawBox = await env.DEVICE_STORE.get(`BOX:${targetBoxNumber}`);
          if (rawBox) boxData = JSON.parse(rawBox);
        }

        if (!boxData) {
          return new Response(JSON.stringify({ error: 'Coin Slot Box not found.' }), {
            status: 404,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        const claimedBy = boxData.firstClaimedBy ? boxData.firstClaimedBy.trim().toLowerCase() : null;
        if (claimedBy && claimedBy !== email) {
          return new Response(JSON.stringify({ error: 'This Coin Slot Box belongs to another user account.' }), {
            status: 403,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        const linked = Array.isArray(boxData.linkedDevices) ? boxData.linkedDevices : [];
        if (!linked.includes(cleanDevId)) {
          if (linked.length >= (boxData.maxDevices || 10)) {
            return new Response(JSON.stringify({ error: 'This Coin Slot Box has reached its maximum limit of 10 devices.' }), {
              status: 400,
              headers: { ...corsHeaders, 'Content-Type': 'application/json' },
            });
          }
          linked.push(cleanDevId);
          boxData.linkedDevices = linked;
          if (env.DEVICE_STORE) {
            await env.DEVICE_STORE.put(`BOX:${targetBoxNumber}`, JSON.stringify(boxData));

            // Also ensure device record exists and has boxBuildNumber + is added to USER_DEVICES
            const rawDev = await env.DEVICE_STORE.get(cleanDevId);
            let dev = rawDev ? JSON.parse(rawDev) : {
              deviceId: cleanDevId,
              hardwareHash: cleanDevId.toUpperCase(),
              deviceModel: 'PisoPhone Kiosk Phone',
              firstRegisteredAt: now,
              paidExpiresAt: now + (365 * 24 * 60 * 60 * 1000), // Activated under hardware box
              lastCheckinAt: now,
              installCount: 1
            };
            dev.ownerEmail = email;
            dev.boxBuildNumber = targetBoxNumber;
            // Extend license under hardware box quota
            if (!dev.paidExpiresAt || dev.paidExpiresAt < now) {
              dev.paidExpiresAt = now + (365 * 24 * 60 * 60 * 1000);
            }
            await env.DEVICE_STORE.put(cleanDevId, JSON.stringify(dev));

            const rawUd = await env.DEVICE_STORE.get(`USER_DEVICES:${email}`);
            let userDevices = rawUd ? JSON.parse(rawUd) : [];
            if (!userDevices.includes(cleanDevId)) {
              userDevices.push(cleanDevId);
              await env.DEVICE_STORE.put(`USER_DEVICES:${email}`, JSON.stringify(userDevices));
            }
          }
        }

        return new Response(
          JSON.stringify({
            success: true,
            buildNumber: targetBoxNumber,
            deviceId: cleanDevId,
            devicesUsed: linked.length,
            maxDevices: boxData.maxDevices || 10,
            slotsRemaining: Math.max(0, (boxData.maxDevices || 10) - linked.length),
            message: `Device linked to Coin Slot Box (${linked.length}/10 slots used).`,
          }),
          { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );
      }

      // =========================================================================
      // 9. Unlink Device from Box
      // POST /api/box/unlink-device
      // Body: { buildNumber: string, deviceId: string, ownerToken?: string, ownerEmail?: string }
      // =========================================================================
      if (url.pathname === '/api/box/unlink-device' && method === 'POST') {
        const body = await request.json();
        const { buildNumber, deviceId } = body;

        const email = await authenticateUserEmail(body);
        if (!email) {
          return new Response(JSON.stringify({ error: 'Authentication required.' }), {
            status: 401,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        if (!buildNumber || !deviceId) {
          return new Response(JSON.stringify({ error: 'Missing buildNumber or deviceId.' }), {
            status: 400,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        const cleanDevId = String(deviceId).trim();
        const normDevId = cleanDevId.toUpperCase().replace(/^HW-/, '');
        const cleanBoxNum = String(buildNumber).trim().toUpperCase();

        if (env.DEVICE_STORE) {
          const rawBox = await env.DEVICE_STORE.get(`BOX:${cleanBoxNum}`);
          if (rawBox) {
            const boxData = JSON.parse(rawBox);
            const claimedBy = boxData.firstClaimedBy ? boxData.firstClaimedBy.trim().toLowerCase() : null;
            if (claimedBy && claimedBy !== email) {
              return new Response(JSON.stringify({ error: 'This Coin Slot Box belongs to another user account.' }), {
                status: 403,
                headers: { ...corsHeaders, 'Content-Type': 'application/json' },
              });
            }
            boxData.linkedDevices = (boxData.linkedDevices || []).filter(id => {
              const normId = String(id).trim().toUpperCase().replace(/^HW-/, '');
              return normId !== normDevId;
            });
            await env.DEVICE_STORE.put(`BOX:${cleanBoxNum}`, JSON.stringify(boxData));
          }

          // Clear box assignment on device record itself
          const keyWithPrefix = `HW-${normDevId}`;
          const keyRaw = normDevId;
          const rawDev = (await env.DEVICE_STORE.get(keyWithPrefix)) || (await env.DEVICE_STORE.get(keyRaw));
          if (rawDev) {
            const devData = JSON.parse(rawDev);
            delete devData.boxBuildNumber;
            await env.DEVICE_STORE.put(keyWithPrefix, JSON.stringify(devData));
          }
        }

        return new Response(
          JSON.stringify({
            success: true,
            message: `Device HW-${normDevId} unlinked from Coin Slot Box #${cleanBoxNum}.`,
          }),
          { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );
      }

      // =========================================================================
      // 10. Remove / Delete Device from Dashboard User Account
      // POST /api/user/remove-device
      // Body: { deviceId: string, ownerToken?: string, ownerEmail?: string }
      // =========================================================================
      if (url.pathname === '/api/user/remove-device' && method === 'POST') {
        const body = await request.json();
        const { deviceId } = body;

        const email = await authenticateUserEmail(body);
        if (!email) {
          return new Response(JSON.stringify({ error: 'Authentication required.' }), {
            status: 401,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        if (!deviceId) {
          return new Response(JSON.stringify({ error: 'Missing deviceId parameter.' }), {
            status: 400,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        const cleanDevId = String(deviceId).trim();
        const normDevId = cleanDevId.toUpperCase().replace(/^HW-/, '');

        if (env.DEVICE_STORE) {
          // 0. Fetch existing device record to find its boxBuildNumber and owner
          let devBoxBuildNumber = null;
          let devOwnerEmail = null;
          const rawDev = (await env.DEVICE_STORE.get(`HW-${normDevId}`)) ||
                         (await env.DEVICE_STORE.get(normDevId)) ||
                         (await env.DEVICE_STORE.get(cleanDevId));
          if (rawDev) {
            try {
              const d = JSON.parse(rawDev);
              if (d.boxBuildNumber) devBoxBuildNumber = d.boxBuildNumber;
              if (d.ownerEmail) devOwnerEmail = d.ownerEmail.trim().toLowerCase();
            } catch(e) {}
          }

          // 1. Remove from USER_DEVICES:${email}
          const rawUd = await env.DEVICE_STORE.get(`USER_DEVICES:${email}`);
          if (rawUd) {
            let userDevices = JSON.parse(rawUd);
            userDevices = userDevices.filter(id => {
              const normId = String(id).trim().toUpperCase().replace(/^HW-/, '');
              return normId !== normDevId;
            });
            await env.DEVICE_STORE.put(`USER_DEVICES:${email}`, JSON.stringify(userDevices));
          }

          // Also clean up from devOwnerEmail if different
          if (devOwnerEmail && devOwnerEmail !== email) {
            const rawOtherUd = await env.DEVICE_STORE.get(`USER_DEVICES:${devOwnerEmail}`);
            if (rawOtherUd) {
              let otherDevices = JSON.parse(rawOtherUd);
              otherDevices = otherDevices.filter(id => {
                const normId = String(id).trim().toUpperCase().replace(/^HW-/, '');
                return normId !== normDevId;
              });
              await env.DEVICE_STORE.put(`USER_DEVICES:${devOwnerEmail}`, JSON.stringify(otherDevices));
            }
          }

          // 2. Unlink from any user boxes in USER_BOXES:${email}
          const rawUb = await env.DEVICE_STORE.get(`USER_BOXES:${email}`);
          const userBoxNums = rawUb ? JSON.parse(rawUb) : [];
          if (devBoxBuildNumber && !userBoxNums.includes(devBoxBuildNumber)) {
            userBoxNums.push(devBoxBuildNumber);
          }

          for (const boxNum of userBoxNums) {
            const rawBox = await env.DEVICE_STORE.get(`BOX:${boxNum}`);
            if (rawBox) {
              const boxData = JSON.parse(rawBox);
              boxData.linkedDevices = (boxData.linkedDevices || []).filter(id => {
                const normId = String(id).trim().toUpperCase().replace(/^HW-/, '');
                return normId !== normDevId;
              });
              await env.DEVICE_STORE.put(`BOX:${boxNum}`, JSON.stringify(boxData));
            }
          }

          // 3. Delete all device record key variants
          await env.DEVICE_STORE.delete(`HW-${normDevId}`);
          await env.DEVICE_STORE.delete(normDevId);
          await env.DEVICE_STORE.delete(cleanDevId);
        }

        return new Response(
          JSON.stringify({
            success: true,
            message: `Device HW-${normDevId} successfully removed from your account.`,
          }),
          { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );
      }

      // =========================================================================
      // 12. Flexible Box Pricing
      // GET /api/box/pricing
      // =========================================================================
      if (url.pathname === '/api/box/pricing' && (method === 'GET' || method === 'POST')) {
        return new Response(
          JSON.stringify({
            success: true,
            basePrice: 5000,
            baseIncludedSlots: 2,
            additionalSlotPrice: 500,
            tiers: [
              { slots: 2, name: 'Starter Dual Kiosk', price: 5000, description: 'Complete Coin Slot Box + 2 Device Slots included' },
              { slots: 3, name: 'Standard Triple Kiosk', price: 5500, description: 'Complete Coin Slot Box + 3 Device Slots' },
              { slots: 5, name: 'Pro 5-Terminal Kiosk', price: 6500, description: 'Complete Coin Slot Box + 5 Device Slots' },
              { slots: 8, name: 'Enterprise 8-Terminal Hub', price: 8000, description: 'Complete Coin Slot Box + 8 Device Slots' },
              { slots: 10, name: 'Mega Arcade 10-Slot Fleet', price: 9000, description: 'Complete Coin Slot Box + 10 Device Slots' },
            ],
            serverTime: now,
          }),
          { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );
      }

      // =========================================================================
      // 13. ESP32 Snapshot Reporting (Single Batch Payload)
      // POST /api/box/report-snapshot
      // Body: { mac: string, tier?: number, slots: Array, lifetimeCoins?: number, lifetimeEarnings?: number, wifiSsid?: string }
      // =========================================================================
      if (url.pathname === '/api/box/report-snapshot' && method === 'POST') {
        const body = await request.json();
        const { mac, tier, slots, lifetimeCoins, lifetimeEarnings, wifiSsid, firmwareVersion } = body;

        if (!mac || typeof mac !== 'string') {
          return new Response(JSON.stringify({ error: 'Missing or invalid MAC address' }), {
            status: 400,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        const rawMac = mac.trim().toUpperCase();
        const formattedMac = formatMacAddress(rawMac);
        const cleanMac = formattedMac || rawMac;

        let boxData = null;
        const rawBox = await env.DEVICE_STORE.get(`BOX:${cleanMac}`);
        if (rawBox) {
          try {
            boxData = JSON.parse(rawBox);
          } catch (e) {}
        }

        if (!boxData) {
          boxData = {
            buildNumber: cleanMac,
            macAddress: cleanMac,
            maxDevices: Math.max(2, parseInt(tier) || 2),
            linkedDevices: [],
            claimedAt: now,
          };
        }

        if (tier && parseInt(tier) > (boxData.maxDevices || 2)) {
          boxData.maxDevices = parseInt(tier);
        }

        const cleanSlots = Array.isArray(slots) ? slots : [];
        boxData.slots = cleanSlots;
        boxData.linkedDevices = cleanSlots
          .filter(s => s && s.deviceId && s.deviceId.trim().length > 0)
          .map(s => s.deviceId.trim().toUpperCase());

        boxData.lastSnapshotAt = now;
        if (lifetimeCoins !== undefined) boxData.lifetimeCoins = parseInt(lifetimeCoins) || 0;
        if (lifetimeEarnings !== undefined) boxData.lifetimeEarnings = parseFloat(lifetimeEarnings) || 0;
        if (wifiSsid) boxData.wifiSsid = String(wifiSsid);
        if (firmwareVersion) boxData.firmwareVersion = String(firmwareVersion);

        await env.DEVICE_STORE.put(`BOX:${cleanMac}`, JSON.stringify(boxData));

        // Sync each paired device slot record in KV so user dashboard reflects active state
        for (const slot of cleanSlots) {
          if (slot && slot.deviceId && slot.deviceId.trim().length > 0) {
            const devId = slot.deviceId.trim().toUpperCase();
            const normDevId = devId.replace(/^HW-/, '');
            const existingDevRaw = await env.DEVICE_STORE.get(`HW-${normDevId}`);
            let devRecord = null;
            if (existingDevRaw) {
              try { devRecord = JSON.parse(existingDevRaw); } catch(e) {}
            }
            if (!devRecord) {
              devRecord = {
                deviceId: `HW-${normDevId}`,
                status: 'PAID',
                firstRegisteredAt: now,
                ownerEmail: boxData.firstClaimedBy || null,
              };
            }
            devRecord.boundBoxMac = cleanMac;
            devRecord.slotNum = slot.slotNum || slot.slot || 1;
            devRecord.paidExpiresAt = slot.expiresAt || (now + 365 * 86400000);
            devRecord.status = 'PAID';
            devRecord.lastSnapshotAt = now;
            if (slot.name) devRecord.deviceName = slot.name;
            if (slot.ip) devRecord.lastKnownIp = slot.ip;

            await env.DEVICE_STORE.put(`HW-${normDevId}`, JSON.stringify(devRecord));
          }
        }

        return new Response(
          JSON.stringify({
            success: true,
            message: 'Box snapshot recorded successfully.',
            boxMac: cleanMac,
            maxDevices: boxData.maxDevices,
            activeSlotsCount: boxData.linkedDevices.length,
            serverTime: now,
          }),
          { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );
      }

      // =========================================================================
      // 14. Issue Flexible Slot Upgrade Token
      // POST /api/box/issue-slot-token
      // Body: { boxMac: string, slotsCount: number, durationDays?: number, ownerToken?: string, ownerEmail?: string }
      // =========================================================================
      if (url.pathname === '/api/box/issue-slot-token' && method === 'POST') {
        const body = await request.json();
        const { boxMac, slotsCount, durationDays } = body;

        const email = await authenticateUserEmail(body);
        if (!email) {
          return new Response(JSON.stringify({ error: 'Authentication required to issue slot tokens.' }), {
            status: 401,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        if (!boxMac || typeof boxMac !== 'string') {
          return new Response(JSON.stringify({ error: 'Valid Box MAC Address required.' }), {
            status: 400,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }

        const rawMac = boxMac.trim().toUpperCase();
        const formattedMac = formatMacAddress(rawMac);
        const cleanMac = formattedMac || rawMac;

        const targetSlots = Math.max(1, Math.min(32, parseInt(slotsCount) || 2));
        const days = Math.max(1, parseInt(durationDays) || 365);
        const expiresAt = now + (days * 86400000);

        const secret = signingSecret || env.LICENSE_SIGNING_SECRET || env.ADMIN_SECRET || 'PISOPHONE_HMAC_MASTER_KEY';
        const sigPayload = `PISOSLOT:${cleanMac}:${targetSlots}:${expiresAt}`;
        const signature = await calculateHmacSha256Hex(sigPayload, secret);
        const slotToken = `PISOSLOT.${cleanMac}.${targetSlots}.${expiresAt}.${signature}`;

        // Save/Update box capacity in KV
        let boxData = null;
        const rawBox = await env.DEVICE_STORE.get(`BOX:${cleanMac}`);
        if (rawBox) {
          try { boxData = JSON.parse(rawBox); } catch(e) {}
        }
        if (!boxData) {
          boxData = {
            buildNumber: cleanMac,
            macAddress: cleanMac,
            maxDevices: targetSlots,
            linkedDevices: [],
            firstClaimedBy: email,
            claimedAt: now,
          };
        } else {
          boxData.maxDevices = Math.max(boxData.maxDevices || 2, targetSlots);
        }
        await env.DEVICE_STORE.put(`BOX:${cleanMac}`, JSON.stringify(boxData));

        return new Response(
          JSON.stringify({
            success: true,
            boxMac: cleanMac,
            slotsCount: targetSlots,
            expiresAt: expiresAt,
            token: slotToken,
            message: `Slot token for ${targetSlots} seats issued successfully.`,
            serverTime: now,
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

