-- Validated against the isolated real-data snapshot in docs/performance/nc-search-20261007.
-- Supports forecast lead filtering while retaining time/id ordering; no rows or existing indexes change.
CREATE INDEX idx_assets_type_mode_status_lead_time
    ON data_assets (asset_type, data_mode, status, lead_minutes, valid_time, id);
