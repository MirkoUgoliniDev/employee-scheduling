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

Validation errors are machine-readable codes (`ASSIGNMENT_TITLE_REQUIRED`,
`ASSIGNMENT_LOCATION_CUSTOMER_MISMATCH`, …) translated by the frontend.

## UI

Two new screens, German-first, both reachable from the navigation bar:

- **Kunden** (`/customers`) — customer master data: code, name, address, usual start time, usual
  duration, default qualification, recurrence note, restrictions, assigned locations, active.
- **Kundeneinsätze** (`/assignments`) — period view: one row per visit with its positions, the
  assigned operator or "Offen", lock and unlock, edit (including duration and team
  composition), optimise, and approve or discard the proposal.

All labels live in `src/main/resources/i18n/ui-translations.tsv` with the five languages; the
existing locales are untouched.

## Database

`V9__customer_centric_scheduling.sql`, in both `sqlite` and `postgresql` flavours, adds
`customers`, `customer_assignments`, `assignment_requirements`, `travel_times`,
`employee_locations` and the columns `employees.role`, `employees.contracted_weekly_hours`,
`employees.employment_type`, `locations.customer_id`, `locations.external`,
`shifts.assignment_id`, `shifts.required_role`. `MigrationSchemaParityTest` keeps the two
dialects in step.

## What is not implemented yet

Recorded honestly, in the order it should be tackled:

1. **Recurrence templates.** `customer_assignments.series_id` and the `RECURRING` status exist,
   but there is no generator for "every 2nd week, Monday and Thursday, until 31.12." and no
   per-occurrence exception handling.
2. **Travel times as a constraint.** `travel_times` is stored and writable, and the UI does not
   maintain it yet; no constraint reads it, so back-to-back visits at two locations are not
   penalised.
3. **Working-time limits and minimum rest for the new columns.**
   `employees.contracted_weekly_hours` is carried through to the solver payload but the
   fairness/overtime constraints still use the previous weekly-hours rules.
4. **Availability windows with time of day.** The existing model stores whole-day
   availability/absence rows; the requirement asks for windows like "only after 09:00".
5. **CSV import/export** for customers, assignments and employees.
6. **Replanning diff view.** The before/after comparison and the explicit list of unresolved
   requirements after a re-solve are shown as counters ("Offene Positionen"), not yet as a
   field-level diff.
7. **Stable-team soft constraint** and per-customer preference weights.

The vertical slice works end to end today: create a customer, assign locations, add employees
with roles and location eligibility, create an assignment with a configurable duration and team,
solve, review the proposed team, approve, edit the assignment and re-solve without losing the
already assigned operators.
