CREATE TABLE sqlite_sequence(name,seq);
CREATE TABLE count_distribution (id INTEGER PRIMARY KEY AUTOINCREMENT, count INTEGER NOT NULL, weight REAL NOT NULL, type TEXT NOT NULL);
CREATE TABLE demo_data_parameters (id INTEGER PRIMARY KEY AUTOINCREMENT, days_in_schedule INTEGER NOT NULL, employee_count INTEGER NOT NULL, random_seed INTEGER NOT NULL);
CREATE TABLE employees (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    code TEXT NOT NULL UNIQUE,
    first_name TEXT NOT NULL,
    last_name TEXT NOT NULL,
    email TEXT NOT NULL DEFAULT ''
, structure_id INTEGER NOT NULL DEFAULT 1);
CREATE TABLE IF NOT EXISTS "locations" (
	"id"	INTEGER,
	"name"	TEXT NOT NULL,
	"l_order"	INTEGER, code TEXT, structure_id INTEGER NOT NULL DEFAULT 1,
	PRIMARY KEY("id" AUTOINCREMENT)
);
CREATE TABLE IF NOT EXISTS "employee_date_type" (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    description TEXT
);
CREATE TABLE IF NOT EXISTS "employee_dates" (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    employee_id INTEGER NOT NULL,
    date_start DATETIME NOT NULL,
    date_end DATETIME NOT NULL,
    date_type_id INTEGER NOT NULL,
    FOREIGN KEY (employee_id) REFERENCES employees(id) ON DELETE CASCADE,
    FOREIGN KEY (date_type_id) REFERENCES employee_date_type(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS "skill_type" (
	"id"	INTEGER,
	"description"	TEXT,
	PRIMARY KEY("id" AUTOINCREMENT)
);
CREATE TABLE IF NOT EXISTS "skills" (
    id INTEGER PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    skill_order INTEGER,

    active INTEGER NOT NULL DEFAULT 1
);
CREATE TABLE IF NOT EXISTS "employee_skills" (
	"id"	INTEGER,
	"employee_id"	INTEGER NOT NULL,
	"skill_id"	INTEGER NOT NULL,
	PRIMARY KEY("id" AUTOINCREMENT),
	FOREIGN KEY("employee_id") REFERENCES "employees"("id") ON DELETE CASCADE,
	FOREIGN KEY("skill_id") REFERENCES "skills"("id") ON DELETE CASCADE
);
CREATE TABLE location_skills (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    location_id INT NOT NULL,
    skill_id INT NOT NULL,
    skill_type_id INT NOT NULL,
    FOREIGN KEY (location_id) REFERENCES locations(id) ON DELETE CASCADE,
    FOREIGN KEY (skill_id) REFERENCES skills(id) ON DELETE CASCADE,
    FOREIGN KEY (skill_type_id) REFERENCES skill_type(id) ON DELETE CASCADE
);
CREATE TABLE shift_skills (
    id INTEGER PRIMARY KEY AUTOINCREMENT, -- Configurazione corretta per l'auto-increment
    shift_id INT NOT NULL,
    skill_id INT NOT NULL,
    skill_type_id INT NOT NULL,
    FOREIGN KEY (skill_id) REFERENCES skills(id) ON DELETE CASCADE,
    FOREIGN KEY (shift_id) REFERENCES shifts(id) ON DELETE CASCADE,
    FOREIGN KEY (skill_type_id) REFERENCES skill_type(id) ON DELETE CASCADE
);
CREATE TABLE shifts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    location_id INT NOT NULL,
    start_time DATETIME NOT NULL,
    end_time DATETIME NOT NULL
, employee_id INTEGER, pinned INTEGER NOT NULL DEFAULT 0);
CREATE UNIQUE INDEX idx_locations_code ON locations(code);
CREATE TABLE languages (  id INTEGER PRIMARY KEY AUTOINCREMENT,  code TEXT NOT NULL UNIQUE,  description TEXT NOT NULL,  active INTEGER NOT NULL DEFAULT 0);
CREATE TABLE labels (  id INTEGER PRIMARY KEY AUTOINCREMENT,  key TEXT NOT NULL UNIQUE,  description TEXT NOT NULL);
CREATE TABLE localizzazioni (  id INTEGER PRIMARY KEY AUTOINCREMENT,  entity_type TEXT NOT NULL,  entity_id   INTEGER NOT NULL,  field_name  TEXT NOT NULL,  language_id INTEGER NOT NULL,  value       TEXT NOT NULL,  UNIQUE(entity_type, entity_id, field_name, language_id));
CREATE TABLE structures (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, address TEXT NOT NULL DEFAULT '', phone TEXT NOT NULL DEFAULT '');
CREATE INDEX idx_employees_structure ON employees(structure_id);
CREATE INDEX idx_locations_structure ON locations(structure_id);
CREATE INDEX idx_shifts_location_start_end ON shifts(location_id, start_time, end_time);
CREATE INDEX idx_shift_skills_shift_type_skill ON shift_skills(shift_id, skill_type_id, skill_id);
CREATE INDEX idx_location_skills_location_type_skill ON location_skills(location_id, skill_type_id, skill_id);
CREATE INDEX idx_employee_skills_employee_skill ON employee_skills(employee_id, skill_id);
CREATE INDEX idx_employee_dates_employee_type_start ON employee_dates(employee_id, date_type_id, date_start);

CREATE TABLE email_templates (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    structure_id INTEGER NOT NULL UNIQUE,
    subject TEXT NOT NULL DEFAULT '',
    body TEXT NOT NULL DEFAULT '',
    FOREIGN KEY (structure_id) REFERENCES structures(id) ON DELETE CASCADE
);

CREATE TABLE pdf_templates (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    structure_id INTEGER NOT NULL UNIQUE,
    header_text TEXT NOT NULL DEFAULT '',
    footer_text TEXT NOT NULL DEFAULT '',
    logo_data_url TEXT NOT NULL DEFAULT '',
    primary_color TEXT NOT NULL DEFAULT '#2980B9',
    FOREIGN KEY (structure_id) REFERENCES structures(id) ON DELETE CASCADE
);

CREATE TABLE solver_settings (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    structure_id INTEGER NOT NULL UNIQUE,
    max_solve_seconds INTEGER NOT NULL DEFAULT 30,
    unimproved_seconds INTEGER NOT NULL DEFAULT 0,
    minimum_rest_hours INTEGER NOT NULL DEFAULT 10,
    max_shifts_per_day INTEGER NOT NULL DEFAULT 1,
    desired_date_weight INTEGER NOT NULL DEFAULT 1,
    undesired_date_weight INTEGER NOT NULL DEFAULT 1,
    balance_weight INTEGER NOT NULL DEFAULT 1,
    optional_skill_weight INTEGER NOT NULL DEFAULT 1,
    balance_by_hours INTEGER NOT NULL DEFAULT 1,
    max_weekly_hours INTEGER NOT NULL DEFAULT 0,
    min_weekly_shifts INTEGER NOT NULL DEFAULT 0,
    max_weekly_shifts INTEGER NOT NULL DEFAULT 0,
    max_consecutive_days INTEGER NOT NULL DEFAULT 0,
    min_days_off_per_week INTEGER NOT NULL DEFAULT 0,
    allow_unassigned INTEGER NOT NULL DEFAULT 0,
    unassigned_weight INTEGER NOT NULL DEFAULT 10,
    same_location_weight INTEGER NOT NULL DEFAULT 0,
    night_balance_weight INTEGER NOT NULL DEFAULT 0,
    night_start_hour INTEGER NOT NULL DEFAULT 22,
    night_end_hour INTEGER NOT NULL DEFAULT 6,
    stop_when_feasible INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (structure_id) REFERENCES structures(id) ON DELETE CASCADE
);

CREATE TABLE email_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    structure_id INTEGER NOT NULL,
    employee_id INTEGER NOT NULL,
    period_slug TEXT NOT NULL,
    period_label TEXT NOT NULL DEFAULT '',
    sent_to TEXT NOT NULL DEFAULT '',
    filename TEXT NOT NULL DEFAULT '',
    sent_at TEXT NOT NULL,
    UNIQUE(structure_id, employee_id, period_slug),
    FOREIGN KEY (structure_id) REFERENCES structures(id) ON DELETE CASCADE,
    FOREIGN KEY (employee_id) REFERENCES employees(id) ON DELETE CASCADE
);

CREATE INDEX idx_email_log_structure_period ON email_log(structure_id, period_slug);
