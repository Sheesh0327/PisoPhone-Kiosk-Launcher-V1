# TechNet account preview

This milestone adds Google and email-code sign-in, sign-out, and an account page that displays the current identity verified by the account Worker. It uses a separate test Supabase project and a separate Cloudflare Pages project. See the [storefront and activation master plan](../docs/plans/technet-storefront-activation.md) for later milestones.

## 1. Prepare Cloudflare

Run these commands in PowerShell from the repository. Use Node.js 22 or newer, npm, and Git. If Wrangler reports that it cannot read an ancestor of a OneDrive folder, use a checkout in a regular local folder that your Windows account can read.

```powershell
Set-Location account-site
npm.cmd ci
npx.cmd wrangler login
npx.cmd wrangler whoami
npx.cmd wrangler pages project create pisophone-accounts-test --production-branch account-preview
```

Confirm that Wrangler is using your Cloudflare account. Use a new Pages project name if the example is taken. In the Workers & Pages dashboard, copy the new project's actual stable `pages.dev` address. Use that origin throughout the remaining steps, without a trailing slash. Its `account-preview` deployment is the main deployment of the separate testing site.

## 2. Set up test sign-in

1. Create a test Supabase project. Copy its **Project URL** and **publishable key** (starts with `sb_publishable_`).
2. In **Authentication → URL Configuration**, set **Site URL** to the stable Pages test origin. Add its full `/account/callback/` URL to **Redirect URLs**. Use that exact hostname to open the test site, including when Wrangler also prints a deployment-specific hostname.
3. In Google Cloud, create an OAuth client for a **Web application**. Copy the Google callback URL displayed by the Supabase Google provider settings into Google's **Authorized redirect URIs**. Enable Google in the Supabase project and enter the Google client ID and secret there. If the Google app is in testing mode, add the Google accounts you will use as test users.
4. For email codes, edit the **Magic Link** template in Supabase Auth and display `{{ .Token }}` as the sign-in code. Supabase's default SMTP is restricted to team addresses. Use a permitted test email first or configure custom SMTP to test other addresses.

[Google sign-in](https://supabase.com/docs/guides/auth/social-login/auth-google) · [Email OTP](https://supabase.com/docs/guides/auth/auth-email-passwordless) · [Custom SMTP](https://supabase.com/docs/guides/auth/auth-smtp)

## 3. Deploy the Worker and Pages test site

The Worker variables below are public configuration. Keep the Google client secret in Supabase. This Worker uses the public key and the requesting user's session token to ask Supabase for the verified account. No service-role or database secret is needed.

```powershell
$pagesProject = 'pisophone-accounts-test'
$env:PAGES_ORIGIN = 'https://pisophone-accounts-test.pages.dev'
$env:SUPABASE_URL = 'https://YOUR-PROJECT-REF.supabase.co'
$env:SUPABASE_PUBLISHABLE_KEY = 'sb_publishable_REPLACE_ME'

npm.cmd run worker:deploy -- --var "PAGES_ORIGIN:$env:PAGES_ORIGIN" --var "SUPABASE_URL:$env:SUPABASE_URL" --var "SUPABASE_PUBLISHABLE_KEY:$env:SUPABASE_PUBLISHABLE_KEY"
```

Use the exact public Worker URL printed by Wrangler in the next step. If this is your first Worker, Cloudflare may prompt you to register a `workers.dev` subdomain. Update `$pagesProject` and `PAGES_ORIGIN` above if you used a different test project name.

```powershell
$env:ACCOUNT_API_URL = 'https://pisophone-account-api-test.YOUR-SUBDOMAIN.workers.dev'

npm.cmd run build
npx.cmd wrangler pages deploy dist --project-name $pagesProject --branch account-preview
npm.cmd run smoke
Start-Process "$env:PAGES_ORIGIN/account/"
```

The build copies Git-tracked public website assets and adds the account routes, same-origin Supabase browser script, and account-specific CSP to `dist/`. Use the original `website/` folder for ordinary production deployment. For local static preview, run `npx.cmd wrangler pages dev dist`. To test sign-in locally, use that origin in the Worker and allow its callback in Supabase. The Worker allows one exact browser origin per deployment.

[Worker deployment](https://developers.cloudflare.com/workers/wrangler/commands/workers/) · [Cloudflare Pages Direct Upload](https://developers.cloudflare.com/pages/get-started/direct-upload/)

## 4. Acceptance checks

- Run `npm.cmd test` for Worker behavior with simulated Supabase responses. Run `npm.cmd run smoke` against the deployed Worker to check health, CORS, and rejection of missing and malformed sessions.
- Open the stable test `/account/` URL. Sign in with Google, reload the page, verify the expected email and account ID, and sign out. Check a cancelled Google flow can be retried.
- In a different browser profile, sign in with an email code. Verify the identity belongs to that test user. Check an incorrect code, resend, a reload after sign-in, and sign-out.
- Where a test token is known expired or belongs to another test Supabase project, confirm `/api/me` rejects it. Use test sessions only. The Worker response should be private and include `Cache-Control: no-store`.
- On an account HTML response verify the effective `connect-src` includes the two test services. Open a box-generated phone-enrollment link, or use a fixture, on the preview origin and verify the provisioning view still loads. Use only a workshop box if testing with real credentials.

If sign-in returns to the wrong hostname, compare the callback URL in Supabase with the stable hostname you actually opened. If the Worker reports an origin error, compare the page origin with `PAGES_ORIGIN` exactly. If Google succeeds but the account check fails, verify the Worker and the Pages build both point at the same Supabase project.
