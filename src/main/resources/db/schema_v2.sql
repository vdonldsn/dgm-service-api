-- ============================================================
--  DGM Service API — v2 Schema (PM Module)
--  Run AFTER schema.sql (v1) is already applied.
--  Adds: pm_accounts, task_templates, maintenance_contracts
--  and foreign key updates to work_orders.
-- ============================================================

-- ── PM Accounts ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS pm_accounts (
    id                   UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    company_name         TEXT NOT NULL,
    contact_name         TEXT NOT NULL,
    email                TEXT UNIQUE NOT NULL,
    phone                TEXT NOT NULL,
    billing_address      TEXT,
    auto_invoice_default BOOLEAN NOT NULL DEFAULT FALSE,
    notes                TEXT,
    active               BOOLEAN NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_pm_accounts_active ON pm_accounts(active);

ALTER TABLE pm_accounts ENABLE ROW LEVEL SECURITY;

CREATE POLICY "pm_accounts_owner_full" ON pm_accounts
    FOR ALL USING (
        EXISTS (SELECT 1 FROM users WHERE id = auth.uid() AND role = 'owner')
    );

-- ── Task Templates ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS task_templates (
    id                      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name                    TEXT NOT NULL,
    service_category        TEXT NOT NULL,
    description             TEXT,
    checklist               JSONB,          -- ordered array of checklist item strings
    estimated_duration_mins INT DEFAULT 60,
    default_flat_rate       NUMERIC(10,2),
    requires_license        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE task_templates ENABLE ROW LEVEL SECURITY;

CREATE POLICY "task_templates_owner_full" ON task_templates
    FOR ALL USING (
        EXISTS (SELECT 1 FROM users WHERE id = auth.uid() AND role = 'owner')
    );

-- Techs can read templates (to get checklists on job detail)
CREATE POLICY "task_templates_tech_read" ON task_templates
    FOR SELECT USING (
        EXISTS (SELECT 1 FROM users WHERE id = auth.uid() AND role = 'tech')
    );

-- ── Maintenance Contracts ─────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS maintenance_contracts (
    id                      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    pm_account_id           UUID NOT NULL REFERENCES pm_accounts(id) ON DELETE CASCADE,
    property_id             UUID NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    unit_id                 TEXT,           -- specific unit number; null = whole property
    task_template_id        UUID REFERENCES task_templates(id) ON DELETE SET NULL,
    task_name               TEXT NOT NULL,  -- denormalized from template
    service_category        TEXT NOT NULL,

    -- Cadence
    cadence_type            TEXT NOT NULL
                                CHECK (cadence_type IN (
                                    'DAILY','WEEKLY','MONTHLY',
                                    'QUARTERLY','SEASONAL','ANNUAL','TRIGGER'
                                )),
    cadence_interval_value  INT DEFAULT 1,
    day_of_week             INT CHECK (day_of_week BETWEEN 0 AND 6),
    day_of_month            INT CHECK (day_of_month BETWEEN 1 AND 31),
    seasonal_month          TEXT CHECK (seasonal_month IN ('JAN','APR','JUL','OCT')),
    start_date              DATE NOT NULL DEFAULT CURRENT_DATE,
    end_date                DATE,           -- null = indefinite
    next_due_date           DATE,           -- scheduler reads this field
    last_generated_date     DATE,           -- idempotency guard

    -- Pricing
    pricing_type            TEXT NOT NULL DEFAULT 'FLAT_RATE'
                                CHECK (pricing_type IN ('FLAT_RATE','TIME_AND_MATERIAL')),
    flat_rate               NUMERIC(10,2),
    hourly_rate             NUMERIC(10,2),
    estimated_duration_mins INT DEFAULT 60,

    -- Behavior
    auto_invoice_on_close   BOOLEAN NOT NULL DEFAULT FALSE,
    requires_photo_on_close BOOLEAN NOT NULL DEFAULT TRUE,
    active                  BOOLEAN NOT NULL DEFAULT TRUE,
    notes                   TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_contracts_pm          ON maintenance_contracts(pm_account_id);
CREATE INDEX IF NOT EXISTS idx_contracts_property    ON maintenance_contracts(property_id);
CREATE INDEX IF NOT EXISTS idx_contracts_next_due    ON maintenance_contracts(next_due_date);
CREATE INDEX IF NOT EXISTS idx_contracts_active      ON maintenance_contracts(active) WHERE active = TRUE;

ALTER TABLE maintenance_contracts ENABLE ROW LEVEL SECURITY;

CREATE POLICY "contracts_owner_full" ON maintenance_contracts
    FOR ALL USING (
        EXISTS (SELECT 1 FROM users WHERE id = auth.uid() AND role = 'owner')
    );

-- ── work_orders: add v2 foreign keys ─────────────────────────────────────────
-- These columns are added to the existing work_orders table
ALTER TABLE work_orders
    ADD COLUMN IF NOT EXISTS contract_id   UUID REFERENCES maintenance_contracts(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS pm_account_id UUID REFERENCES pm_accounts(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS unit_id       TEXT,
    ADD COLUMN IF NOT EXISTS auto_invoice_on_close   BOOLEAN DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS requires_photo_on_close BOOLEAN DEFAULT TRUE;

CREATE INDEX IF NOT EXISTS idx_wo_contract    ON work_orders(contract_id);
CREATE INDEX IF NOT EXISTS idx_wo_pm_account  ON work_orders(pm_account_id);

-- ── properties: add PM account link ──────────────────────────────────────────
ALTER TABLE properties
    ADD COLUMN IF NOT EXISTS pm_account_id UUID REFERENCES pm_accounts(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_properties_pm ON properties(pm_account_id);

-- ── Seed: default task templates ──────────────────────────────────────────────
INSERT INTO task_templates (name, service_category, estimated_duration_mins,
                            default_flat_rate, checklist)
VALUES
(
  'HVAC filter replacement',
  'PREVENTIVE_MAINTENANCE',
  30,
  65.00,
  '["Locate filter access panel","Check current filter condition and note size","Replace filter with correct size","Dispose of old filter","Check thermostat operation","Log filter size and brand for next visit"]'::jsonb
),
(
  'Smoke and CO detector check',
  'PREVENTIVE_MAINTENANCE',
  30,
  55.00,
  '["Test each smoke detector with test button","Test each CO detector","Replace batteries in all units","Note any detector over 10 years old for replacement","Log serial numbers and locations"]'::jsonb
),
(
  'Seasonal exterior inspection',
  'PREVENTIVE_MAINTENANCE',
  60,
  85.00,
  '["Inspect gutters and downspouts","Check caulking around windows and doors","Inspect weatherstripping on all exterior doors","Check hose bibs and outdoor faucets","Inspect fence and gate hardware","Photograph any issues found","Submit report to PM"]'::jsonb
),
(
  'Unit make-ready / turn',
  'GENERAL_REPAIRS',
  240,
  NULL,  -- quote on site for unit turns
  '["Walk through and document all issues with photos","Patch any drywall holes","Touch up paint as needed","Clean and inspect all appliances","Test all outlets and light switches","Check all faucets and fixtures for leaks","Replace any burnt-out bulbs","Clean HVAC filter","Test smoke and CO detectors","Final walk-through sign-off"]'::jsonb
),
(
  'Water heater maintenance check',
  'PLUMBING_ADJACENT',
  90,
  110.00,
  '["Check temperature setting (recommended 120F)","Inspect anode rod if accessible","Flush sediment — connect hose to drain valve","Check pressure relief valve operation","Inspect flue connection on gas units","Look for any signs of corrosion or leaks","Document water heater age and model"]'::jsonb
);

-- ── Updated timestamp trigger ──────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_contracts_updated_at
    BEFORE UPDATE ON maintenance_contracts
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
