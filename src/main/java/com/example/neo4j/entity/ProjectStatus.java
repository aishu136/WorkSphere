package com.example.neo4j.entity;

import java.util.Arrays;
import java.util.stream.Collectors;

public enum ProjectStatus {

    PLANNED(true),
    ACTIVE(true),
    ON_HOLD(true),
    COMPLETED(false),
    CANCELLED(false);

    /** Cypher list of open statuses, for queries that sum allocation, e.g. ['PLANNED','ACTIVE','ON_HOLD']. */
    public static final String OPEN_CYPHER_LIST = Arrays.stream(values())
            .filter(ProjectStatus::isOpen)
            .map(status -> "'" + status.name() + "'")
            .collect(Collectors.joining(",", "[", "]"));

    private final boolean open;

    ProjectStatus(boolean open) {
        this.open = open;
    }

    /** Open projects count toward an employee's allocation; finished ones are kept as history only. */
    public boolean isOpen() {
        return open;
    }
}
