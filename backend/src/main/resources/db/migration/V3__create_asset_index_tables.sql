CREATE TABLE data_assets (
    id BIGINT NOT NULL AUTO_INCREMENT,
    storage_key VARCHAR(32) NOT NULL,
    relative_path VARCHAR(700) NOT NULL,
    file_name VARCHAR(512) NOT NULL,
    asset_type VARCHAR(32) NOT NULL,
    data_mode VARCHAR(32) NOT NULL,
    cycle_time DATETIME(6) NULL,
    valid_time DATETIME(6) NOT NULL,
    lead_minutes INT NULL,
    file_size BIGINT NOT NULL,
    checksum_sha256 CHAR(64) NULL,
    dpi INT NULL,
    file_modified_at DATETIME(6) NOT NULL,
    indexed_at DATETIME(6) NOT NULL,
    last_seen_at DATETIME(6) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'AVAILABLE',
    PRIMARY KEY (id),
    CONSTRAINT uq_data_assets_storage_path UNIQUE (storage_key, relative_path),
    CONSTRAINT ck_data_assets_type
        CHECK (asset_type IN ('WEBP', 'NETCDF')),
    CONSTRAINT ck_data_assets_mode
        CHECK (data_mode IN ('REALTIME', 'FORECAST')),
    CONSTRAINT ck_data_assets_status
        CHECK (status IN ('AVAILABLE', 'MISSING')),
    CONSTRAINT ck_data_assets_file_size CHECK (file_size >= 0),
    CONSTRAINT ck_data_assets_checksum
        CHECK (checksum_sha256 IS NULL OR CHAR_LENGTH(checksum_sha256) = 64),
    CONSTRAINT ck_data_assets_dpi
        CHECK (dpi IS NULL OR (asset_type = 'WEBP' AND dpi > 0)),
    CONSTRAINT ck_data_assets_time_model
        CHECK (
            (data_mode = 'REALTIME' AND cycle_time IS NULL AND lead_minutes IS NULL)
            OR
            (
                data_mode = 'FORECAST'
                AND cycle_time IS NOT NULL
                AND lead_minutes IS NOT NULL
                AND lead_minutes >= 0
                AND TIMESTAMPADD(MINUTE, lead_minutes, cycle_time) = valid_time
            )
        ),
    INDEX idx_assets_timeline
        (asset_type, data_mode, status, valid_time, id),
    INDEX idx_assets_forecast_cycle
        (asset_type, data_mode, status, cycle_time, valid_time, id),
    INDEX idx_assets_status_last_seen
        (status, last_seen_at, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE data_asset_products (
    asset_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    PRIMARY KEY (asset_id, product_id),
    CONSTRAINT fk_data_asset_product_asset
        FOREIGN KEY (asset_id) REFERENCES data_assets (id) ON DELETE CASCADE,
    CONSTRAINT fk_data_asset_product_product
        FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE RESTRICT,
    INDEX idx_data_asset_products_product_asset (product_id, asset_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE asset_scan_runs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    trigger_type VARCHAR(32) NOT NULL,
    triggered_by_user_id BIGINT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    scanned_files BIGINT NOT NULL DEFAULT 0,
    created_assets BIGINT NOT NULL DEFAULT 0,
    updated_assets BIGINT NOT NULL DEFAULT 0,
    removed_webp_assets BIGINT NOT NULL DEFAULT 0,
    missing_netcdf_assets BIGINT NOT NULL DEFAULT 0,
    error_count INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT ck_asset_scan_trigger
        CHECK (trigger_type IN ('STARTUP', 'SCHEDULED', 'MANUAL')),
    CONSTRAINT ck_asset_scan_status
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED')),
    CONSTRAINT ck_asset_scan_counts
        CHECK (
            scanned_files >= 0
            AND created_assets >= 0
            AND updated_assets >= 0
            AND removed_webp_assets >= 0
            AND missing_netcdf_assets >= 0
            AND error_count >= 0
        ),
    CONSTRAINT ck_asset_scan_finished
        CHECK (
            (status = 'RUNNING' AND finished_at IS NULL)
            OR (status <> 'RUNNING' AND finished_at IS NOT NULL)
        ),
    CONSTRAINT fk_asset_scan_triggered_by
        FOREIGN KEY (triggered_by_user_id) REFERENCES users (id) ON DELETE SET NULL,
    INDEX idx_asset_scan_runs_started_id (started_at, id),
    INDEX idx_asset_scan_runs_trigger_started_id (trigger_type, started_at, id),
    INDEX idx_asset_scan_runs_user_started_id (triggered_by_user_id, started_at, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE asset_scan_errors (
    id BIGINT NOT NULL AUTO_INCREMENT,
    scan_run_id BIGINT NOT NULL,
    relative_path VARCHAR(1024) NOT NULL,
    error_code VARCHAR(64) NOT NULL,
    safe_message VARCHAR(1000) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_asset_scan_error_run
        FOREIGN KEY (scan_run_id) REFERENCES asset_scan_runs (id) ON DELETE CASCADE,
    INDEX idx_asset_scan_errors_run_id (scan_run_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
