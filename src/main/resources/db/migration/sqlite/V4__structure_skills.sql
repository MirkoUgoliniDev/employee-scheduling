-- Bridge N:N tra structures e skills (skills resta il catalogo globale).
-- La presenza della riga rappresenta l'abilitazione della skill per quella struttura.
CREATE TABLE IF NOT EXISTS structure_skills (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    structure_id INTEGER NOT NULL,
    skill_id INTEGER NOT NULL,
    UNIQUE (structure_id, skill_id),
    FOREIGN KEY (structure_id) REFERENCES structures(id) ON DELETE CASCADE,
    FOREIGN KEY (skill_id) REFERENCES skills(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_structure_skills_structure_skill
    ON structure_skills(structure_id, skill_id);

-- Rollout sicuro: abilita tutte le skill esistenti per tutte le structures esistenti.
INSERT OR IGNORE INTO structure_skills (structure_id, skill_id)
SELECT s.id, sk.id FROM structures s CROSS JOIN skills sk;
