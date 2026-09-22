-- V5 (prod): Postgres compatible
ALTER TABLE invoice_record ADD COLUMN verification_no     VARCHAR(64);
ALTER TABLE invoice_record ADD COLUMN verifier           VARCHAR(64);
ALTER TABLE invoice_record ADD COLUMN verification_date  DATE;

CREATE INDEX idx_invoice_verification_no ON invoice_record(verification_no);
CREATE INDEX idx_invoice_verifier        ON invoice_record(verifier);
CREATE INDEX idx_invoice_verification_dt ON invoice_record(verification_date);