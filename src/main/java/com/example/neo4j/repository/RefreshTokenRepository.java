package com.example.neo4j.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;

import com.example.neo4j.entity.RefreshToken;

public interface RefreshTokenRepository extends Neo4jRepository<RefreshToken, String> {

	Optional<RefreshToken> findByTokenHash(String tokenHash);

	// Version is bumped so a refresh racing with the revocation fails instead of overwriting it.
	@Query("""
			MATCH (t:RefreshToken {username: $username}) WHERE t.revokedAt IS NULL
			SET t.revokedAt = $now, t.version = coalesce(t.version, 0) + 1
			RETURN count(t)
			""")
	long revokeAll(String username, Instant now);

	@Query("MATCH (t:RefreshToken) WHERE t.expiresAt < $now DETACH DELETE t RETURN count(*)")
	long deleteExpired(Instant now);
}
