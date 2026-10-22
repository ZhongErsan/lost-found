ALTER TABLE lost_register
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'SEARCHING',
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN recovered_at DATETIME NULL;

ALTER TABLE lost_property
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN returned_at DATETIME NULL;

CREATE INDEX idx_lost_register_user_status
    ON lost_register (user_id, status, id);

CREATE INDEX idx_lost_property_status
    ON lost_property (status, id);

CREATE TABLE item_claim (
                            id BIGINT NOT NULL AUTO_INCREMENT,
                            lost_property_id BIGINT NOT NULL COMMENT '拾物登记编号',
                            lost_register_id BIGINT NOT NULL COMMENT '申请人自己的丢失登记编号',
                            claimant_user_id BIGINT NOT NULL COMMENT '申请认领的用户',

                            verification_text TEXT NOT NULL COMMENT '认领依据',

                            status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
                            reject_reason VARCHAR(255) NULL,

                            approved_at DATETIME NULL,
                            handover_at DATETIME NULL,
                            completed_at DATETIME NULL,

                            created_date DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            updated_date DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                                ON UPDATE CURRENT_TIMESTAMP,

                            PRIMARY KEY (id),
                            KEY idx_claim_property_status (lost_property_id, status),
                            KEY idx_claim_register_status (lost_register_id, status),
                            KEY idx_claimant_recent (claimant_user_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;