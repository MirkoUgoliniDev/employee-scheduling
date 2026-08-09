-- Contatore di revisione dei turni.
--
-- Serve a riconoscere che un turno e' cambiato fra il momento in cui il solver lo ha letto
-- e il momento in cui l'utente salva la soluzione: senza, l'operatore veniva assegnato a un
-- turno con orario o requisiti diversi da quelli su cui il solver aveva ragionato, in
-- silenzio e senza alcun errore.
--
-- Lo incrementa Hibernate a ogni scrittura ORM del turno (@Version). Le righe esistenti
-- partono da 0.
ALTER TABLE shifts ADD COLUMN version INTEGER NOT NULL DEFAULT 0;
