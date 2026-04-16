-- ============================================================
--  DGM Service API — Supabase Database Schema
--  Run this once in the Supabase SQL Editor for each new client.
--  Includes tables, indexes, and row-level security policies.
-- ============================================================

-- ── Extensions ───────────────────────────────────────────────────────────────
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ── Users ────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS users (
    id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    role       TEXT NOT NULL CHECK (role IN ('owner', 'tech', 'customer')),
    name       TEXT NOT NULL,
    email      TEXT UNIQUE NOT NULL,
    phone      TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE users ENABLE ROW LEVEL SECURITY;

-- Each user can only read their own row.
-- Owner role can read all rows (via service key in API calls).
CREATE POLICY "users_read_own" ON users
    FOR SELECT USING (auth.uid() = id);

-- ── Properties ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS properties (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    customer_id UUID REFERENCES users(id) ON DELETE SET NULL,
    address     TEXT NOT NULL,
    unit        TEXT,
    city        TEXT NOT NULL DEFAULT 'Nashville',
    state       TEXT NOT NULL DEFAULT 'TN',
    zip         TEXT,
    notes       TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_properties_customer ON properties(customer_id);

ALTER TABLE properties ENABLE ROW LEVEL SECURITY;

CREATE POLICY "properties_owner_full" ON properties
    FOR ALL USING (
        EXISTS (SELECT 1 FROM users WHERE id = auth.uid() AND role = 'owner')
    );

CREATE POLICY "properties_customer_own" ON properties
    FOR SELECT USING (customer_id = auth.uid());

-- ── Work Orders ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS work_orders (
    id                      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    -- Registered customer (nullable — guests use guest_ fields)
    customer_id             UUID REFERENCES users(id) ON DELETE SET NULL,
    -- Guest fields (used when customer_id is null)
    guest_name              TEXT,
    guest_phone             TEXT,
    guest_email             TEXT,
    guest_address           TEXT,
    -- Property (nullable for guests)
    property_id             UUID REFERENCES properties(id) ON DELETE SET NULL,
    -- Assignment
    assigned_tech_id        UUID REFERENCES users(id) ON DELETE SET NULL,
    -- Service details
    service_category        TEXT NOT NULL,
    task_name               TEXT NOT NULL,
    requires_license        BOOLEAN NOT NULL DEFAULT FALSE,
    description             TEXT,
    -- Status lifecycle
    status                  TEXT NOT NULL DEFAULT 'new'
                                CHECK (status IN ('new','scheduled','in_progress','complete','cancelled')),
    job_type                TEXT NOT NULL DEFAULT 'one_off'
                                CHECK (job_type IN ('one_off','reactive')),
    urgency_flag            BOOLEAN NOT NULL DEFAULT FALSE,
    -- Scheduling
    scheduled_date          DATE,
    scheduled_time          TIME,
    estimated_duration_mins INT DEFAULT 60,
    started_at              TIMESTAMPTZ,
    -- Completion
    checklist               JSONB,
    photos                  TEXT[],
    completion_notes        TEXT,
    scope_flag              TEXT,
    -- Billing
    flat_rate               NUMERIC(10,2),
    invoice_id              UUID,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_wo_status          ON work_orders(status);
CREATE INDEX IF NOT EXISTS idx_wo_scheduled_date  ON work_orders(scheduled_date);
CREATE INDEX IF NOT EXISTS idx_wo_assigned_tech   ON work_orders(assigned_tech_id);
CREATE INDEX IF NOT EXISTS idx_wo_urgency         ON work_orders(urgency_flag) WHERE urgency_flag = TRUE;
CREATE INDEX IF NOT EXISTS idx_wo_customer        ON work_orders(customer_id);

ALTER TABLE work_orders ENABLE ROW LEVEL SECURITY;

-- Owner: full access
CREATE POLICY "wo_owner_full" ON work_orders
    FOR ALL USING (
        EXISTS (SELECT 1 FROM users WHERE id = auth.uid() AND role = 'owner')
    );

-- Tech: can read all, but only update their own assigned jobs
CREATE POLICY "wo_tech_read" ON work_orders
    FOR SELECT USING (
        EXISTS (SELECT 1 FROM users WHERE id = auth.uid() AND role = 'tech')
    );

CREATE POLICY "wo_tech_update_own" ON work_orders
    FOR UPDATE USING (assigned_tech_id = auth.uid());

-- ── Invoices ─────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS invoices (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    work_order_id       UUID NOT NULL REFERENCES work_orders(id) ON DELETE CASCADE,
    guest_email         TEXT,
    guest_phone         TEXT,
    amount              NUMERIC(10,2) NOT NULL DEFAULT 0.00,
    status              TEXT NOT NULL DEFAULT 'draft'
                            CHECK (status IN ('draft','sent','paid','overdue')),
    stripe_payment_link TEXT,
    line_items          JSONB,
    sent_at             TIMESTAMPTZ,
    paid_at             TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_invoices_status   ON invoices(status);
CREATE INDEX IF NOT EXISTS idx_invoices_wo       ON invoices(work_order_id);

ALTER TABLE invoices ENABLE ROW LEVEL SECURITY;

-- Owner only for invoices
CREATE POLICY "invoices_owner_full" ON invoices
    FOR ALL USING (
        EXISTS (SELECT 1 FROM users WHERE id = auth.uid() AND role = 'owner')
    );

-- ── Availability ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS availability (
    id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    owner_id          UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    day_of_week       INT NOT NULL CHECK (day_of_week BETWEEN 0 AND 6),
    start_time        TIME NOT NULL,
    end_time          TIME NOT NULL,
    buffer_mins       INT NOT NULL DEFAULT 30,
    max_jobs_per_day  INT NOT NULL DEFAULT 6,
    UNIQUE (owner_id, day_of_week)
);

ALTER TABLE availability ENABLE ROW LEVEL SECURITY;

CREATE POLICY "availability_owner_full" ON availability
    FOR ALL USING (owner_id = auth.uid());

-- Public read for slot computation (service key bypasses RLS anyway)
CREATE POLICY "availability_public_read" ON availability
    FOR SELECT USING (TRUE);

-- ── Blocked Slots ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS blocked_slots (
    id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    owner_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    date       DATE NOT NULL,
    start_time TIME NOT NULL,
    end_time   TIME NOT NULL,
    reason     TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_blocked_date ON blocked_slots(date);

ALTER TABLE blocked_slots ENABLE ROW LEVEL SECURITY;

CREATE POLICY "blocked_owner_full" ON blocked_slots
    FOR ALL USING (owner_id = auth.uid());

CREATE POLICY "blocked_public_read" ON blocked_slots
    FOR SELECT USING (TRUE);

-- ── Seed: default availability (Mon–Fri 8am–5pm, 30 min buffer) ──────────────
-- Replace 'YOUR_OWNER_USER_ID' with the actual owner's user ID after creating
-- their account in Supabase Auth.
--
-- INSERT INTO availability (owner_id, day_of_week, start_time, end_time)
-- VALUES
--   ('YOUR_OWNER_USER_ID', 1, '08:00', '17:00'),  -- Monday
--   ('YOUR_OWNER_USER_ID', 2, '08:00', '17:00'),  -- Tuesday
--   ('YOUR_OWNER_USER_ID', 3, '08:00', '17:00'),  -- Wednesday
--   ('YOUR_OWNER_USER_ID', 4, '08:00', '17:00'),  -- Thursday
--   ('YOUR_OWNER_USER_ID', 5, '08:00', '17:00');  -- Friday
