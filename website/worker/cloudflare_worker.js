/**
 * Cloudflare Worker API for PisoPhone Licensing & Hardware Lock
 * Worker Name: pisophone-licensing-api
 * Bound KV Namespace: DEVICE_STORE -> pisophone-production-kv
 */

const DEFAULT_LICENSE_DURATION_MS = 365 * 24 * 60 * 60 * 1000; // 365 Days
const SAFE_BOX_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";

// ============================================================================
// HTTP & CORS HELPERS
// ============================================================================
const CORS_HEADERS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type, Authorization, X-Signature',
};

function jsonRes(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...CORS_HEADERS, 'Content-Type': 'application/json' },
  });
}

function errRes(message, status = 400, extra = {}) {
  return jsonRes({ error: message, serverTime: Date.now(), ...extra }, status);
}

// ============================================================================
// KV STORE HELPERS
// ============================================================================
async function kvGet(env, key) {
  if (!env?.DEVICE_STORE) return null;
  const raw = await env.DEVICE_STORE.get(key);
  if (!raw) return null;
  try { return JSON.parse(raw); } catch { return null; }
}

async function kvPut(env, key, value) {
  if (!env?.DEVICE_STORE) return;
  const val = typeof value === 'string' ? value : JSON.stringify(value);
  await env.DEVICE_STORE.put(key, val);
}

async function kvDelete(env, key) {
  if (!env?.DEVICE_STORE) return;
  await env.DEVICE_STORE.delete(key);
}

// ============================================================================
// DEVICE & ID NORMALIZATION UTILITIES
// ============================================================================
function normDevId(id) {
  if (!id || typeof id !== 'string') return '';
  let clean = id.trim();
  if (!clean.toUpperCase().startsWith('HW-')) clean = 'HW-' + clean;
  return clean;
}

function getRawMac(id) {
  return String(id || '').trim().toUpperCase().replace(/^HW-/, '');
}

function formatMacAddress(input) {
  if (!input || typeof input !== 'string') return null;
  const clean = input.replace(/[^a-fA-F0-9]/g, '').toUpperCase();
  return clean.length === 12 ? clean.match(/.{1,2}/g).join(':') : null;
}

// Index Management
async function getUserDeviceList(env, email) {
  return (await kvGet(env, `USER_DEVICES:${email}`)) || [];
}

async function addDeviceToUserIndex(env, email, deviceId) {
  if (!email || !deviceId) return;
  const cleanId = normDevId(deviceId);
  const list = await getUserDeviceList(env, email);
  if (!list.includes(cleanId)) {
    list.push(cleanId);
    await kvPut(env, `USER_DEVICES:${email}`, list);
  }
}

async function removeDeviceFromUserIndex(env, email, deviceId) {
  if (!email || !deviceId) return;
  const rawTarget = getRawMac(deviceId);
  const list = await getUserDeviceList(env, email);
  const filtered = list.filter(id => getRawMac(id) !== rawTarget);
  if (filtered.length !== list.length) {
    await kvPut(env, `USER_DEVICES:${email}`, filtered);
  }
}

// ============================================================================
// CRYPTOGRAPHY & AUTHENTICATION
// ============================================================================
async function signRsaSha256(message, privateKeyPem) {
  if (!privateKeyPem || typeof privateKeyPem !== 'string') return null;
  try {
    const b64 = privateKeyPem.replace(/-----[^-]+-----/g, '').replace(/\s+/g, '');
    const binary = atob(b64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    const key = await crypto.subtle.importKey(
      'pkcs8',
      bytes.buffer,
      { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
      false,
      ['sign']
    );
    const sigBuffer = await crypto.subtle.sign({ name: 'RSASSA-PKCS1-v1_5' }, key, new TextEncoder().encode(message));
    const uint8 = new Uint8Array(sigBuffer);
    let binarySig = '';
    for (let i = 0; i < uint8.length; i++) binarySig += String.fromCharCode(uint8[i]);
    return btoa(binarySig).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  } catch (e) {
    console.error('RSA sign error:', e);
    return null;
  }
}

async function generateLicenseToken(deviceId, paidExpiresAt, env) {
  const payload = `${deviceId}|${paidExpiresAt}`;
  const privKey = env.LICENSE_RSA_PRIVATE_KEY;
  if (!privKey) throw new Error("Missing Cloudflare Worker Secret: LICENSE_RSA_PRIVATE_KEY.");
  const rsaSig = await signRsaSha256(payload, privKey);
  if (!rsaSig) throw new Error("Failed to sign license token with LICENSE_RSA_PRIVATE_KEY.");
  return {
    signature: rsaSig,
    licenseKey: `PISO-1Y.${deviceId}.${paidExpiresAt}.${rsaSig}`,
    algorithm: 'RSA-2048-PKCS1v15'
  };
}

async function calculateHmacSha256Hex(message, secret) {
  const encoder = new TextEncoder();
  const key = await crypto.subtle.importKey('raw', encoder.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const signature = await crypto.subtle.sign('HMAC', key, encoder.encode(message));
  return Array.from(new Uint8Array(signature)).map(b => b.toString(16).padStart(2, '0')).join('');
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

  // Grace Period Fallback (up to 30 days) for Google JWTs
  try {
    const parts = String(token).split('.');
    if (parts.length === 3) {
      let base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
      while (base64.length % 4 !== 0) base64 += '=';
      const payload = JSON.parse(atob(base64));
      const isGoogleIssuer = payload.iss === 'https://accounts.google.com' || payload.iss === 'accounts.google.com';
      if (isGoogleIssuer && payload.email && payload.email_verified !== false) {
        const expMs = (payload.exp || 0) * 1000;
        if (Date.now() - expMs < 30 * 24 * 60 * 60 * 1000) {
          return payload.email.trim().toLowerCase();
        }
      }
    }
  } catch (_) {}
  return null;
}

async function authenticateUserEmail(body) {
  if (!body?.ownerToken) return null;
  const verifiedEmail = await verifyGoogleToken(body.ownerToken);
  if (!verifiedEmail) return null;
  if (body.ownerEmail && String(body.ownerEmail).trim().toLowerCase() !== verifiedEmail) {
    return null;
  }
  return verifiedEmail;
}

// In-Memory Rate Limiter (0 KV ops)
const ipRateLimitMap = new Map();
function checkRateLimit(request, limit = 60, windowSeconds = 60) {
  try {
    const clientIp = request.headers.get('cf-connecting-ip') || request.headers.get('x-real-ip') || 'unknown-client';
    const now = Date.now();
    const entry = ipRateLimitMap.get(clientIp);

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
    return entry.count <= limit;
  } catch (e) {
    return true; // Fail open
  }
}

// ============================================================================
// ROUTE HANDLERS
// ============================================================================

// 1. Device Registration
async function handleDeviceRegister(request, env, now) {
  const body = await request.json();
  const { deviceId, hardwareHash, deviceModel, ownerToken } = body;
  if (!deviceId || !hardwareHash) return errRes('Missing deviceId or hardwareHash');

  let ownerEmail = ownerToken ? await verifyGoogleToken(ownerToken) : null;
  const cleanId = normDevId(deviceId);
  let existing = await kvGet(env, cleanId) || await kvGet(env, deviceId);

  if (existing) {
    const isPaid = existing.paidExpiresAt > now;
    const isExpired = existing.paidExpiresAt > 0 && existing.paidExpiresAt <= now;
    const isTransferred = !isPaid && existing.licenseStatus === 'TRANSFERRED';
    const status = isPaid ? 'PAID' : (isTransferred ? 'TRANSFERRED' : (isExpired ? 'EXPIRED' : 'UNACTIVATED'));

    if (existing.ownerEmail) existing.ownerEmail = existing.ownerEmail.trim().toLowerCase();

    const lastCheckin = existing.lastCheckinAt || 0;
    const needsCheckin = (now - lastCheckin) > (12 * 60 * 60 * 1000);
    const needsModel = Boolean(deviceModel && existing.deviceModel !== deviceModel);
    const oldOwner = existing.ownerEmail;
    const needsOwner = Boolean(ownerEmail && oldOwner !== ownerEmail);

    if (needsCheckin || needsModel || needsOwner) {
      existing.lastCheckinAt = now;
      if (needsModel) existing.deviceModel = deviceModel;
      if (needsOwner) existing.ownerEmail = ownerEmail;

      delete existing.licenseType;
      delete existing.trialExpiresAt;
      delete existing.paymentReference;

      await kvPut(env, cleanId, existing);
      if (needsOwner && oldOwner) await removeDeviceFromUserIndex(env, oldOwner, cleanId);
      if (ownerEmail) await addDeviceToUserIndex(env, ownerEmail, cleanId);
    }

    let signature = '', licenseKey = '';
    if (isPaid) {
      const token = await generateLicenseToken(cleanId, existing.paidExpiresAt, env);
      signature = token.signature;
      licenseKey = token.licenseKey;
    }

    return jsonRes({
      status, isPaid, isActivated: isPaid, isValid: isPaid, isExpired,
      deviceId: existing.deviceId || cleanId,
      deviceModel: existing.deviceModel || deviceModel || 'Unknown Device',
      paidExpiresAt: existing.paidExpiresAt || 0,
      daysRemaining: isPaid ? Math.max(0, Math.ceil((existing.paidExpiresAt - now) / 86400000)) : 0,
      checkIntervalDays: 7, signature, licenseKey, serverTime: now,
      message: isPaid ? 'Active Commercial License' : (isTransferred ? `License transferred. Kiosk locked.` : (isExpired ? 'Subscription expired.' : 'Activation required.')),
    });
  }

  const newRecord = {
    deviceId: cleanId, hardwareHash, deviceModel: deviceModel || 'Unknown Device',
    firstRegisteredAt: now, paidExpiresAt: 0, lastCheckinAt: now, installCount: 1,
    ...(ownerEmail && { ownerEmail })
  };

  await kvPut(env, cleanId, newRecord);
  if (ownerEmail) await addDeviceToUserIndex(env, ownerEmail, cleanId);

  return jsonRes({
    status: 'UNACTIVATED', isPaid: false, isActivated: false, isValid: false, isExpired: false,
    deviceId: cleanId, deviceModel: newRecord.deviceModel, paidExpiresAt: 0, daysRemaining: 0,
    checkIntervalDays: 7, serverTime: now, message: 'Device registered. Ready for license activation.'
  });
}

// 2. Query Payment & Device Status
async function handleDeviceStatus(url, env, now) {
  const deviceId = url.searchParams.get('deviceId');
  if (!deviceId) return errRes('Missing deviceId parameter');

  const cleanId = normDevId(deviceId);
  const record = await kvGet(env, cleanId) || await kvGet(env, deviceId);

  if (!record || !(record.paidExpiresAt > now)) {
    const isExpired = record && record.paidExpiresAt > 0 && record.paidExpiresAt <= now;
    const isTransferred = record && !record.paidExpiresAt && record.licenseStatus === 'TRANSFERRED';
    const status = record ? (isTransferred ? 'TRANSFERRED' : (isExpired ? 'EXPIRED' : 'UNACTIVATED')) : 'UNREGISTERED';
    return jsonRes({
      paid: false, isPaid: false, isActivated: false, isValid: false,
      isExpired: Boolean(isExpired), isTransferred: Boolean(isTransferred),
      transferredTo: record?.transferredTo || null, deviceId: cleanId,
      deviceModel: record?.deviceModel || 'Unknown Device', status,
      paidExpiresAt: record?.paidExpiresAt || 0, daysRemaining: 0, checkIntervalDays: 7, serverTime: now,
      message: isTransferred ? 'License was transferred.' : (isExpired ? 'Subscription expired.' : 'No active license found.'),
    });
  }

  const token = await generateLicenseToken(cleanId, record.paidExpiresAt, env);
  const lastRef = Array.isArray(record.processedPaymentRefs) && record.processedPaymentRefs.length > 0 
    ? record.processedPaymentRefs[record.processedPaymentRefs.length - 1] : 'CONFIRMED';

  return jsonRes({
    paid: true, isPaid: true, isActivated: true, isValid: true, isExpired: false, status: 'PAID',
    deviceId: cleanId, deviceModel: record.deviceModel || 'Android Device',
    paidExpiresAt: record.paidExpiresAt, paymentReference: lastRef,
    daysRemaining: Math.max(0, Math.ceil((record.paidExpiresAt - now) / 86400000)),
    checkIntervalDays: 7, signature: token.signature, licenseKey: token.licenseKey, serverTime: now,
    message: 'Subscription active.'
  });
}

// 3. Webhook Payment Reconciliation
async function handlePaymentWebhook(request, env, now, licenseDuration) {
  const body = await request.json().catch(() => null);
  if (!body) return errRes('Invalid JSON payload');

  const ownerEmail = body.ownerToken ? await verifyGoogleToken(body.ownerToken) : body.ownerEmail;
  let deviceId = body.deviceId || body.data?.attributes?.metadata?.deviceId || body.metadata?.deviceId || body.reference_number || body.orderId;
  const paymentRef = body.paymentRef || body.id || body.data?.id || body.transaction_id || `PAY-${now}`;
  const cleanPaymentRef = String(paymentRef).trim();

  // Credit Purchase (no deviceId, ownerEmail present)
  if (!deviceId && ownerEmail) {
    let processedRefs = (await kvGet(env, `USER_PAYMENTS:${ownerEmail}`)) || [];
    if (processedRefs.includes(cleanPaymentRef)) {
      return jsonRes({ success: true, idempotent: true, serverTime: now, message: `Payment ${cleanPaymentRef} already processed.` });
    }

    let credits = parseInt((await kvGet(env, `USER_CREDITS:${ownerEmail}`)) || '0', 10);
    const quantity = parseInt(body.quantity || body.data?.attributes?.metadata?.quantity || body.metadata?.quantity || 1, 10);
    credits += quantity;

    processedRefs.push(cleanPaymentRef);
    await kvPut(env, `USER_CREDITS:${ownerEmail}`, credits.toString());
    await kvPut(env, `USER_PAYMENTS:${ownerEmail}`, processedRefs.slice(-50));

    return jsonRes({ success: true, idempotent: false, serverTime: now, creditsAdded: quantity, totalCredits: credits, message: `${quantity} credit(s) added to ${ownerEmail}.` });
  }

  if (!deviceId) return errRes('Missing deviceId in payload');

  const cleanId = normDevId(deviceId);
  let record = (await kvGet(env, cleanId)) || {
    deviceId: cleanId, hardwareHash: cleanId.toUpperCase(), deviceModel: 'Licensed via Webhook',
    firstRegisteredAt: now, installCount: 1, processedPaymentRefs: []
  };

  if (ownerEmail) record.ownerEmail = ownerEmail;
  const processedRefs = Array.isArray(record.processedPaymentRefs) ? record.processedPaymentRefs : [];

  if (processedRefs.includes(cleanPaymentRef)) {
    const token = await generateLicenseToken(cleanId, record.paidExpiresAt, env);
    return jsonRes({
      success: true, idempotent: true, status: 'PAID', deviceId: cleanId,
      deviceModel: record.deviceModel || 'Android Device', paidExpiresAt: record.paidExpiresAt,
      signature: token.signature, licenseKey: token.licenseKey, serverTime: now,
      message: `Payment ${cleanPaymentRef} already processed.`
    });
  }

  const currentPaidExpires = record.paidExpiresAt || 0;
  const newPaidExpires = (currentPaidExpires > now ? currentPaidExpires : now) + licenseDuration;

  record.paidExpiresAt = newPaidExpires;
  record.lastCheckinAt = now;
  record.paidAt = now;
  processedRefs.push(cleanPaymentRef);
  record.processedPaymentRefs = processedRefs.slice(-25);

  delete record.licenseType;
  delete record.paymentReference;
  delete record.trialExpiresAt;

  await kvPut(env, cleanId, record);
  if (record.ownerEmail) await addDeviceToUserIndex(env, record.ownerEmail, cleanId);

  const token = await generateLicenseToken(cleanId, newPaidExpires, env);
  return jsonRes({
    success: true, idempotent: false, status: 'PAID', deviceId: cleanId,
    deviceModel: record.deviceModel || 'Android Device', paidExpiresAt: newPaidExpires,
    signature: token.signature, licenseKey: token.licenseKey, serverTime: now,
    message: `Payment confirmed for device ${cleanId}.`
  });
}

// 4. User Devices List
async function handleUserDevices(request, env, now) {
  const body = await request.json();
  const email = await authenticateUserEmail(body);
  if (!email) return errRes('Unauthorized', 401, { devices: [] });

  const userDevices = await getUserDeviceList(env, email);
  const devices = [];
  const validUserDevices = [];

  for (const did of userDevices) {
    const dev = await kvGet(env, did);
    if (!dev) continue;
    const devOwner = dev.ownerEmail ? dev.ownerEmail.trim().toLowerCase() : null;
    if (devOwner && devOwner !== email) continue;

    validUserDevices.push(did);
    const isPaid = dev.paidExpiresAt > now;
    const isExpired = dev.paidExpiresAt > 0 && dev.paidExpiresAt <= now;
    const isTransferred = !isPaid && dev.licenseStatus === 'TRANSFERRED';
    let licenseKey = null;
    if (isPaid) {
      try {
        const token = await generateLicenseToken(dev.deviceId || did, dev.paidExpiresAt, env);
        licenseKey = token.licenseKey;
      } catch (_) {}
    }

    devices.push({
      deviceId: dev.deviceId || did, deviceModel: dev.deviceModel || 'Unknown Device',
      firstRegisteredAt: dev.firstRegisteredAt || now, paidExpiresAt: dev.paidExpiresAt || 0,
      status: isPaid ? 'PAID' : (isTransferred ? 'TRANSFERRED' : (isExpired ? 'EXPIRED' : 'UNACTIVATED')),
      daysRemaining: isPaid ? Math.max(0, Math.ceil((dev.paidExpiresAt - now) / 86400000)) : 0,
      licenseKey, boxBuildNumber: dev.boxBuildNumber || null, transferredTo: dev.transferredTo || null,
      transferredFrom: dev.licenseTransferredFrom || null
    });
  }

  if (validUserDevices.length !== userDevices.length) {
    await kvPut(env, `USER_DEVICES:${email}`, validUserDevices);
  }

  return jsonRes({ success: true, email, devices, serverTime: now });
}

// 5. Link Device
async function handleLinkDevice(request, env, now) {
  const body = await request.json();
  const email = await authenticateUserEmail(body);
  if (!email) return errRes('Unauthorized', 401);

  const { deviceId } = body;
  if (!deviceId) return errRes('Missing deviceId parameter');

  const cleanId = normDevId(deviceId);
  let dev = await kvGet(env, cleanId);

  if (dev) {
    const devOwner = dev.ownerEmail ? dev.ownerEmail.trim().toLowerCase() : null;
    if (devOwner && devOwner !== email) return errRes('Device registered to another user account.', 403);
    dev.ownerEmail = email;
  } else {
    dev = {
      deviceId: cleanId, hardwareHash: cleanId.toUpperCase(), deviceModel: 'Manual Linked Kiosk',
      firstRegisteredAt: now, paidExpiresAt: 0, lastCheckinAt: now, installCount: 1, ownerEmail: email
    };
  }

  await kvPut(env, cleanId, dev);
  await addDeviceToUserIndex(env, email, cleanId);

  return jsonRes({ success: true, message: `Device ${cleanId} linked to ${email}`, deviceId: cleanId });
}

// 6. Get User Credits
async function handleUserCredits(request, env, now) {
  const body = await request.json();
  const email = await authenticateUserEmail(body);
  if (!email) return errRes('Unauthorized', 401, { credits: 0 });

  const rawCredits = await kvGet(env, `USER_CREDITS:${email}`);
  const credits = rawCredits ? parseInt(rawCredits, 10) : 0;
  return jsonRes({ success: true, email, credits, serverTime: now });
}

// 7. Activate Device with Credit
async function handleActivateDevice(request, env, now, licenseDuration) {
  const body = await request.json();
  const email = await authenticateUserEmail(body);
  if (!email) return errRes('Unauthorized', 401);

  const { deviceId } = body;
  if (!deviceId) return errRes('Missing deviceId parameter');

  const rawCredits = await kvGet(env, `USER_CREDITS:${email}`);
  let credits = rawCredits ? parseInt(rawCredits, 10) : 0;
  if (credits <= 0) return errRes('No credits available', 403);

  const cleanId = normDevId(deviceId);
  let dev = (await kvGet(env, cleanId)) || {
    deviceId: cleanId, hardwareHash: cleanId.toUpperCase(), deviceModel: 'Linked Kiosk',
    firstRegisteredAt: now, installCount: 1, processedPaymentRefs: []
  };

  const devOwner = dev.ownerEmail ? dev.ownerEmail.trim().toLowerCase() : null;
  if (devOwner && devOwner !== email) return errRes('Device owned by another user account.', 403);

  const currentPaidExpires = dev.paidExpiresAt || 0;
  const newPaidExpires = (currentPaidExpires > now ? currentPaidExpires : now) + licenseDuration;

  const token = await generateLicenseToken(cleanId, newPaidExpires, env);

  credits -= 1;
  await kvPut(env, `USER_CREDITS:${email}`, credits.toString());

  dev.paidExpiresAt = newPaidExpires;
  dev.lastCheckinAt = now;
  dev.paidAt = now;
  dev.ownerEmail = email;

  await kvPut(env, cleanId, dev);
  await addDeviceToUserIndex(env, email, cleanId);

  return jsonRes({
    success: true, message: `Device ${cleanId} activated using 1 credit.`,
    deviceId: cleanId, creditsRemaining: credits, signature: token.signature,
    licenseKey: token.licenseKey, paidExpiresAt: newPaidExpires
  });
}

// 8. Transfer License
async function handleTransferLicense(request, env, now) {
  const body = await request.json();
  const email = await authenticateUserEmail(body);
  if (!email) return errRes('Unauthorized', 401);

  const { sourceDeviceId, targetDeviceId, transferBoxAssignment } = body;
  if (!sourceDeviceId || !targetDeviceId) return errRes('Missing sourceDeviceId or targetDeviceId parameter');

  const cleanSourceId = normDevId(sourceDeviceId);
  const cleanTargetId = normDevId(targetDeviceId);
  if (getRawMac(cleanSourceId) === getRawMac(cleanTargetId)) return errRes('Source and target device cannot be the same.');

  const sourceDev = await kvGet(env, cleanSourceId) || await kvGet(env, getRawMac(cleanSourceId));
  if (!sourceDev) return errRes(`Source device ${cleanSourceId} not found.`, 404);

  const sourceOwner = sourceDev.ownerEmail ? sourceDev.ownerEmail.trim().toLowerCase() : null;
  if (sourceOwner && sourceOwner !== email) return errRes('You are not the registered owner of source device.', 403);

  const sourceExpires = sourceDev.paidExpiresAt || 0;
  const remainingMs = sourceExpires - now;
  if (remainingMs <= 0) return errRes('Source device does not have an active license to transfer.');

  const remainingDays = Math.max(1, Math.ceil(remainingMs / 86400000));

  let targetDev = (await kvGet(env, cleanTargetId)) || (await kvGet(env, getRawMac(cleanTargetId))) || {
    deviceId: cleanTargetId, hardwareHash: cleanTargetId.toUpperCase(), deviceModel: 'Replacement Phone',
    firstRegisteredAt: now, installCount: 1, processedPaymentRefs: []
  };

  const targetOwner = targetDev.ownerEmail ? targetDev.ownerEmail.trim().toLowerCase() : null;
  if (targetOwner && targetOwner !== email) return errRes('Target device is registered to another user account.', 403);

  const currentTargetExpires = targetDev.paidExpiresAt || 0;
  const newTargetExpires = (currentTargetExpires > now ? currentTargetExpires : now) + remainingMs;

  const token = await generateLicenseToken(cleanTargetId, newTargetExpires, env);

  const oldBoxNumber = sourceDev.boxBuildNumber || null;
  sourceDev.paidExpiresAt = 0;
  sourceDev.licenseStatus = 'TRANSFERRED';
  sourceDev.transferredTo = cleanTargetId;
  sourceDev.transferredAt = now;

  let boxTransferred = false;
  if (transferBoxAssignment && oldBoxNumber) {
    delete sourceDev.boxBuildNumber;
    targetDev.boxBuildNumber = oldBoxNumber;

    const boxData = await kvGet(env, `BOX:${oldBoxNumber}`);
    if (boxData) {
      boxData.linkedDevices = (boxData.linkedDevices || []).filter(id => getRawMac(id) !== getRawMac(cleanSourceId) && getRawMac(id) !== getRawMac(cleanTargetId));
      boxData.linkedDevices.push(cleanTargetId);
      await kvPut(env, `BOX:${oldBoxNumber}`, boxData);
      boxTransferred = true;
    }
  }

  await kvPut(env, cleanSourceId, sourceDev);

  targetDev.paidExpiresAt = newTargetExpires;
  targetDev.ownerEmail = email;
  targetDev.lastCheckinAt = now;
  targetDev.licenseTransferredFrom = cleanSourceId;
  targetDev.licenseTransferredAt = now;

  await kvPut(env, cleanTargetId, targetDev);
  await addDeviceToUserIndex(env, email, cleanTargetId);

  return jsonRes({
    success: true, message: `License transferred from ${cleanSourceId} to ${cleanTargetId} (${remainingDays} days remaining).`,
    sourceDeviceId: cleanSourceId, targetDeviceId: cleanTargetId, daysRemaining: remainingDays,
    paidExpiresAt: newTargetExpires, licenseKey: token.licenseKey, signature: token.signature,
    boxTransferred, boxBuildNumber: boxTransferred ? oldBoxNumber : null, serverTime: now
  });
}

// 9. Manual Payment Confirm (Admin)
async function handlePaymentConfirm(request, env, now, licenseDuration) {
  const body = await request.json();
  const { deviceId, paymentRef, adminSecret: reqSecret, addCredits, ownerEmail } = body;

  const adminSecret = env.ADMIN_SECRET;
  if (!adminSecret || reqSecret !== adminSecret) return errRes('Unauthorized: Invalid or missing Admin Secret.', 401);

  const cleanPaymentRef = paymentRef ? String(paymentRef).trim() : `MANUAL-${now}`;

  if (!deviceId && addCredits && ownerEmail) {
    let processedRefs = (await kvGet(env, `USER_PAYMENTS:${ownerEmail}`)) || [];
    if (processedRefs.includes(cleanPaymentRef)) {
      return jsonRes({ success: true, idempotent: true, serverTime: now, message: 'Credits already added for this ref.' });
    }
    let credits = parseInt((await kvGet(env, `USER_CREDITS:${ownerEmail}`)) || '0', 10) + parseInt(addCredits, 10);
    processedRefs.push(cleanPaymentRef);
    await kvPut(env, `USER_CREDITS:${ownerEmail}`, credits.toString());
    await kvPut(env, `USER_PAYMENTS:${ownerEmail}`, processedRefs.slice(-50));
    return jsonRes({ success: true, serverTime: now, creditsAdded: addCredits, totalCredits: credits, message: `Added ${addCredits} credits.` });
  }

  if (!deviceId) return errRes('Missing deviceId');
  const cleanId = normDevId(deviceId);
  let record = (await kvGet(env, cleanId)) || {
    deviceId: cleanId, hardwareHash: cleanId.toUpperCase(), deviceModel: 'Manual Activation',
    firstRegisteredAt: now, installCount: 1, processedPaymentRefs: []
  };

  if (ownerEmail) record.ownerEmail = ownerEmail;
  const processedRefs = Array.isArray(record.processedPaymentRefs) ? record.processedPaymentRefs : [];

  if (paymentRef && processedRefs.includes(cleanPaymentRef)) {
    const token = await generateLicenseToken(cleanId, record.paidExpiresAt, env);
    return jsonRes({
      success: true, idempotent: true, status: 'PAID', deviceId: cleanId,
      deviceModel: record.deviceModel || 'Android Device', paidExpiresAt: record.paidExpiresAt,
      signature: token.signature, licenseKey: token.licenseKey, serverTime: now, message: 'Payment already confirmed.'
    });
  }

  const currentPaidExpires = record.paidExpiresAt || 0;
  const newPaidExpires = (currentPaidExpires > now ? currentPaidExpires : now) + licenseDuration;

  record.paidExpiresAt = newPaidExpires;
  record.lastCheckinAt = now;
  record.paidAt = now;
  processedRefs.push(cleanPaymentRef);
  record.processedPaymentRefs = processedRefs.slice(-25);

  delete record.licenseType;
  delete record.paymentReference;
  delete record.trialExpiresAt;

  await kvPut(env, cleanId, record);
  if (record.ownerEmail) await addDeviceToUserIndex(env, record.ownerEmail, cleanId);

  const token = await generateLicenseToken(cleanId, newPaidExpires, env);
  return jsonRes({
    success: true, status: 'PAID', deviceId: cleanId, deviceModel: record.deviceModel || 'Android Device',
    paidExpiresAt: newPaidExpires, signature: token.signature, licenseKey: token.licenseKey, serverTime: now,
    message: 'Device marked as paid & license issued.'
  });
}

// 10. Coin Slot Box Verification
async function handleBoxVerify(request, env, now) {
  const body = await request.json();
  const email = await authenticateUserEmail(body);
  if (!email) return errRes('Authentication required.', 401);

  let rawInput = body.buildNumber;
  if (!rawInput || typeof rawInput !== 'string') return errRes('Please enter a valid Coin Slot Box Build Number or MAC Address.');

  rawInput = rawInput.trim();
  if (rawInput.includes('?')) {
    try {
      const urlObj = new URL(rawInput.startsWith('http') ? rawInput : 'https://dummy.local/' + rawInput);
      rawInput = urlObj.searchParams.get('box') || urlObj.searchParams.get('b') || urlObj.searchParams.get('mac') || urlObj.searchParams.get('id') || rawInput;
    } catch (_) {}
  } else if (rawInput.startsWith('{') && rawInput.endsWith('}')) {
    try { rawInput = JSON.parse(rawInput).box || rawInput; } catch (_) {}
  }

  const rawUpper = rawInput.trim().toUpperCase();
  const formattedMac = formatMacAddress(rawUpper);
  const cleanBuildNumber = formattedMac || rawUpper;

  let boxRecord = await kvGet(env, `BOX:${cleanBuildNumber}`);
  if (!boxRecord) {
    boxRecord = {
      buildNumber: cleanBuildNumber, esp32MacAddress: formattedMac || cleanBuildNumber,
      maxDevices: 1, linkedDevices: [], pricePhp: 0, firstClaimedBy: email, claimedAt: now,
    };
  }

  if (boxRecord.firstClaimedBy && boxRecord.firstClaimedBy.trim().toLowerCase() !== email) {
    return errRes('This Coin Slot Box build number is registered to another account.', 403);
  }

  boxRecord.firstClaimedBy = email;
  const linkedDevices = Array.isArray(boxRecord.linkedDevices) ? boxRecord.linkedDevices : [];

  await kvPut(env, `BOX:${cleanBuildNumber}`, boxRecord);

  const userBoxes = (await kvGet(env, `USER_BOXES:${email}`)) || [];
  if (!userBoxes.includes(cleanBuildNumber)) {
    userBoxes.push(cleanBuildNumber);
    await kvPut(env, `USER_BOXES:${email}`, userBoxes);
  }

  return jsonRes({
    success: true, message: 'Coin Slot Box verified successfully! (10 Devices Allowed)',
    buildNumber: cleanBuildNumber, maxDevices: 10, devicesUsed: linkedDevices.length,
    slotsRemaining: Math.max(0, 10 - linkedDevices.length), linkedDevices, serverTime: now,
  });
}

// 11. Box Status
async function handleBoxStatus(request, env, now) {
  const body = await request.json();
  const email = await authenticateUserEmail(body);
  if (!email) return errRes('Authentication required.', 401);

  const userBoxes = (await kvGet(env, `USER_BOXES:${email}`)) || [];
  const validUserBoxes = [];
  let allLinked = [];
  const boxDetails = [];

  for (const bNum of userBoxes) {
    const bData = await kvGet(env, `BOX:${bNum}`);
    if (!bData) continue;
    const claimedBy = bData.firstClaimedBy ? bData.firstClaimedBy.trim().toLowerCase() : null;
    if (claimedBy && claimedBy !== email) continue;

    validUserBoxes.push(bNum);
    const bLinked = Array.isArray(bData.linkedDevices) ? bData.linkedDevices : [];
    allLinked = allLinked.concat(bLinked);

    const boxDeviceList = [];
    for (const did of bLinked) {
      const dev = await kvGet(env, did);
      if (dev) {
        const isPaid = dev.paidExpiresAt > now;
        let licenseKey = null;
        if (isPaid) {
          try {
            const token = await generateLicenseToken(dev.deviceId || did, dev.paidExpiresAt, env);
            licenseKey = token.licenseKey;
          } catch (_) {}
        }
        boxDeviceList.push({
          deviceId: dev.deviceId || did, deviceModel: dev.deviceModel || 'Kiosk Device',
          firstRegisteredAt: dev.firstRegisteredAt || now, paidExpiresAt: dev.paidExpiresAt || 0,
          status: isPaid ? 'PAID' : 'UNACTIVATED',
          daysRemaining: isPaid ? Math.max(0, Math.ceil((dev.paidExpiresAt - now) / 86400000)) : 0,
          licenseKey, boxBuildNumber: bNum,
        });
      } else {
        boxDeviceList.push({
          deviceId: did, deviceModel: 'Kiosk Phone', firstRegisteredAt: now,
          paidExpiresAt: now + 365 * 86400000, status: 'PAID', daysRemaining: 365,
          licenseKey: null, boxBuildNumber: bNum,
        });
      }
    }

    boxDetails.push({
      buildNumber: bNum, maxDevices: bData.maxDevices || 10, devicesUsed: bLinked.length,
      slotsRemaining: Math.max(0, (bData.maxDevices || 10) - bLinked.length),
      linkedDevices: bLinked, devices: boxDeviceList,
    });
  }

  const totalAllowed = validUserBoxes.length * 10;
  if (validUserBoxes.length !== userBoxes.length) await kvPut(env, `USER_BOXES:${email}`, validUserBoxes);

  return jsonRes({
    success: true, hasVerifiedBox: validUserBoxes.length > 0, boxes: boxDetails,
    totalBoxes: validUserBoxes.length, totalMaxDevices: totalAllowed,
    totalDevicesUsed: allLinked.length, totalSlotsRemaining: Math.max(0, totalAllowed - allLinked.length),
    serverTime: now,
  });
}

// 12. Link Device to Box
async function handleBoxLinkDevice(request, env, now) {
  const body = await request.json();
  const { buildNumber, deviceId } = body;
  const email = await authenticateUserEmail(body);
  if (!email) return errRes('Authentication required.', 401);
  if (!deviceId) return errRes('Missing deviceId.');

  const cleanDevId = normDevId(deviceId);
  let targetBoxNumber = buildNumber ? String(buildNumber).trim().toUpperCase() : null;

  const existingDev = await kvGet(env, cleanDevId);
  if (existingDev) {
    const devOwner = existingDev.ownerEmail ? existingDev.ownerEmail.trim().toLowerCase() : null;
    if (devOwner && devOwner !== email) return errRes('Device belongs to another user account.', 403);
  }

  if (!targetBoxNumber) {
    const userBoxes = (await kvGet(env, `USER_BOXES:${email}`)) || [];
    for (const bNum of userBoxes) {
      const bData = await kvGet(env, `BOX:${bNum}`);
      if (!bData) continue;
      const claimedBy = bData.firstClaimedBy ? bData.firstClaimedBy.trim().toLowerCase() : null;
      if (claimedBy && claimedBy !== email) continue;
      const bLinked = Array.isArray(bData.linkedDevices) ? bData.linkedDevices : [];
      if (bLinked.includes(cleanDevId) || bLinked.length < (bData.maxDevices || 10)) {
        targetBoxNumber = bNum;
        break;
      }
    }
  }

  if (!targetBoxNumber) return errRes('No verified Coin Slot Box found with available device slots.', 403);

  const boxData = await kvGet(env, `BOX:${targetBoxNumber}`);
  if (!boxData) return errRes('Coin Slot Box not found.', 404);

  const claimedBy = boxData.firstClaimedBy ? boxData.firstClaimedBy.trim().toLowerCase() : null;
  if (claimedBy && claimedBy !== email) return errRes('Coin Slot Box belongs to another user account.', 403);

  const linked = Array.isArray(boxData.linkedDevices) ? boxData.linkedDevices : [];
  if (!linked.includes(cleanDevId)) {
    if (linked.length >= (boxData.maxDevices || 10)) return errRes('Coin Slot Box reached maximum limit of 10 devices.');
    linked.push(cleanDevId);
    boxData.linkedDevices = linked;
    await kvPut(env, `BOX:${targetBoxNumber}`, boxData);

    let dev = existingDev || {
      deviceId: cleanDevId, hardwareHash: cleanDevId.toUpperCase(), deviceModel: 'PisoPhone Kiosk Phone',
      firstRegisteredAt: now, paidExpiresAt: now + (365 * 86400000), lastCheckinAt: now, installCount: 1
    };
    dev.ownerEmail = email;
    dev.boxBuildNumber = targetBoxNumber;
    if (!dev.paidExpiresAt || dev.paidExpiresAt < now) dev.paidExpiresAt = now + (365 * 86400000);
    await kvPut(env, cleanDevId, dev);
    await addDeviceToUserIndex(env, email, cleanDevId);
  }

  return jsonRes({
    success: true, buildNumber: targetBoxNumber, deviceId: cleanDevId, devicesUsed: linked.length,
    maxDevices: boxData.maxDevices || 10, slotsRemaining: Math.max(0, (boxData.maxDevices || 10) - linked.length),
    message: `Device linked to Coin Slot Box (${linked.length}/10 slots used).`,
  });
}

// 13. Unlink Device from Box
async function handleBoxUnlinkDevice(request, env) {
  const body = await request.json();
  const { buildNumber, deviceId } = body;
  const email = await authenticateUserEmail(body);
  if (!email) return errRes('Authentication required.', 401);
  if (!buildNumber || !deviceId) return errRes('Missing buildNumber or deviceId.');

  const cleanDevId = normDevId(deviceId);
  const cleanBoxNum = String(buildNumber).trim().toUpperCase();

  const boxData = await kvGet(env, `BOX:${cleanBoxNum}`);
  if (boxData) {
    const claimedBy = boxData.firstClaimedBy ? boxData.firstClaimedBy.trim().toLowerCase() : null;
    if (claimedBy && claimedBy !== email) return errRes('Coin Slot Box belongs to another user account.', 403);
    boxData.linkedDevices = (boxData.linkedDevices || []).filter(id => getRawMac(id) !== getRawMac(cleanDevId));
    await kvPut(env, `BOX:${cleanBoxNum}`, boxData);
  }

  const devData = await kvGet(env, cleanDevId) || await kvGet(env, getRawMac(cleanDevId));
  if (devData) {
    delete devData.boxBuildNumber;
    await kvPut(env, cleanDevId, devData);
  }

  return jsonRes({ success: true, message: `Device ${cleanDevId} unlinked from Coin Slot Box #${cleanBoxNum}.` });
}

// 14. Remove Device
async function handleRemoveDevice(request, env) {
  const body = await request.json();
  const { deviceId } = body;
  const email = await authenticateUserEmail(body);
  if (!email) return errRes('Authentication required.', 401);
  if (!deviceId) return errRes('Missing deviceId parameter.');

  const cleanDevId = normDevId(deviceId);
  const devData = await kvGet(env, cleanDevId) || await kvGet(env, getRawMac(cleanDevId));

  const devBoxBuildNumber = devData?.boxBuildNumber || null;
  const devOwnerEmail = devData?.ownerEmail ? devData.ownerEmail.trim().toLowerCase() : null;

  await removeDeviceFromUserIndex(env, email, cleanDevId);
  if (devOwnerEmail && devOwnerEmail !== email) await removeDeviceFromUserIndex(env, devOwnerEmail, cleanDevId);

  const userBoxes = (await kvGet(env, `USER_BOXES:${email}`)) || [];
  if (devBoxBuildNumber && !userBoxes.includes(devBoxBuildNumber)) userBoxes.push(devBoxBuildNumber);

  for (const boxNum of userBoxes) {
    const boxData = await kvGet(env, `BOX:${boxNum}`);
    if (boxData) {
      boxData.linkedDevices = (boxData.linkedDevices || []).filter(id => getRawMac(id) !== getRawMac(cleanDevId));
      await kvPut(env, `BOX:${boxNum}`, boxData);
    }
  }

  await kvDelete(env, `HW-${getRawMac(cleanDevId)}`);
  await kvDelete(env, getRawMac(cleanDevId));
  await kvDelete(env, cleanDevId);

  return jsonRes({ success: true, message: `Device ${cleanDevId} successfully removed.` });
}

// 15. ESP32 Snapshot Reporting
async function handleBoxReportSnapshot(request, env, now) {
  const body = await request.json();
  const { mac, tier, slots, lifetimeCoins, lifetimeEarnings, wifiSsid, firmwareVersion } = body;
  if (!mac || typeof mac !== 'string') return errRes('Missing or invalid MAC address');

  const rawMac = mac.trim().toUpperCase();
  const cleanMac = formatMacAddress(rawMac) || rawMac;

  let boxData = (await kvGet(env, `BOX:${cleanMac}`)) || {
    buildNumber: cleanMac, macAddress: cleanMac, maxDevices: Math.max(2, parseInt(tier) || 2),
    linkedDevices: [], claimedAt: now,
  };

  if (tier && parseInt(tier) > (boxData.maxDevices || 2)) boxData.maxDevices = parseInt(tier);

  const cleanSlots = Array.isArray(slots) ? slots : [];
  boxData.slots = cleanSlots;
  boxData.linkedDevices = cleanSlots.filter(s => s?.deviceId?.trim().length > 0).map(s => normDevId(s.deviceId));

  boxData.lastSnapshotAt = now;
  if (lifetimeCoins !== undefined) boxData.lifetimeCoins = parseInt(lifetimeCoins) || 0;
  if (lifetimeEarnings !== undefined) boxData.lifetimeEarnings = parseFloat(lifetimeEarnings) || 0;
  if (wifiSsid) boxData.wifiSsid = String(wifiSsid);
  if (firmwareVersion) boxData.firmwareVersion = String(firmwareVersion);

  await kvPut(env, `BOX:${cleanMac}`, boxData);

  for (const slot of cleanSlots) {
    if (slot?.deviceId?.trim().length > 0) {
      const cleanDevId = normDevId(slot.deviceId);
      let devRecord = (await kvGet(env, cleanDevId)) || {
        deviceId: cleanDevId, status: 'PAID', firstRegisteredAt: now, ownerEmail: boxData.firstClaimedBy || null,
      };
      devRecord.boundBoxMac = cleanMac;
      devRecord.slotNum = slot.slotNum || slot.slot || 1;
      devRecord.paidExpiresAt = slot.expiresAt || (now + 365 * 86400000);
      devRecord.status = 'PAID';
      devRecord.lastSnapshotAt = now;
      if (slot.name) devRecord.deviceName = slot.name;
      if (slot.ip) devRecord.lastKnownIp = slot.ip;

      await kvPut(env, cleanDevId, devRecord);
    }
  }

  return jsonRes({
    success: true, message: 'Box snapshot recorded successfully.', boxMac: cleanMac,
    maxDevices: boxData.maxDevices, activeSlotsCount: boxData.linkedDevices.length, serverTime: now,
  });
}

// 16. Issue Slot Upgrade Token
async function handleBoxIssueSlotToken(request, env, now) {
  const body = await request.json();
  const { boxMac, slotsCount } = body;
  const email = await authenticateUserEmail(body);
  if (!email) return errRes('Authentication required.', 401);
  if (!boxMac || typeof boxMac !== 'string') return errRes('Valid Box MAC Address required.');

  const rawMac = boxMac.trim().toUpperCase();
  const cleanMac = formatMacAddress(rawMac) || rawMac;
  const targetSlots = Math.max(1, Math.min(32, parseInt(slotsCount) || 2));

  const secret = env.LICENSE_SIGNING_SECRET || env.ADMIN_SECRET || 'PISOPHONE_HMAC_MASTER_KEY';
  const sigPayload = `PISOSLOT:${cleanMac}:${targetSlots}`;
  const signature = await calculateHmacSha256Hex(sigPayload, secret);
  const slotToken = `PISOSLOT.${cleanMac}.${targetSlots}.${signature}`;

  let boxData = (await kvGet(env, `BOX:${cleanMac}`)) || {
    buildNumber: cleanMac, macAddress: cleanMac, maxDevices: targetSlots,
    linkedDevices: [], firstClaimedBy: email, claimedAt: now,
  };
  boxData.maxDevices = Math.max(boxData.maxDevices || 2, targetSlots);

  await kvPut(env, `BOX:${cleanMac}`, boxData);

  return jsonRes({
    success: true, boxMac: cleanMac, slotsCount: targetSlots, token: slotToken,
    message: `Slot token for ${targetSlots} seats issued successfully.`, serverTime: now,
  });
}

// ============================================================================
// MAIN WORKER FETCH DISPATCHER
// ============================================================================
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const method = request.method;
    const path = url.pathname;

    if (method === 'OPTIONS') {
      return new Response(null, { headers: CORS_HEADERS });
    }

    // Health & System Information
    if (path === '/' || path === '/health') {
      return jsonRes({ status: 'ok', service: 'PisoPhone Licensing API', serverTime: Date.now() });
    }

    // Static Redirects
    if (path === '/apk/latest.apk' || path === '/app-release.apk') {
      return Response.redirect('https://pisophone.pages.dev/update/app-release.apk', 302);
    }
    if (path === '/firmware.bin' || path === '/esp32/firmware.bin') {
      return Response.redirect('https://pisophone.pages.dev/update/firmware.bin', 302);
    }
    if (path === '/firmware.json' || path === '/esp32/firmware.json') {
      return Response.redirect('https://pisophone.pages.dev/update/firmware.json', 302);
    }

    // Rate Limiting (60 requests/min)
    if (!checkRateLimit(request, 60, 60)) {
      return jsonRes({ error: 'Too many requests. Please slow down.', serverTime: Date.now() }, 429);
    }

    try {
      const now = Date.now();
      const licenseDuration = env.LICENSE_DURATION_MS ? parseInt(env.LICENSE_DURATION_MS, 10) : DEFAULT_LICENSE_DURATION_MS;

      // API Endpoint Dispatcher
      if (path === '/api/device/register' && method === 'POST') return await handleDeviceRegister(request, env, now);
      if ((path === '/api/payment/check' || path === '/api/device/status') && method === 'GET') return await handleDeviceStatus(url, env, now);
      if (path === '/api/payment/webhook' && method === 'POST') return await handlePaymentWebhook(request, env, now, licenseDuration);
      if (path === '/api/user/devices' && method === 'POST') return await handleUserDevices(request, env, now);
      if (path === '/api/user/link-device' && method === 'POST') return await handleLinkDevice(request, env, now);
      if (path === '/api/user/credits' && method === 'POST') return await handleUserCredits(request, env, now);
      if (path === '/api/user/activate-device' && method === 'POST') return await handleActivateDevice(request, env, now, licenseDuration);
      if (path === '/api/user/transfer-license' && method === 'POST') return await handleTransferLicense(request, env, now);
      if (path === '/api/payment/confirm' && method === 'POST') return await handlePaymentConfirm(request, env, now, licenseDuration);
      if (path === '/api/box/verify' && method === 'POST') return await handleBoxVerify(request, env, now);
      if (path === '/api/box/status' && method === 'POST') return await handleBoxStatus(request, env, now);
      if (path === '/api/box/link-device' && method === 'POST') return await handleBoxLinkDevice(request, env, now);
      if (path === '/api/box/unlink-device' && method === 'POST') return await handleBoxUnlinkDevice(request, env);
      if (path === '/api/user/remove-device' && method === 'POST') return await handleRemoveDevice(request, env);
      if (path === '/api/box/pricing' && (method === 'GET' || method === 'POST')) {
        return jsonRes({
          success: true, basePrice: 0, baseIncludedSlots: 1, additionalSlotPrice: 500,
          tiers: [
            { slots: 1, name: 'Start Pack (Default)', price: 0, description: 'Start Pack - 1 Slot Included Free' },
            { slots: 2, name: '2 Slots Upgrade', price: 500, description: 'Start Pack + 1 Additional Device Slot (500 PHP Lifetime License)' },
            { slots: 3, name: '3 Slots Upgrade', price: 1000, description: 'Start Pack + 2 Additional Device Slots (500 PHP/Slot Lifetime License)' },
            { slots: 5, name: '5 Slots Pro Pack', price: 2000, description: 'Start Pack + 4 Additional Device Slots (500 PHP/Slot Lifetime License)' },
            { slots: 10, name: '10 Slots Fleet Pack', price: 4500, description: 'Start Pack + 9 Additional Device Slots (500 PHP/Slot Lifetime License)' },
          ], serverTime: now,
        });
      }
      if (path === '/api/box/report-snapshot' && method === 'POST') return await handleBoxReportSnapshot(request, env, now);
      if (path === '/api/box/issue-slot-token' && method === 'POST') return await handleBoxIssueSlotToken(request, env, now);

      return errRes('Endpoint not found', 404);
    } catch (e) {
      return errRes(e.message, 500);
    }
  },
};
