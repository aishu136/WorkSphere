package com.example.neo4j.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/**
 * Sends emails from the outbox on a schedule. Each email is claimed first (so several app
 * instances never send it at the same moment), then sent outside any database transaction,
 * then marked SENT or scheduled for a retry.
 *
 * Delivery is at-least-once: if an instance dies after sending but before recording it, the claim
 * expires and the email can be sent again.
 *
 * Without a configured mail server (spring.mail.host), emails stay PENDING until one is configured.
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxService outbox;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final String from;
    private final int batchSize;
    private final Duration retention;

    public OutboxDispatcher(OutboxService outbox, ObjectProvider<JavaMailSender> mailSender,
                            @Value("${app.notifications.from:no-reply@worksphere.local}") String from,
                            @Value("${app.outbox.batch-size:50}") int batchSize,
                            @Value("${app.outbox.retention:P30D}") Duration retention) {
        this.outbox = outbox;
        this.mailSender = mailSender;
        this.from = from;
        this.batchSize = batchSize;
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval:PT30S}",
            initialDelayString = "${app.outbox.initial-delay:PT10S}")
    public void run() {
        Instant now = Instant.now();
        try {
            dispatchDue(now);
            long deleted = outbox.deleteSentBefore(now.minus(retention));
            if (deleted > 0) {
                log.info("Deleted {} sent email(s) older than {}", deleted, retention);
            }
        } catch (Exception e) {
            // Keep the schedule alive; the next run tries again.
            log.error("Email outbox run failed", e);
        }
    }

    /** Sends every email due at {@code now}. Returns how many were sent. */
    public int dispatchDue(Instant now) {

        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            log.debug("No mail server configured; outbox emails wait until one is");
            return 0;
        }

        int sent = 0;
        for (String id : outbox.dueIds(now, batchSize)) {

            Optional<OutboxEmail> claimed;
            try {
                claimed = outbox.claim(id, now);
            } catch (OptimisticLockingFailureException | TransactionException e) {
                log.debug("Email {} was claimed by another dispatcher", id);
                continue;
            }
            if (claimed.isEmpty()) {
                continue;
            }

            OutboxEmail email = claimed.get();
            try {
                sender.send(toMessage(email));
                outbox.markSent(id, now);
                sent++;
            } catch (Exception e) {
                outbox.markFailed(id, e.getClass().getSimpleName() + ": " + e.getMessage(), now);
            }
        }
        return sent;
    }

    private SimpleMailMessage toMessage(OutboxEmail email) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email.getRecipient());
        message.setSubject(email.getSubject());
        message.setText(email.getBody());
        return message;
    }
}
