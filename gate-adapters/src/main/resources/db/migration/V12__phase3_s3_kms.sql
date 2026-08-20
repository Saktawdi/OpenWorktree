-- Phase3: S3 object tracking and KMS key audit (local mock, PG-compatible)

CREATE TABLE IF NOT EXISTS s3_object (
    s3_key TEXT PRIMARY KEY,
    version_id TEXT NOT NULL,
    sha256 TEXT NOT NULL,
    size_bytes INTEGER NOT NULL,
    etag TEXT NOT NULL,
    created_at TEXT NOT NULL,
    deleted_at TEXT
);
CREATE INDEX IF NOT EXISTS ix_s3_object_created ON s3_object(created_at);

CREATE TABLE IF NOT EXISTS kms_key (
    key_id TEXT PRIMARY KEY,
    algorithm TEXT NOT NULL,
    created_at TEXT NOT NULL,
    revoked_at TEXT,
    expires_at TEXT
);
