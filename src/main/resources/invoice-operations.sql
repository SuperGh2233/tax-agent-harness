CREATE TABLE IF NOT EXISTS invoice_operations (
 operation_id TEXT PRIMARY KEY NOT NULL,
 user_id TEXT NOT NULL REFERENCES users(id),
 enterprise_id TEXT NOT NULL,
 invoice_id TEXT NOT NULL,
 payload TEXT NOT NULL,
 purpose TEXT NOT NULL,
 remarks TEXT NOT NULL,
 FOREIGN KEY(enterprise_id,invoice_id) REFERENCES invoices(enterprise_id,id)
);
