package com.example.neo4j.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.example.neo4j.dto.SkillRequest;
import com.example.neo4j.dto.SkillResponse;
import com.example.neo4j.entity.Skill;
import com.example.neo4j.repository.SkillRepository;

/**
 * What a change to a set of skills adds, removes and re-levels. Employee skills and project
 * required skills share it, so both follow the same rules and record the same audit details.
 * Lists are sorted by name so audit details are stable.
 */
record SkillChanges(List<SkillResponse> added, List<SkillResponse> removed, List<LevelChange> levelChanged) {

    private static final Comparator<SkillResponse> BY_NAME =
            Comparator.comparing(SkillResponse::name, String.CASE_INSENSITIVE_ORDER);

    // skill has the new level.
    record LevelChange(SkillResponse skill, int from) {

        @Override
        public String toString() {
            return skill.name() + ": " + from + " -> " + skill.level();
        }
    }

    static SkillChanges between(List<SkillResponse> before, List<SkillResponse> after) {
        Map<String, SkillResponse> old = byId(before);
        Map<String, SkillResponse> now = byId(after);

        return new SkillChanges(
                sorted(after.stream().filter(s -> !old.containsKey(s.id()))),
                sorted(before.stream().filter(s -> !now.containsKey(s.id()))),
                sorted(after.stream().filter(s -> old.containsKey(s.id()) && old.get(s.id()).level() != s.level()))
                        .stream().map(s -> new LevelChange(s, old.get(s.id()).level())).toList());
    }

    /**
     * The skills after replacing them all with the requested ones. New skill names create skill nodes.
     * If a skill is named twice, the first one counts.
     */
    static List<SkillResponse> replaced(List<SkillResponse> current, List<SkillRequest> requests,
                                        SkillRepository skills) {
        Map<String, SkillResponse> old = byId(current);
        Map<String, SkillResponse> after = new LinkedHashMap<>();

        for (SkillRequest request : requests) {
            Skill skill = skills.findOrCreate(request.name());
            after.putIfAbsent(skill.getId(), new SkillResponse(skill.getId(), skill.getName(),
                    levelFor(request, old.get(skill.getId()))));
        }
        return List.copyOf(after.values());
    }

    // The skills after adding one, or changing its level if it is already there.
    static List<SkillResponse> added(List<SkillResponse> current, SkillRequest request, SkillRepository skills) {
        Skill skill = skills.findOrCreate(request.name());
        SkillResponse existing = byId(current).get(skill.getId());

        List<SkillResponse> after = new ArrayList<>(current.stream()
                .filter(s -> !s.id().equals(skill.getId())).toList());
        after.add(new SkillResponse(skill.getId(), skill.getName(), levelFor(request, existing)));
        return after;
    }

    static List<SkillResponse> without(List<SkillResponse> current, String skillId) {
        return current.stream().filter(s -> !s.id().equals(skillId)).toList();
    }

    boolean isEmpty() {
        return added.isEmpty() && removed.isEmpty() && levelChanged.isEmpty();
    }

    List<String> removedIds() {
        return removed.stream().map(SkillResponse::id).toList();
    }

    // Skills to create or update, with their new level.
    List<SkillResponse> toWrite() {
        return Stream.concat(added.stream(), levelChanged.stream().map(LevelChange::skill)).toList();
    }

    // added and removed are names, as before levels existed; levels has the new level of each added or changed skill.
    Map<String, Object> details() {
        Map<String, Integer> levels = new LinkedHashMap<>();
        toWrite().forEach(s -> levels.put(s.name(), s.level()));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("added", added.stream().map(SkillResponse::name).toList());
        details.put("removed", removed.stream().map(SkillResponse::name).toList());
        details.put("levelChanged", levelChanged.stream().map(LevelChange::toString).toList());
        details.put("levels", levels);
        return details;
    }

    // Without a level, a skill already held keeps its level and a new one gets the default.
    private static int levelFor(SkillRequest request, SkillResponse existing) {
        if (request.level() != null) {
            return request.level();
        }
        return existing == null ? Skill.DEFAULT_LEVEL : existing.level();
    }

    private static Map<String, SkillResponse> byId(List<SkillResponse> skills) {
        return skills.stream().collect(Collectors.toMap(SkillResponse::id, Function.identity(), (a, b) -> a));
    }

    private static List<SkillResponse> sorted(Stream<SkillResponse> skills) {
        return skills.sorted(BY_NAME).toList();
    }
}
