# Employee Scheduling — Frontend

Applicazione React per la gestione dei turni del personale, integrata con il solver **Timefold** (Quarkus/Java backend).

---

## Stack tecnologico

| Componente | Tecnologia |
|---|---|
| Framework UI | React 18 + TypeScript |
| Bundler | Vite |
| CSS/Componenti | Bootstrap 5 + React-Bootstrap |
| Timeline | vis-timeline + vis-data |
| Stato globale | Zustand (persist → localStorage) |
| i18n | react-i18next (traduzioni da backend) |
| PDF | jsPDF |
| Icone | Font Awesome 6 |
| Notifiche | react-hot-toast |

---

## Struttura del progetto

```
frontend/src/
├── App.tsx                    # Routing principale (React Router)
├── main.tsx                   # Entry point, inizializza i18n + Zustand
├── api/                       # Client HTTP tipizzato
│   ├── client.ts              # fetch wrapper con gestione errori centralizzata
│   ├── employees.ts           # /demo-data/employees, /demo-data/addemployee, …
│   ├── locations.ts           # /demo-data/getlocations, /demo-data/addlocation, …
│   ├── shifts.ts              # /demo-data/generate, /schedules (Timefold), …
│   ├── skills.ts              # /demo-data/get_skills, /demo-data/save_skills, …
│   ├── structures.ts          # /structures
│   ├── dates.ts               # /demo-data/getemployeedates, …
│   └── labels.ts              # /labels, /languages, /translations, /localizzazioni
├── pages/                     # Pagine (una per route)
│   ├── ShiftsPage.tsx         # Timeline principale + solver
│   ├── EmployeesPage.tsx      # CRUD dipendenti
│   ├── LocationsPage.tsx      # CRUD sedi
│   ├── SkillsPage.tsx         # CRUD competenze (batch)
│   ├── DatesPage.tsx          # Disponibilità dipendenti
│   ├── ReportPage.tsx         # Generazione PDF
│   ├── StructuresPage.tsx     # CRUD strutture organizzative
│   └── LabelsPage.tsx         # CRUD etichette i18n
├── components/
│   ├── Navbar.tsx             # Barra navigazione + selettore struttura/lingua
│   ├── ConfirmModal.tsx       # Modal conferma riutilizzabile
│   ├── ContextMenu.tsx        # Menu contestuale tasto destro
│   ├── employees/
│   │   ├── EmployeeModal.tsx       # Add/Edit dipendente
│   │   └── EmployeeDatesModal.tsx  # Gestione fasce disponibilità (da toolbar)
│   ├── locations/
│   │   ├── LocationModal.tsx       # Add/Edit sede
│   │   └── LocationShiftsModal.tsx # Lista turni di una sede
│   ├── shifts/
│   │   ├── VisTimeline.tsx         # Wrapper imperativo vis-timeline
│   │   ├── ShiftModal.tsx          # Add/Edit turno
│   │   └── SolveResultModal.tsx    # Riepilogo vincoli dopo solve
│   ├── structures/
│   │   └── StructureModal.tsx      # Add/Edit struttura
│   └── labels/
│       └── LabelModal.tsx          # Add/Edit etichetta + traduzioni per lingua
├── store/
│   └── useAppStore.ts         # Zustand: struttura corrente + lingua (persistiti)
├── i18n/
│   └── index.ts               # Inizializzazione react-i18next con traduzioni da backend
├── types/
│   └── index.ts               # Tipi TypeScript condivisi (Structure, Shift, Employee, …)
└── utils/
    └── pdfHelpers.ts          # Generazione PDF con jsPDF
```

---

## Avvio in sviluppo

```bash
# Prerequisito: backend Quarkus in esecuzione su localhost:8080
cd frontend
npm install
npm run dev
# → http://localhost:5173
```

Il Vite dev server fa da proxy verso `http://localhost:8080` per tutti i path:
`/demo-data`, `/structures`, `/schedules`, `/labels`, `/languages`, `/translations`, `/localizzazioni`

---

## Build per produzione

```bash
npm run build
# Output: ../src/main/resources/META-INF/resources
# Quarkus serve il frontend direttamente in produzione
```

---

## Architettura Backend ↔ Frontend

### Endpoint principali

| Endpoint | Metodo | Usato da |
|---|---|---|
| `/demo-data/employees?structureId=X` | GET | EmployeesPage, ReportPage |
| `/demo-data/addemployee?structureId=X` | POST | EmployeeModal |
| `/demo-data/updateemployee/{id}` | PUT | EmployeeModal |
| `/demo-data/employees/{id}` | DELETE | EmployeesPage |
| `/demo-data/getlocations?structureId=X` | GET | LocationsPage, ShiftModal |
| `/demo-data/getlocation/{id}` | GET | LocationsPage (per skills complete) |
| `/demo-data/addlocation?structureId=X` | POST | LocationModal |
| `/demo-data/updatelocation/{id}` | PUT | LocationModal |
| `/demo-data/deletelocation/{id}` | DELETE | LocationsPage |
| `/demo-data/get_skills` | GET | SkillsPage, modal dipendenti/sedi |
| `/demo-data/save_skills` | POST | SkillsPage (batch) |
| `/demo-data/skills/{id}` | DELETE | SkillsPage |
| `/demo-data/generate?structureId=X` | GET | ShiftsPage, ReportPage, Solve |
| `/demo-data/editshift/{id}?structureId=X` | GET | ShiftModal |
| `/demo-data/addshift` | POST | ShiftModal |
| `/demo-data/updateshift/{id}` | PUT | ShiftModal |
| `/demo-data/delete_shift/{id}` | DELETE | ShiftModal, LocationShiftsModal |
| `/demo-data/getemployeedates/{empId}` | GET | DatesPage, EmployeeDatesModal |
| `/demo-data/add_employee_dates/{empId}` | POST | DatesPage, EmployeeDatesModal |
| `/demo-data/update_employee_dates/{id}` | PUT | DatesPage, EmployeeDatesModal |
| `/demo-data/delete_date/{id}` | DELETE | DatesPage, EmployeeDatesModal |
| `/structures` | GET/POST | Navbar, StructuresPage |
| `/structures/{id}` | PUT/DELETE | StructuresPage |
| `/schedules` | POST | shiftsApi.solve() |
| `/schedules/{jobId}` | GET/DELETE | shiftsApi.getJob() / stopJob() |
| `/schedules/analyze` | PUT | shiftsApi.analyze() |
| `/labels` | GET/POST/PUT/DELETE | LabelsPage |
| `/languages` | GET | LabelModal |
| `/translations` | GET | i18n init |
| `/localizzazioni/labels/{id}` | GET/PUT | LabelModal |

### Struttura dati chiave: `ScheduleData`

```
GET /demo-data/generate → ScheduleData {
  employees: [{ id, fullName, unavailableDates, undesiredDates, desiredDates }]
  locations:  [{ id, name }]
  shifts:     [{ id, location_id, start, end, employee, employeeId, requiredSkills, optionalSkills }]
  score?:     "0hard/-2soft"     // solo dopo solve
  solverStatus?: "NOT_SOLVING"   // solo dopo solve
}
```

---

## Flusso Timefold Solver

```
1. Utente preme "Solve"
   ↓
2. GET /demo-data/generate → JSON grezzo (EmployeeSchedule Java)
   ↓
3. POST /schedules  → jobId (plain text)
   ↓
4. Polling ogni 2s: GET /schedules/{jobId}
   │   solverStatus === "SOLVING_ACTIVE" → continua polling
   └── solverStatus !== "SOLVING_ACTIVE" → fine
   ↓
5. GET /schedules/{jobId}  (schedule risolto)
   PUT /schedules/analyze → ScoreAnalysis
   ↓
6. SolveResultModal: score globale + tabella vincoli violati/rispettati
```

> **Nota**: il payload del `POST /schedules` deve essere il JSON grezzo del backend
> (non l'interfaccia TypeScript `ScheduleData`), perché il solver Timefold richiede campi
> aggiuntivi che TypeScript non modella. Per questo `shiftsApi.solve()` usa `fetch` diretto
> invece del client tipizzato.

---

## Sistema i18n

1. All'avvio: `initI18n()` legge la cache localStorage (`i18n_cache`, TTL 1h)
2. Se scaduta: `GET /translations` → `{ langCode: { key: value } }`
3. Le chiavi seguono la convenzione `prefisso.nomeChiave`:
   - `btn.*`, `modal.*`, `label.*`, `table.*`, `msg.*`, `toast.*`, ecc.
4. Cambio lingua: `i18n.changeLanguage(code)` + salvataggio in Zustand store
5. Lingue supportate: `it`, `en`, `fr`, `es`, `de`

---

## Gestione strutture multiple

Ogni sede e dipendente appartiene a una struttura (`structure_id`).
I turni ereditano la struttura tramite `location_id → locations.structure_id`.
La struttura attiva è salvata in `useAppStore.currentStructure` e passata
come `structureId` a tutti gli endpoint che la richiedono.

---

## Protezione eliminazione entità in uso

| Entità | Controllo |
|---|---|
| Skill | Campo `used` restituito dall'API (assegnata a dipendente/sede) |
| Dipendente | Cross-reference con `schedule.shifts[].employee.id` |
| Sede | Cross-reference con `schedule.shifts[].location_id` |
| Struttura Default (id=1) | Pulsante elimina disabilitato nell'UI |

Le entità in uso mostrano l'icona cestino in rosa (`#f5a0a0`) con `cursor: not-allowed`.

---

## Note implementative importanti

### LocationsPage — skills mancanti dalla list API
`GET /demo-data/getlocations` non restituisce le skills.
La pagina risolve questo con:
```ts
locationsApi.list(structureId).then(locs => Promise.all(locs.map(l => locationsApi.get(l.id))))
```

### VisTimeline — wrapper imperativo
vis-timeline è una libreria DOM-imperativa incompatibile con React dichiarativo.
Il componente usa `useRef` per l'istanza e `DataSet` mutabili fuori dal render cycle.
Gli eventi del gruppo (icone nella colonna sinistra) usano event delegation con `data-action`.

### Skills — flag `used`
Il backend restituisce **tutte** le skills con `used: true/false`.
Il frontend deve sempre filtrare con `.filter(s => s.used)` prima di mostrare le skills assegnate.
