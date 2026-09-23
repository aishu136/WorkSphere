// Transactional outbox: emails are saved with the change that caused them and sent (and retried) by a background job.
CREATE CONSTRAINT outbox_email_id IF NOT EXISTS FOR (m:OutboxEmail) REQUIRE m.id IS UNIQUE;
CREATE INDEX outbox_email_due IF NOT EXISTS FOR (m:OutboxEmail) ON (m.status, m.nextAttemptAt);
CREATE INDEX outbox_email_sent IF NOT EXISTS FOR (m:OutboxEmail) ON (m.status, m.sentAt);
