CREATE TABLE IF NOT EXISTS app_meta (
    meta_key VARCHAR(100) PRIMARY KEY,
    meta_value VARCHAR(500) NOT NULL
);
CREATE TABLE IF NOT EXISTS knowledge_bases (
    id VARCHAR(80) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    color VARCHAR(60) NOT NULL,
    icon VARCHAR(40) NOT NULL,
    updated_at VARCHAR(40) NOT NULL,
    visibility VARCHAR(20) NOT NULL,
    tags CLOB NOT NULL
);
CREATE TABLE IF NOT EXISTS documents (
    id VARCHAR(80) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    kb_id VARCHAR(80) NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    doc_type VARCHAR(10) NOT NULL,
    size_bytes BIGINT NOT NULL,
    chunk_count INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    updated_at VARCHAR(40) NOT NULL,
    visibility VARCHAR(20) NOT NULL,
    content CLOB NOT NULL
);
CREATE INDEX IF NOT EXISTS documents_kb_idx ON documents(kb_id);
CREATE TABLE IF NOT EXISTS workspace_settings (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    settings_json CLOB NOT NULL
);
CREATE TABLE IF NOT EXISTS chat_sessions (
    id VARCHAR(80) PRIMARY KEY,
    title VARCHAR(120) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    updated_at VARCHAR(40) NOT NULL,
    messages_json CLOB NOT NULL
);
CREATE TABLE IF NOT EXISTS activities (
    id VARCHAR(80) PRIMARY KEY,
    activity_type VARCHAR(20) NOT NULL,
    title VARCHAR(250) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    activity_time VARCHAR(40) NOT NULL
);
CREATE TABLE IF NOT EXISTS daily_stats (
    stat_date DATE PRIMARY KEY,
    query_count BIGINT NOT NULL,
    success_count BIGINT NOT NULL,
    token_count BIGINT NOT NULL,
    latency_total DOUBLE PRECISION NOT NULL
);
