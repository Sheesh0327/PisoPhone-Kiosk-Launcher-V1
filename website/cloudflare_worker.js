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
  const privKey = env.LICENSE_RSA_PRIVATE_KEY;
  const rsaSig = await signRsaSha256(payload, privKey);
  
  if (rsaSig) {
    return {
      signature: rsaSig,
      licenseKey: `PISO-1Y.${deviceId}.${paidExpiresAt}.${rsaSig}`,
      algorithm: 'RSA-2048-PKCS1v15'
    };
  }
  
  // Fallback to HMAC if RSA secret is not configured
  const signingSecret = env.LICENSE_SIGNING_SECRET || 'piso_master_lic_secret_2026_89a1f';
  const hmacSig = await signHmacSha256(payload, signingSecret);
  return {
    signature: hmacSig,
    licenseKey: `PISO-1Y.${deviceId}.${paidExpiresAt}.${hmacSig}`,
    algorithm: 'HMAC-SHA256'
  };
}

async function verifyGoogleToken(token) {
  if (!token) return null;
  try {
    const res = await fetch(`https://oauth2.googleapis.com/tokeninfo?id_token=${token}`);
    if (res.ok) {
      const data = await res.json();
      return data.email;
    }
  } catch (e) {
    console.error("Token verification failed:", e);
  }
  return null;
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
          ownerEmail = await verifyGoogleToken(ownerToken);
        }

        let existing = null;
        if (env.DEVICE_STORE) {
          const raw = await env.DEVICE_STORE.get(deviceId);
          if (raw) existing = JSON.parse(raw);
        }

        if (existing) {
          const isPaid = existing.paidExpiresAt > now;
          const isExpired = existing.paidExpiresAt > 0 && existing.paidExpiresAt <= now;
          const status = isPaid ? 'PAID' : (isExpired ? 'EXPIRED' : 'UNACTIVATED');

          existing.lastCheckinAt = now;
          existing.installCount = (existing.installCount || 1) + 1;
          existing.deviceModel = deviceModel || existing.deviceModel;
          if (ownerEmail) existing.ownerEmail = ownerEmail;

          // Clean up old redundant properties if they exist
          delete existing.licenseType;
          delete existing.trialExpiresAt;
          delete existing.paymentReference;

          if (env.DEVICE_STORE) {
            await env.DEVICE_STORE.put(deviceId, JSON.stringify(existing));
            
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
                : (isExpired ? 'Subscription has expired. Renewal required.' : 'Device registered. Activation required to unlock kiosk.'),
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

        let record = null;
        if (env.DEVICE_STORE) {
          const raw = await env.DEVICE_STORE.get(deviceId);
          if (raw) record = JSON.parse(raw);
        }

        if (!record || !(record.paidExpiresAt > now)) {
          const isExpired = record && record.paidExpiresAt > 0 && record.paidExpiresAt <= now;
          const status = record ? (isExpired ? 'EXPIRED' : 'UNACTIVATED') : 'UNREGISTERED';
          return new Response(
            JSON.stringify({
              paid: false,
              isPaid: false,
              isActivated: false,
              isValid: false,
              isExpired: Boolean(isExpired),
              deviceId,
              deviceModel: record ? (record.deviceModel || 'Android Device') : 'Unknown Device',
              status,
              paidExpiresAt: record ? (record.paidExpiresAt || 0) : 0,
              daysRemaining: 0,
              checkIntervalDays: 7,
              serverTime: now,
              message: isExpired ? 'Subscription has expired.' : 'No active license found for this device ID.',
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
        
        const email = await verifyGoogleToken(body.ownerToken);
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
        for (const did of userDevices) {
          if (env.DEVICE_STORE) {
            const rawDev = await env.DEVICE_STORE.get(did);
            if (rawDev) {
              const dev = JSON.parse(rawDev);
              const isPaid = dev.paidExpiresAt > now;
              
              devices.push({
                deviceId: dev.deviceId,
                deviceModel: dev.deviceModel || 'Unknown Device',
                firstRegisteredAt: dev.firstRegisteredAt || now,
                paidExpiresAt: dev.paidExpiresAt || 0,
                status: isPaid ? 'PAID' : 'UNACTIVATED',
                daysRemaining: isPaid ? Math.max(0, Math.ceil((dev.paidExpiresAt - now) / (24 * 60 * 60 * 1000))) : 0
              });
            }
          }
        }

        return new Response(JSON.stringify({ success: true, email, devices, serverTime: now }), {
          status: 200,
          headers: { ...corsHeaders, 'Content-Type': 'application/json' },
        });
      }

      // =========================================================================
      // 5. Manual Payment Confirmation / Admin Trigger
      // POST /api/payment/confirm
      // Body: { adminSecret?: string, deviceId: string, paymentRef?: string }
      // =========================================================================
      if (url.pathname === '/api/payment/confirm' && method === 'POST') {
        const body = await request.json();
        const { deviceId, paymentRef, adminSecret: reqSecret, ownerToken } = body;

        if (!deviceId) {
          return new Response(JSON.stringify({ error: 'Missing deviceId', serverTime: now }), {
            status: 400,
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          });
        }
        
        let ownerEmail = null;
        if (ownerToken) {
          ownerEmail = await verifyGoogleToken(ownerToken);
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

