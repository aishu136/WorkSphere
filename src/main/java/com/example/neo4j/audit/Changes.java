package com.example.neo4j.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Collects before/after values for the fields an operation actually changed,
 * e.g. {"email": {"from": "a@x.com", "to": "b@x.com"}}. Unchanged fields are skipped.
 */
public final class Changes {

    private final Map<String, Object> fields = new LinkedHashMap<>();

    public Changes track(String field, Object from, Object to) {
        if (!Objects.equals(from, to)) {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("from", from);
            change.put("to", to);
            fields.put(field, change);
        }
        return this;
    }

    public boolean isEmpty() {
        return fields.isEmpty();
    }

    public Map<String, Object> asMap() {
        return fields;
    }
}
