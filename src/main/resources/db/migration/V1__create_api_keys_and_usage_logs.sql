-- V1__create_api_keys_and_usage_logs.sql
-- Enables pgcrypto extension for gen_random_uuid() compatibility
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 1. Virtual API Keys Table
CREATE TABLE api_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    key_hash VARCHAR(64) NOT NULL,
    name VARCHAR(100) NOT NULL,
    budget_inr NUMERIC(14, 6) NOT NULL CHECK (budget_inr >= 0),
    spent_inr NUMERIC(14, 6) NOT NULL DEFAULT 0.000000 CHECK (spent_inr >= 0),
    reserved_inr NUMERIC(14, 6) NOT NULL DEFAULT 0.000000 CHECK (reserved_inr >= 0),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Unique index for O(log N) key authentication lookup
CREATE UNIQUE INDEX idx_api_keys_key_hash ON api_keys(key_hash);

-- 2. Immutable Usage Logs Table
CREATE TABLE usage_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    api_key_id UUID NOT NULL REFERENCES api_keys(id) ON DELETE RESTRICT,
    model VARCHAR(100) NOT NULL,
    input_tokens INT NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    output_tokens INT NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    cost_inr NUMERIC(14, 6) NOT NULL DEFAULT 0.000000 CHECK (cost_inr >= 0),
    provider VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Index for performant spend history queries (GET /usage?key=...)
CREATE INDEX idx_usage_logs_api_key_created ON usage_logs(api_key_id, created_at DESC);

