# Customer-centric scheduling (MVZ)

*Why does the planning object in this repository become a customer assignment, how does it map
onto the existing solver, and what is still missing?*

This document is the architecture decision record for the customer-centric extension of the
planner. It records the decision, the mapping onto the existing Timefold model, the constraint
set and the migration path. It is written to be read before changing anything in
`rest/AssignmentService.java`, `domain/Shift.java` or the constraint provider.

## The problem

The original planner is shift-centric: a `Shift` is a block of time at a `Location` that needs
one employee with certain skills. That is enough for a ward roster and wrong for an
outpatient medical care centre (MVZ), where the unit of work is a **visit to a customer**:

- one customer, one location, one day, one service;
- a *team* of people with different roles (for example one physician and two medical
  assistants), not one person;
- a *variable duration* (30 minutes for a wound dressing, four hours for a treatment series)
  rather than a fixed shift template;
- recurring visits that must not turn into a series of unrelated copies;
- and a planner who must see **which requirement is still uncovered**, not just a score.

## Decision: reuse `Shift` as the position, do not add a second solver

The alternative was a new `@PlanningEntity CustomerAssignment` with a `List<@PlanningVariable
Employee>` team variable. It was rejected:

1. Timefold has no list variable with per-element role requirements; a `@PlanningListVariable`
   would need an index-based trick, and the existing constraints (weekly hours, rest periods,
   availability intervals, workload balancing, pins) are written against `Shift`.
2. Two planning entities in one `Solution` (the old `Shift` and a new assignment) would mean two
   sources of truth for the same working time, and every existing constraint would have to know
   about both — with no guarantee that the two stay consistent.
3. `allowsUnassigned = true` already gives what an understaffed MVZ needs: an unfilled position
   can exist explicitly.

So the mapping is:

| Concept | Representation |
|---|---|
| Customer | `customers` table, `CustomerEntity`, DTO `Customer` |
| Customer assignment (visit) | `customer_assignments` + `assignment_requirements`, DTO `CustomerAssignment` |
| Required team member ("position") | **one `shifts` row** with `assignment_id`, `required_role`, skill, location, start and end |
| Team composition | `assignment_requirements`: role + optional qualification + headcount |
| Unfilled position | a `shifts` row with `employee_id = NULL` (allowed by `allowsUnassigned`) |
| Locked decision | the existing `@PlanningPin` flag on the position (`pinned`) |

An assignment with 1 physician and 2 medical assistants therefore owns **three** shift rows, all
with the same window and location. The solver keeps working on shifts exactly as before; the
customer layer is a projection on top of them, kept in sync by
`AssignmentMaterializer` (pure, unit-tested) and `AssignmentService` (persistence).

Consequences that are accepted deliberately:

- An assignment is **not** a planning entity. Its team is the team of its positions; there is
  no separate "team" object to keep consistent.
- Changing a duration rewrites the positions' start/end in place. Everything downstream
  (overlaps, rest, hours) is recomputed by the existing constraints on the next solve.
- A position that the solver cannot fill stays visible. The planner sees "Offen" instead of a
  silently understaffed assignment.

## Minimal churn when replanning

Editing an assignment (duration, start, requirements) must not shuffle the whole week. Every
existing position carries a **churn key** — `role + "|" + skillId` — and
`AssignmentService.regeneratePositions` pairs the wanted positions with the existing ones per
key, in list order:

- matched position: `locationId`, `startTime`, `endTime`, `requiredRole` are rewritten in place,
  so `id`, the assigned employee and the pin survive;
- shortfall: new shift rows are inserted, unassigned;
- surplus: the extra rows are deleted.

Non-planned statuses (`CANCELLED`, `COMPLETED`, `RECURRING`) keep their history but own no open
positions, so a cancelled visit cannot be staffed by accident.

## Recurrence: the series generator

A month of visits is not entered by hand. `assignment_series` holds the **template** — customer,
location, service, start time, duration, priority, team composition, recurrence pattern, weekday
set, effective range and the fixed-time flag — and `AssignmentSeriesService` materialises the
concrete `customer_assignments` from it.

Occurrence calculation is a pure function (`occurrences()`), which is why it can be tested
without a database:

| Pattern | Rule |
|---|---|
| `WEEKLY` | every selected weekday in the effective range |
| `BIWEEKLY` | the same weekdays, but only in weeks whose distance from the anchor week (`effectiveFrom`'s Monday) is even, so the parity never drifts between two requests for adjacent months |
| `MONTHLY_NTH_WEEKDAY` | the *n*-th occurrence of each selected weekday in the month; `nth = 5` means **the last** one, so it resolves to the 5th when the month has it and the 4th otherwise, and only `n < 5` is skipped in months that have no such day |

Generation is a **top-up**, not a rebuild: `POST /assignments/series/{id}/generate?from&to`
creates only the occurrences that have neither an assignment nor a recorded exception, and
reports `{occurrences, generated, skipped, positions}`. Calling it twice in a row returns
`{generated: 0, skipped: n}`, so pressing it again for the next month is safe, and an occurrence
the planner deleted is not resurrected behind their back. Each generated assignment carries
`series_id`, which is what the UI uses for the "Serie" badge and for the per-row cancel/restore
action.

**Per-occurrence exceptions** live in `assignment_exceptions` (`series_id`, `exception_date`,
`kind`). Cancelling one visit writes an exception and cancels only that assignment, regenerating
its positions away; the series template is untouched. Restoring deletes the exception and
regenerates the single occurrence. A date that already has an assignment is never rewritten by a
generation run — pinned or not — so a planner's manual change to one visit survives.

## Constraints added

Two hard constraints were added to `EmployeeSchedulingConstraintProvider`, both penalising in
minutes (the provider's existing unit, weighted `ONE_HARD`):

| Constraint | Enforces |
|---|---|
| `requiredRole` | a `PHYSICIAN` position may only be filled by an employee whose `role` is `PHYSICIAN`, and vice versa |
| `locationEligibility` | an employee may only be assigned where `employee_locations` allows it — **no rows means unrestricted**, for installations without eligibility data |

Unqualified staff filling a gap stays impossible for both new rules, and the pre-existing
`requiredSkill`, no-overlap, rest, availability, weekly-hours and unassigned-shift constraints
apply to the new positions unchanged. The soft side reuses the existing weights, so fairness,
preferences and workload balancing affect customer visits with no new code.

## Replanning and human approval

The optimisation path is the existing one, unchanged:

1. `GET /demo-data/generate?start&end&activeOnly=true&context=true` builds the problem;
2. `POST /schedules` solves it and returns a job id;
3. the UI polls `GET /schedules/{jobId}`;
4. **nothing is written** until the planner presses "Plan übernehmen", which calls
   `POST /demo-data/save-assignments` with `{shift_id, employee_id, version}`.

The saved payload carries each shift's `version`; a concurrent edit makes the whole save fail
with `SHIFTS_CHANGED` instead of silently overwriting. `GET /assignments/conflicts` reports a
locked position whose employee has since become unavailable, has the wrong role, is not
eligible for the location or lacks the qualification — a conflict the solver cannot repair
(a pin forbids the change) and which must therefore be surfaced rather than hidden.

## API

| Endpoint | Purpose |
|---|---|
| `GET/POST /customers`, `GET/PUT/DELETE /customers/{id}` | customer master data; `locationIds` is written to `locations.customer_id` |
| `GET /assignments?structureId&from&to` | assignments of a period with team and staffing counters |
| `POST/PUT/DELETE /assignments[/{id}]` | create, edit (regenerates positions), delete |
| `POST /assignments/{id}/pin?pinned=` | lock/unlock every position of the assignment |
| `POST /assignments/{id}/regenerate` | rebuild positions from the requirements |
| `GET /assignments/unfilled` | explicitly unfilled positions of a period |
| `GET /assignments/conflicts` | locked positions invalidated by absence, role, location or qualification |
| `GET/POST /assignments/series`, `GET/PUT/DELETE /assignments/series/{id}` | recurrence templates |
| `POST /assignments/series/{id}/generate?from&to` | idempotent top-up of the period; returns `occurrences`, `generated`, `skipped`, `positions` |
| `GET /assignments/series/{id}/exceptions` | per-occurrence exceptions of a series |
| `POST /assignments/series/{id}/exceptions/cancel?date=` | cancel one occurrence without touching the template |
| `POST /assignments/series/{id}/exceptions/move?date=&to=` | move one occurrence to another date |
| `DELETE /assignments/series/{id}/exceptions/{date}` | drop the exception and regenerate that single occurrence |

Validation errors are machine-readable codes (`ASSIGNMENT_TITLE_REQUIRED`,
`ASSIGNMENT_LOCATION_CUSTOMER_MISMATCH`, …) translated by the frontend.

## UI

Two new screens, German-first, both reachable from the navigation bar:

- **Kunden** (`/customers`) — customer master data: code, name, address, usual start time, usual
  duration, default qualification, recurrence note, restrictions, assigned locations, active.
- **Kundeneinsätze** (`/assignments`) — period view: one row per visit with its positions, the
  assigned operator or "Offen", lock and unlock, edit (including duration and team
  composition), optimise, and approve or discard the proposal. Above the table sits the
  **Serientermine** panel: the series of the structure with a readable pattern
  ("Wöchentlich · Mo, Do · 08:00 · 120′"), its validity range, the number of generated visits,
  an "Erzeugen" button for the visible period and the series' delete action, plus a modal for a
  new series (customer, location, recurrence, weekday set, validity, fixed-time flag, team
  composition, notes). A generated visit carries a "Serie" badge and, in the row actions, either
  "Termin absagen" or "Termin wiederherstellen".

All labels live in `src/main/resources/i18n/ui-translations.tsv` with the five languages; the
existing locales are untouched.

## Database

`V9__customer_centric_scheduling.sql` adds
`customers`, `customer_assignments`, `assignment_requirements`, `travel_times`,
`employee_locations` and the columns `employees.role`, `employees.contracted_weekly_hours`,
`employees.employment_type`, `locations.customer_id`, `locations.external`,
`shifts.assignment_id`, `shifts.required_role`. `V10__assignment_series.sql` adds
`assignment_series`, `assignment_series_requirement` and `assignment_exceptions`. Both exist in
the `sqlite` and `postgresql` flavours and differ only in the identity syntax
(`AUTOINCREMENT` vs. `GENERATED BY DEFAULT AS IDENTITY`) and in `REAL` vs. `DOUBLE PRECISION`;
`MigrationSchemaParityTest` keeps the two dialects in step.

## What is not implemented yet

Recorded honestly, in the order it should be tackled:

1. **Travel times as a constraint.** `travel_times` is stored and writable, and the UI does not
   maintain it yet; no constraint reads it, so back-to-back visits at two locations are not
   penalised.
2. **Working-time limits and minimum rest for the new columns.**
   `employees.contracted_weekly_hours` is carried through to the solver payload but the
   fairness/overtime constraints still use the previous weekly-hours rules.
3. **Availability windows with time of day.** The existing model stores whole-day
   availability/absence rows; the requirement asks for windows like "only after 09:00".
4. **CSV import/export** for customers, assignments and employees. This is now the largest gap
   for a month horizon: the series generator removes the repetitive entry of a period, but a
   customer master data import is still typed by hand.
5. **Replanning diff view.** The before/after comparison and the explicit list of unresolved
   requirements after a re-solve are shown as counters ("Offene Positionen"), not yet as a
   field-level diff.
6. **Stable-team soft constraint** and per-customer preference weights.

The vertical slice works end to end today: create a customer, assign locations, add employees
with roles and location eligibility, create an assignment with a configurable duration and team,
solve, review the proposed team, approve, edit the assignment and re-solve without losing the
already assigned operators.

A month is planned through a series: create the template once, press "Erzeugen" for the month,
and the visits materialise with their positions; cancel a single week in the row actions, and the
template keeps generating the others.
