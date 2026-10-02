CREATE TABLE products (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    family VARCHAR(64) NOT NULL,
    name_zh VARCHAR(255) NOT NULL,
    name_en VARCHAR(255) NOT NULL,
    unit VARCHAR(64) NULL,
    description_zh TEXT NOT NULL,
    description_en TEXT NOT NULL,
    producer VARCHAR(255) NOT NULL,
    algorithm_name VARCHAR(255) NULL,
    source_description TEXT NOT NULL,
    official_source_url VARCHAR(1024) NULL,
    colorbar_required BOOLEAN NOT NULL DEFAULT FALSE,
    colorbar_path VARCHAR(512) NULL,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    published_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_products_code UNIQUE (code),
    CONSTRAINT ck_products_status
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'DISABLED')),
    CONSTRAINT ck_products_colorbar
        CHECK (
            status = 'DRAFT'
            OR colorbar_required = FALSE
            OR (colorbar_path IS NOT NULL AND CHAR_LENGTH(TRIM(colorbar_path)) > 0)
        ),
    CONSTRAINT ck_products_published_at
        CHECK (
            (status = 'DRAFT' AND published_at IS NULL)
            OR (status IN ('PUBLISHED', 'DISABLED') AND published_at IS NOT NULL)
        ),
    CONSTRAINT fk_products_created_by
        FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_products_updated_by
        FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE SET NULL,
    INDEX idx_products_status_sort_id (status, sort_order, id),
    INDEX idx_products_family_status_id (family, status, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE product_mode_policies (
    product_id BIGINT NOT NULL,
    data_mode VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    stale_after_minutes INT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (product_id, data_mode),
    CONSTRAINT ck_product_mode_data_mode
        CHECK (data_mode IN ('REALTIME', 'FORECAST')),
    CONSTRAINT ck_product_mode_stale
        CHECK (stale_after_minutes > 0),
    CONSTRAINT fk_product_mode_product
        FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE RESTRICT,
    INDEX idx_product_modes_enabled_mode (enabled, data_mode, product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE product_admin_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    admin_user_id BIGINT NULL,
    action VARCHAR(32) NOT NULL,
    before_json JSON NULL,
    after_json JSON NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT ck_product_admin_action
        CHECK (action IN ('CREATE', 'UPDATE', 'PUBLISH', 'DISABLE', 'REPUBLISH')),
    CONSTRAINT fk_product_admin_event_product
        FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE RESTRICT,
    CONSTRAINT fk_product_admin_event_admin
        FOREIGN KEY (admin_user_id) REFERENCES users (id) ON DELETE SET NULL,
    INDEX idx_product_events_product_time_id (product_id, created_at, id),
    INDEX idx_product_events_admin_time_id (admin_user_id, created_at, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
