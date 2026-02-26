-- Registered event sources (for source allowlisting)
CREATE TABLE source_registration (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_uri       VARCHAR NOT NULL UNIQUE,
    display_name     VARCHAR NOT NULL,
    description      TEXT,
    active           BOOLEAN NOT NULL DEFAULT true,
    api_key_hash     VARCHAR,
    allowed_types    JSONB DEFAULT '[]',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_source_registration_uri    ON source_registration (source_uri);
CREATE INDEX idx_source_registration_active ON source_registration (active);
