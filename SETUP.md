# DGM Service API — Setup Guide
Plug-and-play backend for field service businesses.
Complete this guide once per new client deployment.

---

## What you need before starting
- A Supabase account (supabase.com) — free tier works for MVP
- A Twilio account (twilio.com) — buy one phone number (~$1/mo)
- A Stripe account (stripe.com) — free to set up, pay-per-transaction
- A Railway account (railway.app) — free trial, then ~$5/mo
- Git and Java 17 installed locally for local testing

---

## Step 1 — Clone and configure

```bash
git clone https://github.com/your-org/dgm-service-api.git
cd dgm-service-api
cp .env.example .env
```

Open `.env` and fill in every value. The sections below tell you
exactly where to find each credential.

---

## Step 2 — Supabase setup

1. Go to supabase.com → New project
2. Choose a region close to your client (us-east-1 for Nashville)
3. Once created, go to **Project Settings → API**
   - Copy **Project URL** → `SUPABASE_URL`
   - Copy **service_role key** → `SUPABASE_SERVICE_KEY`
   - Copy **JWT Secret** (under JWT Settings) → `SUPABASE_JWT_SECRET`

4. Go to **SQL Editor** → paste the full contents of:
   `src/main/resources/db/schema.sql`
   Click **Run**. All tables, indexes, and RLS policies are created.

5. Go to **Authentication → Users** → Add user
   - Create the owner account (email + password)
   - Copy the user's UUID

6. Back in **SQL Editor**, run the availability seed:
   ```sql
   INSERT INTO availability (owner_id, day_of_week, start_time, end_time)
   VALUES
     ('PASTE_OWNER_UUID_HERE', 1, '08:00', '17:00'),
     ('PASTE_OWNER_UUID_HERE', 2, '08:00', '17:00'),
     ('PASTE_OWNER_UUID_HERE', 3, '08:00', '17:00'),
     ('PASTE_OWNER_UUID_HERE', 4, '08:00', '17:00'),
     ('PASTE_OWNER_UUID_HERE', 5, '08:00', '17:00');
   ```

7. Set the owner's role in app_metadata so the JWT contains it:
   Go to **Authentication → Users** → click the owner user → 
   Edit **app_metadata** and add:
   ```json
   { "role": "owner" }
   ```
   Do the same for any tech accounts with `{ "role": "tech" }`.

---

## Step 3 — Twilio setup

1. Go to twilio.com/console
2. Copy **Account SID** → `TWILIO_ACCOUNT_SID`
3. Copy **Auth Token** → `TWILIO_AUTH_TOKEN`
4. Go to **Phone Numbers → Manage → Buy a number**
   - Search for a Nashville (615) area code number
   - Purchase it
   - Copy the number in E.164 format (e.g. +16155550000) → `TWILIO_FROM_NUMBER`

Test SMS sending locally before deploying:
```bash
./gradlew bootRun
curl -X POST http://localhost:8080/api/notifications/booking-confirmed \
  -H "Content-Type: application/json" \
  -d '{"guestPhone":"+1YOUR_PERSONAL_NUMBER","guestName":"Test"}'
```
You should receive a text within 10 seconds.

---

## Step 4 — Stripe setup

1. Go to dashboard.stripe.com
2. Make sure you are in **Test mode** first
3. Go to **Developers → API keys**
   - Copy **Secret key** → `STRIPE_SECRET_KEY`
   - (Use `sk_test_...` for testing, `sk_live_...` for production)

4. Register the webhook endpoint:
   - Go to **Developers → Webhooks → Add endpoint**
   - Endpoint URL: `https://YOUR_RAILWAY_URL/api/invoices/stripe-webhook`
   - Events to listen to: `payment_intent.succeeded`, `payment_link.completed`
   - Copy the **Signing secret** → `STRIPE_WEBHOOK_SECRET`

   NOTE: You need a deployed URL for this step. Come back after Step 5
   to register the webhook with your Railway URL.

---

## Step 5 — Deploy to Railway

1. Go to railway.app → New Project → Deploy from GitHub repo
2. Select your forked/cloned `dgm-service-api` repository
3. Railway detects the Dockerfile automatically
4. Go to **Variables** tab in Railway and add every variable from your `.env`:

   ```
   SUPABASE_URL
   SUPABASE_SERVICE_KEY
   SUPABASE_JWT_SECRET
   TWILIO_ACCOUNT_SID
   TWILIO_AUTH_TOKEN
   TWILIO_FROM_NUMBER
   STRIPE_SECRET_KEY
   STRIPE_WEBHOOK_SECRET
   BUSINESS_NAME
   BUSINESS_SHORT_NAME
   BUSINESS_TAGLINE
   OWNER_PHONE
   BUSINESS_TIMEZONE
   AFTER_HOURS_SURCHARGE
   ```

5. Railway builds and deploys. Wait for the green checkmark (~2-3 min).
6. Copy the generated Railway URL (e.g. `https://dgm-service-api.up.railway.app`)
7. Go back to Stripe and register the webhook with this URL (see Step 4).

---

## Step 6 — Connect the Lovable frontend

In your Cloudflare Pages project, set these environment variables:

```
VITE_SUPABASE_URL         = (same as SUPABASE_URL)
VITE_SUPABASE_ANON_KEY    = (Supabase anon/public key — NOT service key)
VITE_API_BASE_URL         = https://your-railway-url.up.railway.app
```

The frontend calls Supabase directly for auth and data reads.
It calls the Railway API for availability slots, notifications,
and invoice operations.

Update the CORS allowed origin in `SecurityConfig.java`:
```java
"https://your-cloudflare-pages-domain.pages.dev"
```
Redeploy after this change.

---

## Step 7 — End-to-end test checklist

Work through these in order:

- [ ] Load the landing page — no console errors
- [ ] Click "Book a Job" → service selector loads
- [ ] Select a task → job details form appears
- [ ] Submit details → availability calendar loads with real slots
- [ ] Select a slot → confirmation screen shows correct task/time
- [ ] Submit → work order created in Supabase, SMS received on guest phone
- [ ] Log in as owner → dashboard shows the new work order
- [ ] Assign tech to the work order
- [ ] Log in as tech → job appears on My Day
- [ ] Tap "On my way" → SMS received on guest phone
- [ ] Upload after-photo + add notes → tap "Complete"
- [ ] Owner dashboard shows job as complete
- [ ] Owner sends invoice → Stripe payment link SMS received
- [ ] Open link → pay in Stripe test mode
- [ ] Invoice status updates to "paid" in Supabase

---

## Onboarding a new client (after first deployment)

To deploy this backend for a different service business:

1. Create a new Supabase project
2. Create a new Twilio number
3. Create a new Stripe account (or use a separate Stripe product)
4. Create a new Railway service (or add an environment in the same project)
5. Set these `.env` values to the new client's info:
   ```
   BUSINESS_NAME
   BUSINESS_SHORT_NAME
   BUSINESS_TAGLINE
   OWNER_PHONE
   BUSINESS_TIMEZONE
   ```
6. Run `schema.sql` in the new Supabase project
7. Deploy — all notification templates, invoice logic, and
   availability engine work out of the box for the new client.

No code changes required. Only environment variables change per client.

---

## Local development

```bash
# Run with local .env file
./gradlew bootRun

# Run tests
./gradlew test

# Build fat jar (output: build/libs/dgm-service-api-1.0.0.jar)
./gradlew bootJar

# Run jar directly
java -jar build/libs/dgm-service-api-1.0.0.jar
```

Swagger UI available at: http://localhost:8080/swagger-ui.html
API docs (JSON) at:       http://localhost:8080/api-docs

---

## v2 — Property Manager Module

### What v2 adds
- `pm_accounts` table and full CRUD
- `task_templates` table with seeded templates for common PM jobs
- `maintenance_contracts` table linking PMs, properties, and templates
- Nightly contract scheduler (runs at 1:00 AM UTC — auto-generates work orders)
- Portfolio dashboard endpoint for PM summary views
- Unit vacancy trigger endpoint for make-ready work orders

### Deploy v2 schema

Run `schema_v2.sql` in the Supabase SQL Editor **after** `schema.sql` is applied:
```
src/main/resources/db/schema_v2.sql
```

This adds the new tables, indexes, RLS policies, seeds the default task
templates, and adds the required columns to existing tables via `ALTER TABLE`.

### Scheduler configuration

The nightly scheduler runs automatically once deployed — no extra setup needed.
It is enabled by the `@EnableScheduling` annotation on `DgmServiceApplication`.

To change the run time, set this env var in Railway:
```
SCHEDULER_CRON=0 0 2 * * *    # runs at 2:00 AM UTC instead
```
Then wire it into `application.yml`:
```yaml
scheduler:
  cron: ${SCHEDULER_CRON:0 0 1 * * *}
```
And update `@Scheduled(cron = "${scheduler.cron}", zone = "UTC")` in `ContractScheduler`.

### New API endpoints (v2)

PM Accounts:
  POST   /api/pm/accounts              Create a PM account
  GET    /api/pm/accounts              List all PM accounts
  GET    /api/pm/accounts/{id}         Get a single PM account
  DELETE /api/pm/accounts/{id}         Deactivate a PM account
  GET    /api/pm/accounts/{id}/dashboard  Portfolio summary dashboard
  GET    /api/pm/history?propertyId=X  Unit/property maintenance history
  POST   /api/pm/unit-vacancy          Trigger a make-ready work order

Contracts:
  POST   /api/contracts                Create a maintenance contract
  GET    /api/contracts/pm/{pmId}      List contracts for a PM
  GET    /api/contracts/property/{id}  List contracts for a property
  POST   /api/contracts/{id}/pause     Pause a contract
  POST   /api/contracts/{id}/resume    Resume a paused contract
  DELETE /api/contracts/{id}           Delete a contract

All v2 endpoints require OWNER role JWT.
