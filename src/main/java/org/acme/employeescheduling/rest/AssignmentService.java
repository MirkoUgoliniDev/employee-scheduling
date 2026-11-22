package org.acme.employeescheduling.rest;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import org.acme.employeescheduling.dto.AssignmentPosition;
import org.acme.employeescheduling.dto.AssignmentRequirement;
import org.acme.employeescheduling.dto.CustomerAssignment;
import org.acme.employeescheduling.persistence.AssignmentRequirementEntity;
import org.acme.employeescheduling.persistence.CustomerAssignmentEntity;
import org.acme.employeescheduling.persistence.CustomerEntity;
import org.acme.employeescheduling.persistence.EmployeeDateEntity;
import org.acme.employeescheduling.persistence.EmployeeEntity;
import org.acme.employeescheduling.persistence.EmployeeLocationEntity;
import org.acme.employeescheduling.persistence.EmployeeSkillEntity;
import org.acme.employeescheduling.persistence.LocationEntity;
import org.acme.employeescheduling.persistence.ShiftEntity;
import org.acme.employeescheduling.persistence.ShiftSkillEntity;
import org.acme.employeescheduling.persistence.SkillEntity;

/**
 * @brief Materialises customer assignments into solver-facing shift rows ("positions").
 *
 * @details This is the seam between the customer-centric model and the existing planner. An
 *          assignment with the team "1 anaesthetist + 2 MFAs" becomes three {@code shifts} rows
 *          in the same transaction; from then on the whole existing machinery applies unchanged
 *          — {@code /demo-data/generate} puts them in the solver payload, the constraints check
 *          skills, availability and rest, {@code /demo-data/save-assignments} persists the
 *          result. No second planner, no duplicated solver.
 *
 *          <p><b>Minimal churn.</b> {@link #regeneratePositions(int)} pairs each wanted position
 *          with an already existing shift of the same role and qualification <i>in list order</i>
 *          and updates that row in place, so its {@code id}, its {@code employee_id} and its
 *          {@code pinned} flag survive a duration change. Only positions without a counterpart
 *          are inserted, and only surplus rows are deleted: the planner changes a duration and
 *          keeps the team, instead of getting a completely re-planned week.
 *
 *          <p><b>Locked assignments that became invalid are reported, not hidden.</b> A pinned
 *          position whose employee is meanwhile absent, ineligible for the location or lacking
 *          the role/qualification is returned by {@link #conflicts(int, String, String)} so the
 *          UI can demand a decision. The solver cannot repair it either (a pin forbids changing
 *          the employee), so silence would look like a valid plan.
 */
@ApplicationScoped
public class AssignmentService {

    private static final DateTimeFormatter DB_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Upper bound for one materialised assignment: protects the solver from a typo like 3000. */
    static final int MAX_POSITIONS = 40;

    // -----------------------------------------------------------------------
    // Write side
    // -----------------------------------------------------------------------

    /**
     * @brief Creates an assignment, its requirements and its positions.
     * @return the new assignment ID
     */
    @Transactional
    public int create(CustomerAssignmentEntity assignment, List<AssignmentRequirementEntity> requirements) {
        assignment.createdAt = LocalDateTime.now().format(DB_FORMAT);
        assignment.persist();
        for (AssignmentRequirementEntity requirement : requirements) {
            requirement.assignmentId = assignment.id;
            requirement.persist();
        }
        regeneratePositions(assignment.id);
        return assignment.id;
    }

    /**
     * @brief Applies a new header and requirement set to an existing assignment, then re-syncs.
     *
     * @details It has to be <b>one</b> transaction: the header (duration, start, status) is written
     *          first and {@link #regeneratePositions(int)} then reads it back to recompute the
     *          positions. Annotating the JAX-RS method instead would leave the entity detached
     *          between the two calls, so the regenerated positions would still follow the old
     *          duration — which is exactly the defect this method exists to prevent.
     */
    @Transactional
    public void update(int assignmentId, CustomerAssignment assignment,
            List<AssignmentRequirementEntity> requirements) {
        CustomerAssignmentEntity entity = CustomerAssignmentEntity.findById(assignmentId);
        if (entity == null) {
            return;
        }
        entity.customerId = assignment.getCustomerId();
        entity.locationId = assignment.getLocationId();
        entity.title = assignment.getTitle().trim();
        entity.service = assignment.getService() == null ? "" : assignment.getService();
        entity.assignmentDate = assignment.getAssignmentDate();
        entity.startTime = assignment.getStartTime();
        entity.durationMinutes = assignment.getDurationMinutes();
        entity.priority = assignment.getPriority();
        entity.status = assignment.getStatus();
        entity.fixedTime = assignment.isFixedTime();
        entity.notes = assignment.getNotes() == null ? "" : assignment.getNotes();
        entity.seriesId = assignment.getSeriesId();
        replaceRequirements(assignmentId, requirements);
        regeneratePositions(assignmentId);
    }

    /** @brief Replaces the requirement rows of an existing assignment. */
    @Transactional
    public void replaceRequirements(int assignmentId, List<AssignmentRequirementEntity> requirements) {
        AssignmentRequirementEntity.delete("assignmentId = ?1", assignmentId);
        for (AssignmentRequirementEntity requirement : requirements) {
            requirement.assignmentId = assignmentId;
            requirement.persist();
        }
    }

    /**
     * @brief Brings the shift rows of an assignment in line with its requirements.
     *
     * @details Matching is by churn key (role + qualification), consumed in list order, so an
     *          unchanged requirement keeps its shift rows — and with them the employees already
     *          planned and any manual pin. A cancelled or completed assignment keeps no open
     *          positions: its rows are deleted, because a cancelled visit must not hold staff.
     *
     * @return the number of positions after regeneration
     */
    @Transactional
    public int regeneratePositions(int assignmentId) {
        CustomerAssignmentEntity assignment = CustomerAssignmentEntity.findById(assignmentId);
        if (assignment == null) {
            return 0;
        }
        List<ShiftEntity> existing = ShiftEntity.list("assignmentId = ?1 order by id", assignmentId);
        Map<Integer, Integer> requiredSkillByShift = requiredSkillByShift(existingIds(existing));

        if (!AssignmentMaterializer.PLANNED_STATUSES.contains(assignment.status)) {
            deleteShifts(existing);
            return 0;
        }

        List<AssignmentRequirementEntity> requirements = AssignmentRequirementEntity.list(
                "assignmentId = ?1 order by id", assignmentId);
        List<AssignmentMaterializer.Position> wanted = AssignmentMaterializer.materialize(
                requirements.stream()
                        .map(r -> new AssignmentMaterializer.Requirement(r.role, r.skillId, r.headcount))
                        .toList());

        LocalDateTime start = AssignmentMaterializer.startAt(assignment.assignmentDate, assignment.startTime);
        LocalDateTime end = start.plusMinutes(assignment.durationMinutes);
        String dbStart = start.format(DB_FORMAT);
        String dbEnd = end.format(DB_FORMAT);

        // Deque per churn key: an unchanged duration therefore reuses the very same rows, and a
        // duration change only rewrites start/end on them.
        Map<String, Deque<ShiftEntity>> pool = new LinkedHashMap<>();
        for (ShiftEntity shift : existing) {
            pool.computeIfAbsent(
                    churnKey(shift.requiredRole, requiredSkillByShift.get(shift.id)),
                    key -> new ArrayDeque<>()).add(shift);
        }

        Set<Integer> kept = new HashSet<>();
        for (AssignmentMaterializer.Position position : wanted) {
            Deque<ShiftEntity> candidates = pool.get(position.churnKey());
            ShiftEntity shift = candidates == null ? null : candidates.pollFirst();
            boolean created = shift == null;
            if (created) {
                shift = new ShiftEntity();
                shift.assignmentId = assignmentId;
                shift.employeeId = null;
                shift.pinned = false;
            }
            shift.locationId = assignment.locationId;
            shift.startTime = dbStart;
            shift.endTime = dbEnd;
            shift.requiredRole = position.role();
            if (created) {
                shift.persist();
            }
            syncRequiredSkill(shift, position.skillId(), requiredSkillByShift.get(shift.id));
            kept.add(shift.id);
        }

        List<ShiftEntity> surplus = existing.stream().filter(shift -> !kept.contains(shift.id)).toList();
        deleteShifts(surplus);
        return wanted.size();
    }

    /** @brief Deletes an assignment with its requirements and materialised positions. */
    @Transactional
    public boolean delete(int assignmentId) {
        deleteShifts(ShiftEntity.list("assignmentId = ?1", assignmentId));
        AssignmentRequirementEntity.delete("assignmentId = ?1", assignmentId);
        return CustomerAssignmentEntity.deleteById(assignmentId);
    }

    /** @brief Pins or unpins every position of an assignment (manual lock). */
    @Transactional
    public int setPinned(int assignmentId, boolean pinned) {
        List<ShiftEntity> shifts = ShiftEntity.list("assignmentId = ?1", assignmentId);
        for (ShiftEntity shift : shifts) {
            shift.pinned = pinned;
        }
        return shifts.size();
    }

    // -----------------------------------------------------------------------
    // Read side
    // -----------------------------------------------------------------------

    /**
     * @brief Lists assignments of a period with their requirements and positions.
     * @param from inclusive "yyyy-MM-dd"; null means no lower bound
     * @param to   inclusive "yyyy-MM-dd"; null means no upper bound
     */
    public List<CustomerAssignment> list(int structureId, String from, String to) {
        List<CustomerAssignmentEntity> assignments = CustomerAssignmentEntity.list(
                "structureId = ?1 and assignmentDate >= ?2 and assignmentDate <= ?3"
                        + " order by assignmentDate, startTime, id",
                structureId, orDefault(from, "0000-01-01"), orDefault(to, "9999-12-31"));
        if (assignments.isEmpty()) {
            return List.of();
        }

        Set<Integer> assignmentIds = assignments.stream().map(a -> a.id).collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        Map<Integer, List<AssignmentRequirementEntity>> requirementsByAssignment =
                DemoDataRepository.loadInChunks(assignmentIds,
                        chunk -> AssignmentRequirementEntity.<AssignmentRequirementEntity>list(
                                "assignmentId in ?1 order by id", chunk))
                        .stream().collect(Collectors.groupingBy(r -> r.assignmentId));
        Map<Integer, List<ShiftEntity>> shiftsByAssignment =
                DemoDataRepository.loadInChunks(assignmentIds,
                        chunk -> ShiftEntity.<ShiftEntity>list(
                                "assignmentId in ?1 order by startTime, id", chunk))
                        .stream().collect(Collectors.groupingBy(s -> s.assignmentId));

        Map<Integer, String> customerNames = CustomerEntity.<CustomerEntity>list(
                        "id in ?1", idsOf(assignments.stream().map(a -> a.customerId).toList()))
                .stream().collect(Collectors.toMap(c -> c.id, c -> c.name));
        Map<Integer, String> locationNames = LocationEntity.<LocationEntity>list(
                        "id in ?1", idsOf(assignments.stream().map(a -> a.locationId).toList()))
                .stream().collect(Collectors.toMap(l -> l.id, l -> l.name));
        Map<Integer, String> skillNames = SkillEntity.<SkillEntity>list("structureId = ?1", structureId)
                .stream().collect(Collectors.toMap(s -> s.id, s -> s.name));

        List<ShiftEntity> allShifts = shiftsByAssignment.values().stream()
                .flatMap(List::stream).toList();
        Map<Integer, Integer> requiredSkillByShift = requiredSkillByShift(existingIds(allShifts));
        Map<Integer, String> employeeNames = employeeNames(allShifts);

        List<CustomerAssignment> result = new ArrayList<>(assignments.size());
        for (CustomerAssignmentEntity entity : assignments) {
            CustomerAssignment dto = toDto(entity, customerNames, locationNames,
                    requirementsByAssignment.getOrDefault(entity.id, List.of()),
                    shiftsByAssignment.getOrDefault(entity.id, List.of()),
                    requiredSkillByShift, skillNames, employeeNames);
            result.add(dto);
        }
        return result;
    }

    /** @brief Loads one assignment, or null when it does not belong to the structure. */
    public CustomerAssignment load(int structureId, int assignmentId) {
        CustomerAssignmentEntity entity = CustomerAssignmentEntity.findById(assignmentId);
        if (entity == null || entity.structureId != structureId) {
            return null;
        }
        return list(structureId, entity.assignmentDate, entity.assignmentDate).stream()
                .filter(dto -> dto.getId() == assignmentId).findFirst().orElse(null);
    }

    /**
     * @brief Open positions (no employee) in a period, across all assignments.
     * @details Unfilled positions are first-class: the planner sees exactly which roles are
     *          uncovered instead of an assignment that silently counts as staffed.
     */
    public List<AssignmentPosition> unfilledPositions(int structureId, String from, String to) {
        List<CustomerAssignment> assignments = list(structureId, from, to);
        List<AssignmentPosition> unfilled = new ArrayList<>();
        for (CustomerAssignment assignment : assignments) {
            for (AssignmentPosition position : assignment.getPositions()) {
                if (position.getEmployeeId() == null) {
                    unfilled.add(position);
                }
            }
        }
        return unfilled;
    }

    /**
     * @brief Pinned positions whose employee no longer satisfies the requirement.
     *
     * @details Returned as {@code [{shiftId, assignmentId, employeeName, conflictType, detail}]}.
     *          {@code conflictType} is one of {@code ABSENCE}, {@code ROLE}, {@code LOCATION},
     *          {@code SKILL}. The solver cannot fix any of them (the pin forbids a change), so
     *          they must reach the planner.
     */
    public List<Map<String, Object>> conflicts(int structureId, String from, String to) {
        List<CustomerAssignment> assignments = list(structureId, from, to);
        Set<Integer> shiftIds = assignments.stream()
                .flatMap(a -> a.getPositions().stream())
                .map(AssignmentPosition::getId)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (shiftIds.isEmpty()) {
            return List.of();
        }
        List<ShiftEntity> shifts = DemoDataRepository.loadInChunks(shiftIds,
                chunk -> ShiftEntity.<ShiftEntity>list("id in ?1", chunk));
        Set<Integer> employeeIds = shifts.stream()
                .filter(s -> s.pinned && s.employeeId != null)
                .map(s -> s.employeeId)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (employeeIds.isEmpty()) {
            return List.of();
        }

        Map<Integer, EmployeeEntity> employees = DemoDataRepository.loadInChunks(employeeIds,
                        chunk -> EmployeeEntity.<EmployeeEntity>list("id in ?1", chunk))
                .stream().collect(Collectors.toMap(e -> e.id, e -> e));
        Map<Integer, Set<Integer>> skillsByEmployee = DemoDataRepository.loadInChunks(employeeIds,
                        chunk -> EmployeeSkillEntity.<EmployeeSkillEntity>list("employeeId in ?1", chunk))
                .stream().collect(Collectors.groupingBy(s -> s.employeeId,
                        Collectors.mapping(s -> s.skillId, Collectors.toSet())));
        Map<Integer, Set<Integer>> locationsByEmployee = DemoDataRepository.loadInChunks(employeeIds,
                        chunk -> EmployeeLocationEntity.<EmployeeLocationEntity>list("employeeId in ?1", chunk))
                .stream().collect(Collectors.groupingBy(l -> l.employeeId,
                        Collectors.mapping(l -> l.locationId, Collectors.toSet())));
        List<EmployeeDateEntity> unavailable = DemoDataRepository.loadInChunks(employeeIds,
                chunk -> EmployeeDateEntity.<EmployeeDateEntity>list(
                        "employeeId in ?1 and dateTypeId = ?2", chunk, EmployeeDateEntity.TYPE_UNAVAILABLE));

        Map<Integer, Integer> requiredSkillByShift = requiredSkillByShift(existingIds(shifts));
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (ShiftEntity shift : shifts) {
            if (!shift.pinned || shift.employeeId == null) {
                continue;
            }
            EmployeeEntity employee = employees.get(shift.employeeId);
            if (employee == null) {
                continue;
            }
            LocalDateTime start = parseDbDateTime(shift.startTime);
            LocalDateTime end = parseDbDateTime(shift.endTime);

            for (EmployeeDateEntity absence : unavailable) {
                if (absence.employeeId != employee.id) {
                    continue;
                }
                LocalDateTime absenceStart = EmployeeDateEntity.parseDbDateTime(absence.dateStart);
                LocalDateTime absenceEnd = EmployeeDateEntity.parseDbDateTime(absence.dateEnd);
                if (absenceStart == null || absenceEnd == null) {
                    continue;
                }
                if (absenceStart.isBefore(end) && absenceEnd.isAfter(start)) {
                    conflicts.add(conflict(shift, employee, "ABSENCE", absence.dateStart + " .. " + absence.dateEnd));
                }
            }
            if (!shift.requiredRole.isBlank() && !shift.requiredRole.equals(employee.role)) {
                conflicts.add(conflict(shift, employee, "ROLE", shift.requiredRole));
            }
            Set<Integer> allowed = locationsByEmployee.getOrDefault(employee.id, Set.of());
            if (!allowed.isEmpty() && !allowed.contains(shift.locationId)) {
                conflicts.add(conflict(shift, employee, "LOCATION", "location " + shift.locationId));
            }
            Integer requiredSkill = requiredSkillByShift.get(shift.id);
            if (requiredSkill != null
                    && !skillsByEmployee.getOrDefault(employee.id, Set.of()).contains(requiredSkill)) {
                conflicts.add(conflict(shift, employee, "SKILL", "skill " + requiredSkill));
            }
        }
        return conflicts;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** @brief Churn key of a position: role plus qualification, independent of the row ID. */
    static String churnKey(String role, Integer skillId) {
        return (role == null ? "" : role) + "|" + (skillId == null ? "" : skillId);
    }

    /** @brief Required (type 1) qualification per shift row, in one query. */
    private static Map<Integer, Integer> requiredSkillByShift(Set<Integer> shiftIds) {
        if (shiftIds.isEmpty()) {
            return Map.of();
        }
        Map<Integer, Integer> result = new HashMap<>();
        for (ShiftSkillEntity row : DemoDataRepository.loadInChunks(shiftIds,
                chunk -> ShiftSkillEntity.<ShiftSkillEntity>list(
                        "shiftId in ?1 and skillTypeId = ?2", chunk, ShiftSkillEntity.TYPE_REQUIRED))) {
            result.putIfAbsent(row.shiftId, row.skillId);
        }
        return result;
    }

    /** @brief Rewrites the required qualification of one shift only when it actually changed. */
    private static void syncRequiredSkill(ShiftEntity shift, Integer skillId, Integer currentSkillId) {
        if (Objects.equals(skillId, currentSkillId)) {
            return;
        }
        ShiftSkillEntity.delete("shiftId = ?1 and skillTypeId = ?2", shift.id, ShiftSkillEntity.TYPE_REQUIRED);
        if (skillId != null) {
            ShiftSkillEntity row = new ShiftSkillEntity();
            row.shiftId = shift.id;
            row.skillId = skillId;
            row.skillTypeId = ShiftSkillEntity.TYPE_REQUIRED;
            row.persist();
        }
    }

    private static void deleteShifts(List<ShiftEntity> shifts) {
        for (ShiftEntity shift : shifts) {
            ShiftSkillEntity.delete("shiftId = ?1", shift.id);
            shift.delete();
        }
    }

    private static Set<Integer> existingIds(List<ShiftEntity> shifts) {
        return shifts.stream().map(s -> s.id).collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private static Set<Integer> idsOf(List<Integer> values) {
        return values.stream().filter(Objects::nonNull)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private static Map<Integer, String> employeeNames(List<ShiftEntity> shifts) {
        Set<Integer> ids = shifts.stream().map(s -> s.employeeId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (ids.isEmpty()) {
            return Map.of();
        }
        return DemoDataRepository.loadInChunks(ids, chunk -> EmployeeEntity.<EmployeeEntity>list("id in ?1", chunk))
                .stream().collect(Collectors.toMap(e -> e.id, e -> (e.firstName + " " + e.lastName).trim()));
    }

    private static Map<String, Object> conflict(ShiftEntity shift, EmployeeEntity employee,
            String conflictType, String detail) {
        Map<String, Object> conflict = new LinkedHashMap<>();
        conflict.put("shiftId", shift.id);
        conflict.put("assignmentId", shift.assignmentId);
        conflict.put("employeeId", employee.id);
        conflict.put("employeeName", (employee.firstName + " " + employee.lastName).trim());
        conflict.put("conflictType", conflictType);
        conflict.put("detail", detail);
        return conflict;
    }

    private static LocalDateTime parseDbDateTime(String value) {
        return EmployeeDateEntity.parseDbDateTime(value);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static CustomerAssignment toDto(CustomerAssignmentEntity entity,
            Map<Integer, String> customerNames, Map<Integer, String> locationNames,
            List<AssignmentRequirementEntity> requirements, List<ShiftEntity> shifts,
            Map<Integer, Integer> requiredSkillByShift, Map<Integer, String> skillNames,
            Map<Integer, String> employeeNames) {
        CustomerAssignment dto = new CustomerAssignment();
        dto.setId(entity.id);
        dto.setStructureId(entity.structureId);
        dto.setCustomerId(entity.customerId);
        dto.setCustomerName(customerNames.getOrDefault(entity.customerId, ""));
        dto.setLocationId(entity.locationId);
        dto.setLocationName(locationNames.getOrDefault(entity.locationId, ""));
        dto.setTitle(entity.title);
        dto.setService(entity.service);
        dto.setAssignmentDate(entity.assignmentDate);
        dto.setStartTime(entity.startTime);
        dto.setDurationMinutes(entity.durationMinutes);
        dto.setEndTime(LocalDateTime.parse(entity.assignmentDate + "T" + entity.startTime)
                .plusMinutes(entity.durationMinutes).toLocalTime().toString());
        dto.setPriority(entity.priority);
        dto.setStatus(entity.status);
        dto.setFixedTime(entity.fixedTime);
        dto.setNotes(entity.notes);
        dto.setSeriesId(entity.seriesId);

        int required = 0;
        for (AssignmentRequirementEntity requirement : requirements) {
            AssignmentRequirement line = new AssignmentRequirement();
            line.setId(requirement.id);
            line.setAssignmentId(requirement.assignmentId);
            line.setRole(requirement.role);
            line.setSkillId(requirement.skillId);
            line.setSkillName(requirement.skillId == null ? "" : skillNames.getOrDefault(requirement.skillId, ""));
            line.setHeadcount(requirement.headcount);
            dto.getRequirements().add(line);
            required += Math.max(0, requirement.headcount);
        }

        int filled = 0;
        for (ShiftEntity shift : shifts) {
            AssignmentPosition position = new AssignmentPosition();
            position.setId(shift.id);
            position.setRole(shift.requiredRole);
            Integer skillId = requiredSkillByShift.get(shift.id);
            position.setSkillId(skillId);
            position.setSkillName(skillId == null ? "" : skillNames.getOrDefault(skillId, ""));
            position.setEmployeeId(shift.employeeId);
            position.setEmployeeName(shift.employeeId == null
                    ? "" : employeeNames.getOrDefault(shift.employeeId, ""));
            position.setStart(shift.startTime);
            position.setEnd(shift.endTime);
            position.setPinned(shift.pinned);
            position.setVersion(shift.version);
            dto.getPositions().add(position);
            if (shift.employeeId != null) {
                filled++;
            }
        }
        dto.setRequiredHeadcount(required);
        dto.setFilledPositions(filled);
        dto.setUnfilledPositions(Math.max(0, required - filled));
        return dto;
    }
}
