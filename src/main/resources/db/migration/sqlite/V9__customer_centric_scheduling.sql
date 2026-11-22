-- Customer-centric scheduling (MVZ): customers, customer assignments, staffing requirements.
--
-- The existing model only knew shifts (one required employee per row). A medical care centre
-- plans *customer assignments* instead: who treats which customer, where, for how long, and
-- with which team composition. A single assignment therefore generates one shift row per
-- required team member ("position"), which reuses the whole solver, the availability/skill
-- constraints and the existing save path instead of introducing a second planner.
--
-- All new text columns are NOT NULL with a default so the migrations can be applied to a
-- populated database without rewriting existing rows.

CREATE TABLE customers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    structure_id INTEGER NOT NULL DEFAULT 1,
    code TEXT NOT NULL,
    name TEXT NOT NULL,
    address TEXT NOT NULL DEFAULT '',
    phone TEXT NOT NULL DEFAULT '',
    active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
    default_start_time TEXT NOT NULL DEFAULT '08:00',
    default_duration_minutes INTEGER NOT NULL DEFAULT 480,
    default_skill_id INTEGER,
    recurrence_pattern TEXT NOT NULL DEFAULT '',
    restrictions TEXT NOT NULL DEFAULT '',
    UNIQUE (structure_id, code),
    FOREIGN KEY (structure_id) REFERENCES structures(id) ON DELETE CASCADE,
    FOREIGN KEY (default_skill_id) REFERENCES skills(id) ON DELETE SET NULL
);

-- One concrete visit/job for a customer. status: FIXED (time and team are locked),
-- FLEXIBLE (the solver may move the time inside the day and choose the team),
-- COMPLETED, CANCELLED. fixed_time pins the start; a flexible assignment lets the solver
-- only choose people, never the slot, until the planner moves it explicitly.
CREATE TABLE customer_assignments (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    structure_id INTEGER NOT NULL DEFAULT 1,
    customer_id INTEGER NOT NULL,
    location_id INTEGER NOT NULL,
    title TEXT NOT NULL DEFAULT '',
    service TEXT NOT NULL DEFAULT '',
    assignment_date TEXT NOT NULL,
    start_time TEXT NOT NULL DEFAULT '08:00',
    duration_minutes INTEGER NOT NULL DEFAULT 480,
    priority INTEGER NOT NULL DEFAULT 3 CHECK (priority IN (1, 2, 3)),
    status TEXT NOT NULL DEFAULT 'FLEXIBLE',
    fixed_time INTEGER NOT NULL DEFAULT 0 CHECK (fixed_time IN (0, 1)),
    notes TEXT NOT NULL DEFAULT '',
    series_id INTEGER,
    created_at TEXT NOT NULL DEFAULT '',
    FOREIGN KEY (structure_id) REFERENCES structures(id) ON DELETE CASCADE,
    FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE CASCADE,
    FOREIGN KEY (location_id) REFERENCES locations(id) ON DELETE CASCADE
);

CREATE INDEX idx_customer_assignments_window
    ON customer_assignments (structure_id, assignment_date);

-- Required team composition per assignment: role plus optional qualification, repeated
-- headcount times. Each row materialises into one shift row, so "1 anaesthetist + 2 MFAs"
-- becomes three shift rows with different required roles.
CREATE TABLE assignment_requirements (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    assignment_id INTEGER NOT NULL,
    role TEXT NOT NULL DEFAULT 'MEDICAL_ASSISTANT',
    skill_id INTEGER,
    headcount INTEGER NOT NULL DEFAULT 1 CHECK (headcount >= 1),
    FOREIGN KEY (assignment_id) REFERENCES customer_assignments(id) ON DELETE CASCADE,
    FOREIGN KEY (skill_id) REFERENCES skills(id) ON DELETE SET NULL
);

CREATE INDEX idx_assignment_requirements_assignment
    ON assignment_requirements (assignment_id);

-- Configurable travel time matrix between locations. No external routing service is called:
-- the planner maintains the minutes by hand, and a later routing integration only has to fill
-- this table. Symmetric entries are stored twice so lookups stay a single indexed read.
CREATE TABLE travel_times (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    structure_id INTEGER NOT NULL DEFAULT 1,
    from_location_id INTEGER NOT NULL,
    to_location_id INTEGER NOT NULL,
    travel_minutes INTEGER NOT NULL DEFAULT 0 CHECK (travel_minutes >= 0),
    UNIQUE (structure_id, from_location_id, to_location_id),
    FOREIGN KEY (structure_id) REFERENCES structures(id) ON DELETE CASCADE,
    FOREIGN KEY (from_location_id) REFERENCES locations(id) ON DELETE CASCADE,
    FOREIGN KEY (to_location_id) REFERENCES locations(id) ON DELETE CASCADE
);

-- Locations an employee may work at. An employee with no rows is unrestricted (every
-- existing installation keeps working unchanged); adding a row narrows the set.
CREATE TABLE employee_locations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    employee_id INTEGER NOT NULL,
    location_id INTEGER NOT NULL,
    UNIQUE (employee_id, location_id),
    FOREIGN KEY (employee_id) REFERENCES employees(id) ON DELETE CASCADE,
    FOREIGN KEY (location_id) REFERENCES locations(id) ON DELETE CASCADE
);

ALTER TABLE employees ADD COLUMN role TEXT NOT NULL DEFAULT 'MEDICAL_ASSISTANT';
ALTER TABLE employees ADD COLUMN contracted_weekly_hours REAL NOT NULL DEFAULT 40;
ALTER TABLE employees ADD COLUMN employment_type TEXT NOT NULL DEFAULT 'FULL_TIME';

ALTER TABLE locations ADD COLUMN customer_id INTEGER REFERENCES customers(id) ON DELETE SET NULL;
ALTER TABLE locations ADD COLUMN external INTEGER NOT NULL DEFAULT 0;

ALTER TABLE shifts ADD COLUMN assignment_id INTEGER REFERENCES customer_assignments(id) ON DELETE CASCADE;
ALTER TABLE shifts ADD COLUMN required_role TEXT NOT NULL DEFAULT '';
