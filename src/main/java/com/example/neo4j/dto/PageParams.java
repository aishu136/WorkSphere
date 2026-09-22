package com.example.neo4j.dto;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Binds ?page=&size= query parameters. The size cap stops a single request
 * from pulling the entire directory.
 */
public record PageParams(

        @Min(0)
        Integer page,

        @Min(1)
        @Max(MAX_SIZE)
        Integer size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public Pageable toPageable() {
        return PageRequest.of(page == null ? 0 : page, size == null ? DEFAULT_SIZE : size);
    }
}
