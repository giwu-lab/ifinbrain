-- Cross-dialect RBAC schema. SQLite/Postgres compatible.

CREATE TABLE sys_user (
  id              INTEGER     PRIMARY KEY AUTOINCREMENT,
  username        VARCHAR(64) NOT NULL UNIQUE,
  password_hash   VARCHAR(255) NOT NULL,
  display_name    VARCHAR(128),
  email           VARCHAR(128),
  phone           VARCHAR(32),
  status          SMALLINT    NOT NULL DEFAULT 1,
  last_login_at   TIMESTAMP,
  created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sys_role (
  id          INTEGER     PRIMARY KEY AUTOINCREMENT,
  code        VARCHAR(64) NOT NULL UNIQUE,
  name        VARCHAR(128) NOT NULL,
  remark      VARCHAR(255),
  status      SMALLINT    NOT NULL DEFAULT 1,
  created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sys_permission (
  id          INTEGER     PRIMARY KEY AUTOINCREMENT,
  code        VARCHAR(128) NOT NULL UNIQUE,
  name        VARCHAR(128) NOT NULL,
  type        VARCHAR(16) NOT NULL,
  created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sys_menu (
  id          INTEGER     PRIMARY KEY AUTOINCREMENT,
  parent_id   INTEGER     NOT NULL DEFAULT 0,
  name        VARCHAR(64) NOT NULL,
  path        VARCHAR(255),
  component   VARCHAR(255),
  icon        VARCHAR(64),
  sort        INTEGER     NOT NULL DEFAULT 0,
  permission  VARCHAR(128),
  type        VARCHAR(16) NOT NULL,
  status      SMALLINT    NOT NULL DEFAULT 1,
  created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sys_user_role (
  user_id     INTEGER NOT NULL,
  role_id     INTEGER NOT NULL,
  PRIMARY KEY (user_id, role_id)
);

CREATE TABLE sys_role_perm (
  role_id     INTEGER NOT NULL,
  perm_id     INTEGER NOT NULL,
  PRIMARY KEY (role_id, perm_id)
);

CREATE TABLE sys_refresh_token (
  id           INTEGER      PRIMARY KEY AUTOINCREMENT,
  user_id      INTEGER      NOT NULL,
  token_hash   VARCHAR(128) NOT NULL UNIQUE,
  expires_at   TIMESTAMP    NOT NULL,
  revoked      SMALLINT     NOT NULL DEFAULT 0,
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_refresh_user ON sys_refresh_token(user_id);

CREATE TABLE sys_setting (
  key_name    VARCHAR(128) PRIMARY KEY,
  value_text  TEXT,
  updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE invoice_record (
  id                  INTEGER      PRIMARY KEY AUTOINCREMENT,
  storage_key         VARCHAR(512) NOT NULL,
  storage_type        VARCHAR(16)  NOT NULL,
  original_filename   VARCHAR(255) NOT NULL,
  size_bytes          BIGINT       NOT NULL,
  sha256              CHAR(64)     NOT NULL,
  invoice_type        VARCHAR(128),
  invoice_number      VARCHAR(64),
  issue_date          DATE,
  buyer_name          VARCHAR(255),
  buyer_tax_id        VARCHAR(64),
  seller_name         VARCHAR(255),
  seller_tax_id       VARCHAR(64),
  amount_without_tax  DECIMAL(18,2),
  tax_amount          DECIMAL(18,2),
  total_amount        DECIMAL(18,2),
  total_amount_upper  VARCHAR(255),
  payee               VARCHAR(64),
  reviewer            VARCHAR(64),
  drawer              VARCHAR(64),
  message             VARCHAR(255),
  uploader_id         INTEGER,
  created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_invoice_seller     ON invoice_record(seller_name);
CREATE INDEX idx_invoice_invoice_no ON invoice_record(invoice_number);
CREATE INDEX idx_invoice_uploader   ON invoice_record(uploader_id);
CREATE INDEX idx_invoice_sha256     ON invoice_record(sha256);