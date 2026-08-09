-- Le competenze diventano proprieta' della singola struttura.
--
-- Prima: catalogo globale + ponte structure_skills che decideva solo la visibilita'.
-- Rinominare una competenza la cambiava per tutte le strutture, e il vincolo
-- UNIQUE(name) impediva perfino di avere una "Nurse" distinta in due strutture.
--
-- Ora: skills.structure_id NOT NULL e unicita' del nome PER STRUTTURA.
--
-- Le assegnazioni esistenti (operatori, sedi, turni, template) vengono azzerate:
-- gli identificativi delle competenze cambiano per costruzione, e ricollegarli
-- avrebbe richiesto scelte arbitrarie sui casi ambigui. Rimozione voluta e concordata.

CREATE TABLE skills_new (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    structure_id INTEGER NOT NULL,
    name TEXT NOT NULL,
    skill_order INTEGER,
    active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
    UNIQUE (structure_id, name),
    FOREIGN KEY (structure_id) REFERENCES structures(id) ON DELETE CASCADE
);

-- Una copia del catalogo per ogni struttura: si riparte dallo stesso elenco, ma con
-- righe indipendenti, modificabili e cancellabili senza toccare le altre strutture.
INSERT INTO skills_new (structure_id, name, skill_order, active)
SELECT st.id, s.name, s.skill_order, s.active
FROM structures st
CROSS JOIN skills s;

-- Assegnazioni azzerate: puntavano agli identificativi vecchi.
DELETE FROM employee_skills;
DELETE FROM location_skills;
DELETE FROM shift_skills;
DELETE FROM shift_template_skills;

-- Le traduzioni dei nomi sono indicizzate per identificativo (entity_type='skill',
-- entity_id = id della competenza): con gli id cambiati finirebbero sulla competenza
-- sbagliata. La tabella usa entity_type/entity_id, non una chiave testuale.
DELETE FROM localizzazioni WHERE entity_type = 'skills';

DROP TABLE skills;
ALTER TABLE skills_new RENAME TO skills;

-- Il ponte non serve piu': la struttura e' nella riga stessa.
DROP TABLE IF EXISTS structure_skills;

CREATE INDEX IF NOT EXISTS idx_skills_structure ON skills(structure_id, skill_order);
