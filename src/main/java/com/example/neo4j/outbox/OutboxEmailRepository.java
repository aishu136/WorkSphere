package com.example.neo4j.outbox;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEmailRepository extends Neo4jRepository<OutboxEmail, String> {

	List<OutboxEmail> findByStatusAndNextAttemptAtLessThanEqual(OutboxStatus status, Instant now, Pageable pageable);

	Page<OutboxEmail> findByStatus(OutboxStatus status, Pageable pageable);

	// Retention: sent emails contain personal data and aren't needed once delivered. Deletes in batches.
	@Query("""
			MATCH (m:OutboxEmail) WHERE m.status = 'SENT' AND m.sentAt < $cutoff
			WITH m LIMIT 1000
			DETACH DELETE m
			RETURN count(*)
			""")
	long deleteSentBefore(@Param("cutoff") Instant cutoff);
}
