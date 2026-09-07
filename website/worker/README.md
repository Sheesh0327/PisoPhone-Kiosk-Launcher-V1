# Cloudflare Worker Serverless Backend

This directory contains the serverless backend code for the **PisoPhone Operator Portal**.

## File Overview

- **`cloudflare_worker.js`**: Universal Cloudflare Worker script that handles:
  - Google OAuth token validation & operator authentication
  - Hardware box registration and cryptographic binding
  - License generation using AES-256 HMAC tokens
  - Online credit and payment reconciliation (Xendit / Maya / GCash)
  - QR Code generation for one-tap mobile activation

## Deployment to Cloudflare Workers

1. Install Wrangler CLI:
   ```bash
   npm install -g wrangler
   ```
2. Log in to Cloudflare:
   ```bash
   wrangler login
   ```
3. Deploy the worker:
   ```bash
   wrangler deploy cloudflare_worker.js --name pisophone-api
   ```
4. Set required Environment Secrets in Cloudflare Dashboard / Wrangler:
   - `MASTER_SECRET`: 256-bit hexadecimal key for signing offline license tokens.
   - `GOOGLE_CLIENT_ID`: Google Identity Services client ID.
   - `D1_DATABASE` or `KV_NAMESPACE`: Bound storage for operators, boxes, and activations.
