# Storefront preview

The account preview adds a test store at `/account/store/` using the existing Supabase session. Use synthetic customer names, addresses and payment references. The sample prices and lead times are fixtures. There is no real payment destination.

## Supabase setup

Run [storefront.sql](supabase/storefront.sql) once in the **PisoPhone test project** SQL Editor. It adds the test catalog, orders with price snapshots, a seller role, and quote notification records. Authentication settings and existing users are reused.

Grant seller access to a specific existing test user's UUID from **Authentication → Users**. Run the following as the database owner with the UUID substituted.

```sql
insert into public.store_sellers (user_id) values ('SELLER-USER-UUID')
on conflict do nothing;
```

Keep a separate buyer account for isolation checks. A signed-in user can see their own orders. The seller can edit sample listings, quote shipping, review test references and record a direct sale without assigning it to a buyer account. Direct sales use the same validation and review workflow as web orders.

## Pages and Worker

Use the preview build and deployment commands in the [account setup guide](README.md). The test store uses the same published origin and callback. Rebuild Pages after checkout changes. Deploy the Worker for quote-email delivery. Its existing account verification route uses the same three public settings as before.

To exercise quote emails, configure a verified Resend sender, a Worker secret named `STORE_MAIL_API_KEY`, and Worker variables `STORE_MAIL_FROM` and `STORE_TEST_EMAIL_TO`. Use one approved test recipient. The seller dashboard tries the send after a quote is stored, displays its status, and can retry. The quote stays visible in the buyer's order history even when email is unconfigured or fails. Supabase Auth email delivery is configured independently. [Resend idempotency](https://resend.com/changelog/idempotency-keys)

## Verification

From `account-site`, run these checks. The isolated PGlite install is for local SQL verification and is excluded from Git and the Pages build.

```powershell
npm.cmd test
npm.cmd install --prefix .wrangler/db-test --no-package-lock @electric-sql/pglite
node scripts/test-storefront.mjs
```

On the hosted preview, use a buyer profile to submit a sample shipping order. Use the seller profile to enter a shipping charge. Reopen the buyer order, submit a synthetic reference and confirm that the seller can verify the exact total. Check pickup and direct sales separately. Log in as a second buyer and verify the first buyer's order and its lines are inaccessible. Confirm the existing phone enrollment entry point still loads.
