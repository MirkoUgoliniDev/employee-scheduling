-- Utenti applicativi che possono autenticarsi.
--
-- Due soli ruoli: ADMIN (configurazione, backup, utenti) e CAPOSALA (turni, operatori,
-- anagrafiche). Il ruolo non si chiama "operatore" perche' in questo dominio *Operatore* e'
-- gia' la persona schedulata.
--
-- La password e' conservata solo come hash bcrypt: 'password_hash' non deve MAI contenere
-- testo in chiaro. Le date usano TEXT come tutto il resto dello schema SQLite.
CREATE TABLE IF NOT EXISTS app_users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL,
    active INTEGER NOT NULL DEFAULT 1,
    display_name TEXT,
    created_at TEXT NOT NULL,
    last_login_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_app_users_username ON app_users(username);
