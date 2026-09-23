package com.example.neo4j.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.neo4j.exception.ConflictException;
import com.example.neo4j.exception.ResourceNotFoundException;

/**
 * The email outbox. Emails are enqueued inside the business transaction that caused them, so the
 * change and its emails are saved together or not at all. OutboxDispatcher sends them later.
 *
 * Retries back off exponentially: 1, 2, 4, 8 ... minutes, capped at 1 hour, up to maxAttempts;
 * after that the email is FAILED until an admin retries it.
 */
@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    static final Duration FIRST_RETRY_DELAY = Duration.ofMinutes(1);
    static final Duration MAX_RETRY_DELAY = Duration.ofHours(1);

    /** While a dispatcher is sending, the email is hidden from others for this long. */
    static final Duration CLAIM_LEASE = Duration.ofMinutes(5);

    private static final int MAX_ERROR_LENGTH = 500;

    private final OutboxEmailRepository repository;
    private final int maxAttempts;

    public OutboxService(OutboxEmailRepository repository,
                         @Value("${app.outbox.max-attempts:8}") int maxAttempts) {
        this.repository = repository;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Adds an email to the outbox. Must be called inside the transaction of the change it is about,
     * so a rolled-back change never leaves an email behind.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String recipient, String subject, String body, String reference) {
        OutboxEmail email = new OutboxEmail();
        email.setRecipient(recipient);
        email.setSubject(subject);
        email.setBody(body);
        email.setReference(reference);
        email.setStatus(OutboxStatus.PENDING);
        email.setAttempts(0);
        email.setCreatedAt(Instant.now());
        email.setNextAttemptAt(email.getCreatedAt());
        repository.save(email);
    }

    // ---- Dispatcher steps (each in its own transaction) ---------------------------

    @Transactional(readOnly = true)
    public List<String> dueIds(Instant now, int limit) {
        return repository.findByStatusAndNextAttemptAtLessThanEqual(OutboxStatus.PENDING, now,
                        PageRequest.of(0, limit, Sort.by("nextAttemptAt")))
                .stream().map(OutboxEmail::getId).toList();
    }

    /**
     * Claims an email for sending by pushing its next attempt past the lease. Saving checks the
     * version, so if another dispatcher claimed it first this throws OptimisticLockingFailureException.
     * Empty if it is no longer pending or due.
     */
    @Transactional
    public Optional<OutboxEmail> claim(String id, Instant now) {
        return repository.findById(id)
                .filter(email -> email.getStatus() == OutboxStatus.PENDING && !email.getNextAttemptAt().isAfter(now))
                .map(email -> {
                    email.setNextAttemptAt(now.plus(CLAIM_LEASE));
                    return repository.save(email);
                });
    }

    @Transactional
    public void markSent(String id, Instant now) {
        repository.findById(id).ifPresent(email -> {
            email.setStatus(OutboxStatus.SENT);
            email.setAttempts(email.getAttempts() + 1);
            email.setSentAt(now);
            email.setNextAttemptAt(null);
            repository.save(email);
        });
    }

    @Transactional
    public void markFailed(String id, String error, Instant now) {
        repository.findById(id).ifPresent(email -> {
            int attempts = email.getAttempts() + 1;
            email.setAttempts(attempts);
            email.setLastError(truncate(error));

            if (attempts >= maxAttempts) {
                email.setStatus(OutboxStatus.FAILED);
                email.setNextAttemptAt(null);
                log.error("Giving up on email {} to {} after {} attempts: {}", id, email.getRecipient(), attempts,
                        email.getLastError());
            } else {
                email.setNextAttemptAt(now.plus(retryDelay(attempts)));
                log.warn("Email {} to {} failed (attempt {} of {}), retrying at {}: {}", id, email.getRecipient(),
                        attempts, maxAttempts, email.getNextAttemptAt(), email.getLastError());
            }
            repository.save(email);
        });
    }

    /** 1, 2, 4, 8 ... minutes after the 1st, 2nd, 3rd, 4th ... failure, capped at 1 hour. */
    static Duration retryDelay(int failedAttempts) {
        Duration delay = FIRST_RETRY_DELAY.multipliedBy(1L << Math.min(failedAttempts - 1, 30));
        return delay.compareTo(MAX_RETRY_DELAY) > 0 ? MAX_RETRY_DELAY : delay;
    }

    @Transactional
    public long deleteSentBefore(Instant cutoff) {
        return repository.deleteSentBefore(cutoff);
    }

    // ---- Admin ----------------------------------------------------------------------

    /** Newest first. */
    @Transactional(readOnly = true)
    public Page<OutboxEmailResponse> search(OutboxStatus status, Pageable pageable) {
        Pageable newestFirst = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        Page<OutboxEmail> page = status == null
                ? repository.findAll(newestFirst)
                : repository.findByStatus(status, newestFirst);
        return page.map(OutboxEmailResponse::from);
    }

    /** Puts a FAILED email back in the queue with a fresh set of attempts. */
    @Transactional
    public OutboxEmailResponse retry(String id) {
        OutboxEmail email = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Outbox email not found with id: " + id));
        if (email.getStatus() != OutboxStatus.FAILED) {
            throw new ConflictException("Only FAILED emails can be retried; this one is " + email.getStatus());
        }

        email.setStatus(OutboxStatus.PENDING);
        email.setAttempts(0);
        email.setNextAttemptAt(Instant.now());
        log.info("Email {} to {} queued for retry by an admin", id, email.getRecipient());
        return OutboxEmailResponse.from(repository.save(email));
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH) + "...";
    }
}
