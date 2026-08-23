CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY,
  secret_hash TEXT NOT NULL,
  display_name TEXT NOT NULL,
  fcm_token TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS groups (
  id TEXT PRIMARY KEY,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS group_members (
  group_id TEXT NOT NULL,
  device_id TEXT NOT NULL,
  joined_at INTEGER NOT NULL,
  PRIMARY KEY (group_id, device_id)
);

CREATE INDEX IF NOT EXISTS idx_members_device ON group_members(device_id);

CREATE TABLE IF NOT EXISTS invites (
  code TEXT PRIMARY KEY,
  creator_id TEXT NOT NULL,
  group_id TEXT,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  used_at INTEGER
);

CREATE TABLE IF NOT EXISTS pairs (
  id TEXT PRIMARY KEY,
  device_a TEXT NOT NULL,
  device_b TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  UNIQUE (device_a, device_b)
);

CREATE INDEX IF NOT EXISTS idx_pairs_a ON pairs(device_a);
CREATE INDEX IF NOT EXISTS idx_pairs_b ON pairs(device_b);

CREATE TABLE IF NOT EXISTS alerts (
  id TEXT PRIMARY KEY,
  pair_id TEXT NOT NULL,
  sender_id TEXT NOT NULL,
  receiver_id TEXT NOT NULL,
  kind TEXT NOT NULL,
  sent_at INTEGER NOT NULL,
  received_at INTEGER,
  acked_at INTEGER
);

CREATE INDEX IF NOT EXISTS idx_alerts_pair ON alerts(pair_id, sent_at DESC);
CREATE INDEX IF NOT EXISTS idx_alerts_receiver ON alerts(receiver_id, sent_at DESC);

CREATE TABLE IF NOT EXISTS schedules (
  id TEXT PRIMARY KEY,
  pair_id TEXT NOT NULL,
  sender_id TEXT NOT NULL,
  receiver_id TEXT NOT NULL,
  hour INTEGER NOT NULL,
  minute INTEGER NOT NULL,
  timezone TEXT NOT NULL,
  days_mask INTEGER NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 1,
  last_fired_date TEXT,
  created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_schedules_enabled ON schedules(enabled);
