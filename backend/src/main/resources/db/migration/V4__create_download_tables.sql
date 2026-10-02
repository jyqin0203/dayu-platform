CREATE TABLE download_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    organization_snapshot VARCHAR(255) NOT NULL,
    asset_id BIGINT NOT NULL,
    file_name_snapshot VARCHAR(512) NOT NULL,
    expected_bytes BIGINT NOT NULL,
    purpose TEXT NOT NULL,
    ip_address VARCHAR(45) NOT NULL DEFAULT '',
    user_agent VARCHAR(500) NOT NULL DEFAULT '',
    status VARCHAR(32) NOT NULL DEFAULT 'REQUESTED',
    denial_reason_code VARCHAR(64) NULL,
    requested_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    authorized_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    delivered_bytes BIGINT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_download_status
        CHECK (status IN ('REQUESTED', 'AUTHORIZED', 'DENIED', 'DELIVERED', 'INTERRUPTED')),
    CONSTRAINT ck_download_expected_bytes CHECK (expected_bytes >= 0),
    CONSTRAINT ck_download_delivered_bytes
        CHECK (delivered_bytes IS NULL OR delivered_bytes >= 0),
    CONSTRAINT ck_download_purpose
        CHECK (CHAR_LENGTH(purpose) BETWEEN 10 AND 2000),
    CONSTRAINT fk_download_event_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_download_event_asset
        FOREIGN KEY (asset_id) REFERENCES data_assets (id) ON DELETE RESTRICT,
    INDEX idx_download_events_time_id (requested_at, id),
    INDEX idx_download_events_status_time_id (status, requested_at, id),
    INDEX idx_download_events_user_time_id (user_id, requested_at, id),
    INDEX idx_download_events_asset_time_id (asset_id, requested_at, id),
    INDEX idx_download_events_org_time_id (organization_snapshot, requested_at, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE download_event_products (
    download_event_id BIGINT NOT NULL,
    product_code_snapshot VARCHAR(64) NOT NULL,
    PRIMARY KEY (download_event_id, product_code_snapshot),
    CONSTRAINT fk_download_event_product_event
        FOREIGN KEY (download_event_id) REFERENCES download_events (id) ON DELETE CASCADE,
    INDEX idx_download_event_products_code_event
        (product_code_snapshot, download_event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
