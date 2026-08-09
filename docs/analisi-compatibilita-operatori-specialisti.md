# Analisi: Compatibilità Operatori ↔ Specialisti

> **Stato:** analisi approfondita v2 — **verificata sul codice reale**. Nessuna implementazione ancora.
> **Data:** 2026-07-08 (v2) · v1: 2026-07-07.
> **Contesto:** già realizzati l'entità *Specialisti* (medici dell'ambulatorio, tabella `specialists`) e l'assegnazione *Specialista → Sede* (`locations.specialist_id`). Questo documento analizza il passo successivo: le **(in)compatibilità di carattere** tra operatori e specialisti, e come farle rispettare al solver.
>
> **Novità v2:** corregge un'imprecisione della v1 sulla denormalizzazione delle skill (§3) e ancora ogni intervento a metodi/righe reali del codice (§12).

---

## 1. Il problema (in una frase)

Oltre alle **competenze** (chi *sa fare* cosa), nella realtà esistono **incompatibilità personali**: alcuni operatori si rifiutano di lavorare sotto un certo specialista. Va modellato e fatto rispettare dal **solver**.

---

## 2. Concetto chiave: competenza ≠ compatibilità

| | Competenza (skill) | Compatibilità |
|---|---|---|
| Natura | **Unaria** (una persona) | **Binaria** (una coppia) |
| Esempio | "Aicha sa fare Radiology" | "Aicha non lavora col dott. Rossi" |
| Match | Sede *richiede* ↔ operatore *possiede* | Proprietà della **coppia** operatore–specialista |
| Modello | Catalogo skill + flag `used` | **Relazione** a sé stante |

**Conclusione:** NON modellarla come skill (esploderebbe il catalogo e sporcherebbe la semantica). È una **relazione binaria** operatore↔specialista — il caso *"conflicting employees"* dei sample Timefold, gestito nativamente.

---

## 3. Come si innesta nel modello attuale — ⚠️ correzione rispetto alla v1

La catena logica è pulita:

```
Operatore ──assegnato a──▶ Turno ──appartiene a──▶ Sede ──ha──▶ Specialista
```

Un operatore che copre un turno lavora **sotto lo specialista della sede di quel turno**. Regola:

> se lo **specialista della sede del turno** è incompatibile con l'**operatore** candidato → turno vietato (hard) o penalizzato (soft).

### 3.1 Come funziona DAVVERO la denormalizzazione (verificato)

La v1 diceva che il `Shift` «porta già denormalizzate le skill della sede». **Non è così.** Il codice reale:

- Le skill del turno sono **proprie del turno**, salvate in `shift_skills` e idratate in blocco da `hydrateShiftSkills()` — vedi [DemoDataRepository.java:894-922](src/main/java/org/acme/employeescheduling/rest/DemoDataRepository.java#L894-L922). Non vengono copiate dalla sede al momento del solve.
- Il `Shift` conosce solo `location_id` (`getLocation_id()`), **non** l'oggetto `Location` né alcuno specialista — confermato in [Shift.java](src/main/java/org/acme/employeescheduling/domain/Shift.java).
- Lo `EmployeeSchedule` costruito in [`generateDemoData(...)`](src/main/java/org/acme/employeescheduling/rest/DemoDataRepository.java#L3677-L3707) **trasporta già la lista `locations`**, ognuna con il proprio `specialistId` ([Location.java:58-66](src/main/java/org/acme/employeescheduling/dto/Location.java#L58-L66), colonna `locations.specialist_id`).

**Conseguenza pratica** — due strade per dare l'informazione al vincolo:

- **Strada A — Denormalizzare `specialistId` sul `Shift`** (consigliata). In `generateDemoData`, dopo aver caricato `locations` e `shifts`, si costruisce `Map<locationId, specialistId>` e si fa `shift.setSpecialistId(...)`. Il vincolo diventa un banale `forEach(Shift)`, gemello di `unavailableEmployee`. **Nessun nuovo problem fact.**
- **Strada B — Location come problem fact + join** `Shift.location_id == Location.id`. Più "pulita" concettualmente ma introduce un join in ogni vincolo e richiede che `Location` sia `@ProblemFactCollectionProperty` in `EmployeeSchedule`. Più lavoro, nessun vantaggio reale qui.

→ **Si va con la Strada A**, che ricalca 1:1 i vincoli esistenti `unavailableEmployee` (hard) e `undesired/desiredDayForEmployee` (soft) in [EmployeeSchedulingConstraintProvider.java:191-234](src/main/java/org/acme/employeescheduling/solver/EmployeeSchedulingConstraintProvider.java#L191-L234).

---

## 4. Livelli di compatibilità — ✅ DECISO (2026-07-08)

**Scala a 2 livelli: Evita / Incompatibile.** Niente "Preferito": il caso d'uso reale è il rifiuto, non la preferenza; un reward "preferito" spingerebbe ad accoppiare sempre le stesse persone, remando **contro** i bilanciamenti già presenti (`balanceEmployeeShiftAssignments`/`Hours`/`NightShifts`). Meno codice, UX più semplice.

| Livello | `type` | Significato | Vincolo solver | Effetto |
|---|---|---|---|---|
| *(neutro)* | — (nessuna riga) | nessuna relazione | — | default |
| **Da evitare** | 2 | "preferibilmente no" | soft **penalty** | evita, ma se serve accoppia |
| **Incompatibile** | 3 | "mai" | **hard penalty** | accoppiamento vietato |

Il valore `type = 1` (preferito) resta **riservato e inutilizzato**: se un domani servisse, si aggiunge solo il vincolo reward e lo stato in UI — zero migration (vantaggio della semantica 1/2/3 delle date).

**Nota:** non partire col solo *hard*. Troppe incompatibilità assolute → il solver può non trovare soluzione e lasciare **turni scoperti**. "Evita" (soft) assorbe i casi non tassativi.

> **Direzionalità:** è l'operatore che rifiuta lo specialista. Ai fini dell'assegnazione l'effetto è identico → si applica come **blocco/penalità sulla coppia**, non serve simmetria.

---

## 5. Modello dati proposto

### 5.1 Tabella di relazione (sparsa)

```sql
CREATE TABLE IF NOT EXISTS operator_specialist_affinity (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  operator_id   INTEGER NOT NULL,   -- → employees.id
  specialist_id INTEGER NOT NULL,   -- → specialists.id
  type          INTEGER NOT NULL,   -- 1=preferito, 2=da evitare, 3=incompatibile
  UNIQUE(operator_id, specialist_id)
);
CREATE INDEX IF NOT EXISTS idx_osa_operator   ON operator_specialist_affinity(operator_id);
CREATE INDEX IF NOT EXISTS idx_osa_specialist ON operator_specialist_affinity(specialist_id);
```

- `type` ricalca la semantica 1/2/3 già usata per le date → coerenza concettuale e di codice.
- `UNIQUE(coppia)` evita relazioni contraddittorie (stessa coppia marcata due volte).
- Tabella **sparsa**: si salvano solo le relazioni non-neutre. Il "neutro" è l'assenza di riga.
- Coerenza di struttura implicita: operatore e specialista appartengono alla stessa struttura (entrambe le tabelle hanno `structure_id`).
- **Creazione pigra della tabella** al primo accesso, come già fa `SpecialistRepository.ensureTable()` ([SpecialistRepository.java:50-71](src/main/java/org/acme/employeescheduling/rest/SpecialistRepository.java#L50-L71)). Coerente con lo stile del progetto (niente migration esterne).

> **Nota FK/SQLite:** i vincoli `FOREIGN KEY` non sono forzati di default in SQLite. Serve cancellazione a cascata **applicativa** alla delete di operatore/specialista (vedi §8).

### 5.2 Cosa serve a runtime (nel payload del solver)

- **Sul `Shift`**: nuovo campo `Integer specialistId` (denormalizzato dalla sede — Strada A).
- **Sull'`Employee`**: due insiemi tipizzati → `avoidSpecialistIds`, `incompatibleSpecialistIds` (`Set<Integer>`), sul modello di `unavailableDates/undesiredDates/desiredDates` in [Employee.java:51-61](src/main/java/org/acme/employeescheduling/dto/Employee.java#L51-L61).
- **Peso** in `SolverSettings`: nuovo `avoid_specialist_weight` — accanto a `desired_date_weight` / `undesired_date_weight` / `optional_skill_weight` in [SolverSettings.java:13-16](src/main/java/org/acme/employeescheduling/dto/SolverSettings.java#L13-L16).

---

## 6. Il solver: 2 nuovi vincoli

Score attuale = `HardSoftBigDecimalScore`. I 2 vincoli ricalcano i pattern esistenti e vanno registrati in `defineConstraints(...)` ([EmployeeSchedulingConstraintProvider.java:82-100](src/main/java/org/acme/employeescheduling/solver/EmployeeSchedulingConstraintProvider.java#L82-L100)):

```
incompatibleSpecialist (HARD)  → penalize  se employee.incompatibleSpecialistIds contiene shift.specialistId
avoidSpecialist        (SOFT)  → penalize  × avoidSpecialistWeight
```

Scheletro (gemello di `unavailableEmployee`, **Strada A**, nessun join su Location):

```java
Constraint incompatibleSpecialist(ConstraintFactory f) {
    return f.forEach(Shift.class)
        .filter(s -> s.getEmployee() != null
                  && s.getSpecialistId() != null
                  && s.getEmployee().getIncompatibleSpecialistIds().contains(s.getSpecialistId()))
        .penalize(HardSoftBigDecimalScore.ONE_HARD)
        .asConstraint("Incompatible specialist");
}
```

Il soft si aggancia a `SolverSettings` col `.join(SolverSettings.class)` come già fa `undesiredDayForEmployee` ([righe 206-215](src/main/java/org/acme/employeescheduling/solver/EmployeeSchedulingConstraintProvider.java#L206-L215)).

### 6.1 Attenzione all'infeasibility

Con vincoli **hard** il solver può lasciare turni **non assegnati** a causa delle incompatibilità. Esiste già la gestione `unassignedHard`/`unassignedSoft` + flag `allowUnassigned` ([righe 335-345](src/main/java/org/acme/employeescheduling/solver/EmployeeSchedulingConstraintProvider.java#L335-L345)). Serve **feedback UI chiaro** ("turno scoperto: nessun operatore compatibile con lo specialista X"), altrimenti sembra un bug.

---

## 7. UX — dichiarare le (in)compatibilità

Obiettivo: rendere **naturale per il caposala** dichiararle, senza errori e con feedback immediato.

### 7.1 Requisiti

- **Veloce** (le incompatibilità sono poche ma importanti).
- **Leggibile a colpo d'occhio** (chi non va con chi).
- **Niente contraddizioni** (garantito già dallo `UNIQUE` sulla coppia).
- **Spiega le conseguenze** (hard = può lasciare turni scoperti).
- Coerente con lo stile esistente (badge colorati, toggle, modali) e **localizzato in 5 lingue**.

### 7.2 Opzione A — nel modal Operatore, pattern "lista eccezioni" — ✅ DECISO (2026-07-08)

Sezione "Compatibilità con Specialisti" nella scheda operatore col pattern **lista eccezioni**: si elencano SOLO le relazioni non-neutre; per aggiungerne una si sceglie lo specialista da un dropdown e si clicca ⚠ Evita o ✗ Mai. Tutto il resto è implicitamente neutro — ricalca 1:1 la tabella sparsa (§5.1).

```
┌─ Modifica Operatore: Aicha Ait Bassou ────────────┐
│ Nome … Cognome … Codice … Email … [x] Attivo      │
│ Competenze: [✓] Radiology [ ] Pediatria …         │
│ ───────────────────────────────────────────────── │
│ Compatibilità con Specialisti                     │
│                                                   │
│  ⚠ Benedetti Marco   Da evitare            [🗑]   │
│  ✗ Rossi Paolo       Incompatibile         [🗑]   │
│                                                   │
│  [ Seleziona specialista… ▾ ] [⚠ Evita] [✗ Mai]   │
│                                                   │
│  ⓘ "Incompatibile" può lasciare turni scoperti    │
└───────────────────────────────────────────────────┘
```

**Perché questo pattern** (vs elenco completo con selettore a 3 stati, vs badge ciclabili):
- **Veloce**: si tocca solo chi serve; le incompatibilità reali sono poche (2-3 per operatore).
- **Modal corto**: niente scroll interno con 30 specialisti; coerente col form react-bootstrap compatto esistente ([EmployeeModal.tsx](frontend/src/components/employees/EmployeeModal.tsx)).
- **Stato esplicito**: ogni riga dice chiaramente il livello (a differenza dei badge ciclabili).
- Il dropdown mostra solo specialisti **attivi della struttura** non già presenti in lista.
- Righe con badge colorato: giallo=evita, rosso=incompatibile; cestino per rimuovere (torna neutro).

### 7.3 Opzione B — nel modal Specialista (vista speculare)

Stessi dati dal lato specialista ("operatori incompatibili con questo medico"). Utile come **seconda vista**, non come unica.

### 7.4 Opzione C — matrice Operatori × Specialisti (potente, più lavoro)

Griglia con celle ciclabili a codice colore. **Pro:** panoramica totale, inserimento rapido. **Contro:** scala male con molti nominativi → responsive con `overflow-x`.

### 7.5 Raccomandazione

Partire da **Opzione A** + eventuale **vista di sola lettura** a matrice in Report/Configurazione. Matrice editabile (C) come evoluzione.

### 7.6 Dettagli da non dimenticare

- **Codice colore** coerente: giallo=evita, rosso=incompatibile, grigio=neutro (riusare i badge già in uso per skill/date).
- **Default = neutro** → si salvano solo le righe non-neutre.
- **Filtro/ricerca** per elenchi lunghi.
- **Microcopy** vicino a "Incompatibile" sul rischio di turni scoperti.
- **Solo stessa struttura**: mostrare solo operatori/specialisti attivi della struttura corrente.
- **i18n**: nuove chiavi in tutte e 5 le lingue (regola tassativa).

---

## 8. Edge case e insidie

- **Sede senza specialista** (`specialistId == null`) → `shift.specialistId` null → nessun vincolo, si ignora (il `filter` lo scarta).
- **Più specialisti per sede** (oggi 1): se in futuro N, la regola diventa "incompatibile con *almeno uno* → blocca". Il modello dati regge già.
- **Operatore/specialista disattivato**: `generateDemoData(activeOnly=true)` già filtra gli inattivi ([DemoDataRepository.java:3685-3688](src/main/java/org/acme/employeescheduling/rest/DemoDataRepository.java#L3685-L3688)); le righe di affinità restano ma non producono effetti.
- **Cancellazione operatore/specialista** → cancellare a cascata le righe di affinità **applicativamente** (SQLite non forza le FK). Da agganciare a `deleteSpecialistById` ([SpecialistRepository.java:183-193](src/main/java/org/acme/employeescheduling/rest/SpecialistRepository.java#L183-L193)) e alla delete operatore.
- **Backup pre-distruttivo**: la delete a cascata è un'operazione distruttiva → verificare che rientri nel sistema di backup automatico già presente.
- **Calibrazione pesi**: quanto pesa "da evitare" vs una skill opzionale o una data indesiderata? Da tarare nei `SolverSettings`, esposto in Configurazione → Solver.

---

## 9. Estensione futura (da tenere a mente ORA)

Lo stesso meccanismo (relazione binaria + vincolo) copre anche **Operatore ↔ Operatore** ("due che non possono stare nello stesso turno" → `forEachUniquePair` su turni sovrapposti, come `noOverlappingShifts` a [riga 137](src/main/java/org/acme/employeescheduling/solver/EmployeeSchedulingConstraintProvider.java#L137)).

**Decisione di design da chiudere PRIMA di scrivere lo schema:** tabella dedicata `operator_specialist_affinity` **oppure** schema generalizzato `person_affinity(subject_type, subject_id, object_type, object_id, type)`. Il generalizzato evita una futura migration ma è più astratto. → *Raccomandazione:* partire **dedicato** (più leggibile, YAGNI), generalizzare solo se/quando arriva davvero l'operatore↔operatore.

---

## 10. Decisioni aperte (da chiudere prima di sviluppare)

- [x] **Scala**: ✅ **Evita/Incompatibile** (deciso 2026-07-08, §4). Niente Preferito; `type=1` riservato.
- [x] **UI primaria**: ✅ **modal Operatore, pattern "lista eccezioni"** (deciso 2026-07-08, §7.2). Vista speculare (B) e matrice (C) rimandate.
- [ ] **Schema**: dedicato vs generalizzato (§9).
- [ ] **Pesi soft**: default (es. 1/1 come gli altri) e se esporli in Config → Solver da subito.
- [ ] **Feedback infeasibility**: come comunicare i turni scoperti da incompatibilità.

---

## 11. Roadmap a fasi

1. **Fase 1 — Dati & UX base**: tabella affinità + repository + endpoint CRUD + gestione nel modal Operatore (scala completa) + i18n. *Nessun impatto sul solver.*
2. **Fase 2 — Solver**: campo `specialistId` sul `Shift` + denormalizzazione in `generateDemoData` + caricamento insiemi sull'`Employee` + 3 vincoli + 2 pesi in `SolverSettings` (+ sezione Config→Solver).
3. **Fase 3 — Feedback & viste**: gestione turni scoperti in UI + vista matrice (sola lettura).
4. **Fase 4 (opz.)** — estensione operatore↔operatore.

---

## 12. Mappa interventi sul codice reale (riferimento rapido)

**Backend — dati (Fase 1)**
- Nuovo `AffinityRepository.java` (pattern `SpecialistRepository`): `ensureTable()`, `getByOperator/Structure`, `upsert(operatorId, specialistId, type)`, `delete(...)`, `deleteByOperator/BySpecialist`.
- Nuovo `AffinityResource.java` (REST) sul modello di `SpecialistResource.java`.
- Cascata delete: agganciare in [SpecialistRepository.deleteSpecialistById](src/main/java/org/acme/employeescheduling/rest/SpecialistRepository.java#L183) e nella delete operatore.

**Backend — solver (Fase 2)**
- [Shift.java](src/main/java/org/acme/employeescheduling/domain/Shift.java): campo `Integer specialistId` + getter/setter (NON planning, semplice problem property).
- [Employee.java](src/main/java/org/acme/employeescheduling/dto/Employee.java): 2 `Set<Integer>` (`avoidSpecialistIds`, `incompatibleSpecialistIds`) + getter/setter, sul modello delle date (righe 51-61).
- [DemoDataRepository.generateDemoData](src/main/java/org/acme/employeescheduling/rest/DemoDataRepository.java#L3677): dopo il load, `Map<locationId,specialistId>` da `locations` → `shift.setSpecialistId(...)`; idratare i 2 set sugli `Employee` (nuovo metodo `hydrateEmployeeAffinities`, gemello di `hydrateShiftSkills` a [riga 894](src/main/java/org/acme/employeescheduling/rest/DemoDataRepository.java#L894)).
- [SolverSettings.java](src/main/java/org/acme/employeescheduling/dto/SolverSettings.java): campo `avoid_specialist_weight` + getter/setter (+ persistenza in `getSolverSettings`/update + form Config→Solver frontend).
- [EmployeeSchedulingConstraintProvider.java](src/main/java/org/acme/employeescheduling/solver/EmployeeSchedulingConstraintProvider.java): 2 vincoli + registrazione in `defineConstraints` (riga 82).

**Frontend**
- API: nuovo `affinity.ts` (pattern `specialists.ts`).
- Modali: `EmployeeModal.tsx` (sezione compatibilità) e/o `SpecialistModal.tsx` (vista speculare).
- Config→Solver: 2 nuovi campi peso.
- i18n `frontend/src/i18n/`: nuove chiavi × 5 lingue.
