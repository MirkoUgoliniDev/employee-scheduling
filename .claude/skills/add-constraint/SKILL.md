---
name: add-constraint
description: Add, change or remove a solver constraint — the ConstraintProvider method, its score unit, a configurable weight if it needs one, the name shown in the UI, and the ConstraintVerifier test. Use whenever scheduling behaviour must change: "avoid X", "prefer Y", "never more than N". Getting the units wrong here does not fail anything visibly, it silently unbalances the solver.
---

# Adding a solver constraint

All 22 constraints live in one file:
`src/main/java/org/acme/employeescheduling/solver/EmployeeSchedulingConstraintProvider.java`.

Read the neighbouring constraints before writing. Timefold 1.33 — check the official docs
for the constraint stream API rather than working from memory (`CLAUDE.md`, design
references).

## 1. Hard or soft — and in what unit

This is the decision that matters, and the one that goes wrong invisibly.

- **Hard** constraints are violations: a missing required skill, overlapping shifts, an
  unavailable employee. Any hard violation makes the schedule infeasible.
- **Soft** constraints are preferences, and they compete with each other. For them to
  compete *fairly* they must be expressed in the **same unit**.

The established unit is **minutes × weight**. The existing comments explain the scaling
choices in detail — for instance why an excess shift is scaled as "one day of minutes",
and why `unassigned_weight` must dominate `undesired_date_weight`. Read them; they are
the record of tuning already paid for.

A soft constraint penalized as a bare count (1 per violation) against others measured in
minutes is **thousands of times weaker** than intended. Nothing fails: the solver just
quietly ignores it. If you deviate from minutes×weight, say why in a comment.

## 2. If the weight must be configurable

Weights are **per structure**, persisted as columns of `SolverSettingsEntity`
(`desired_date_weight`, `undesired_date_weight`, `balance_weight`,
`optional_skill_weight`, `unassigned_weight`, `same_location_weight`,
`avoid_specialist_weight`, …).

A new configurable weight is therefore a full vertical slice:

1. migration in **both** engine directories — `new-migration` skill
2. column on `SolverSettingsEntity` (`nullable=false`, with a sensible default for
   existing rows)
3. field on the `SolverSettings` DTO and in `SolverSettingsResource`
4. control in `components/solver/SolverSettingsSection.tsx` and `api/solverSettings.ts`
5. label and help text through `t()` in five languages — `add-ui-string` skill

If the constraint does not need tuning, use a constant and skip all of it. Do not add a
setting nobody will ever change.

## 3. The constraint name is user-visible

`.asConstraint("Missing required skill")` — that string surfaces in the UI when a
schedule is explained. Keep it short and descriptive.

**Known debt**: constraint names in `SolveResultModal` are still unlocalized, so they
appear in English regardless of the interface language. If you are adding one, that is
the moment to route them through `t()` rather than adding to the pile.

## 4. Test it with ConstraintVerifier

A constraint without a test will drift, because the solver returns *a* schedule either
way — a broken constraint produces no error, just a worse schedule.

Follow `UnavailableEmployeeConstraintTest`:

```java
private final ConstraintVerifier<EmployeeSchedulingConstraintProvider, EmployeeSchedule> constraintVerifier =
        ConstraintVerifier.build(new EmployeeSchedulingConstraintProvider(), EmployeeSchedule.class, Shift.class);
```

Test both that it **penalizes** when it should and that it **stays silent** when it
should not — the second half is what catches an over-broad join.

That test also documents a trap worth remembering: the solver payload is serialized to
JSON by `/demo-data/generate` and deserialized by `POST /schedules`, so each preassigned
shift gets a **copy** of the employee. An identity-based join then produces no rows and
the constraint never fires. If your constraint joins on a fact object, verify `equals`
and `hashCode` exist on it.

## 5. Verify

```
mvn -B -ntp test
```

And run it for real — `run-app` skill. Constraint tests prove the rule fires; only
solving a real dataset shows whether the schedule actually improved. If you changed
weights or units, compare against a schedule produced before the change.
