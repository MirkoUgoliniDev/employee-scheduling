-- Aggiunge la colonna email agli utenti applicativi (registrazione via OTP).
-- Deve restare allineata alla migrazione SQLite corrispondente (MigrationSchemaParityTest).
-- Nullable per gli utenti esistenti creati da ADMIN senza email; unica dove valorizzata.
ALTER TABLE app_users ADD COLUMN email TEXT;
CREATE UNIQUE INDEX idx_app_users_email ON app_users(email);
