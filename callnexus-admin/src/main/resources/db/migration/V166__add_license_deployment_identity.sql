    CREATE TABLE cc_license_deployment_identity (
        singleton_id TINYINT NOT NULL PRIMARY KEY,
        deployment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
        public_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
        public_key_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
        created_at DATETIME NULL,
        CHECK (singleton_id = 1),
        CHECK ((deployment_id IS NULL AND public_key IS NULL AND public_key_fingerprint IS NULL AND created_at IS NULL)
            OR (deployment_id IS NOT NULL AND public_key IS NOT NULL AND public_key_fingerprint IS NOT NULL AND created_at IS NOT NULL))
    ) ENGINE=InnoDB;

    INSERT INTO cc_license_deployment_identity (singleton_id) VALUES (1);
