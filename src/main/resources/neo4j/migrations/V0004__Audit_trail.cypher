// Audit events are append-only nodes written in the same transaction as the change they describe.
CREATE CONSTRAINT audit_event_id IF NOT EXISTS FOR (a:AuditEvent) REQUIRE a.id IS UNIQUE;

// Newest-first listing, a record's history, and filtering by who/what.
CREATE INDEX audit_event_seq IF NOT EXISTS FOR (a:AuditEvent) ON (a.seq);
CREATE INDEX audit_event_timestamp IF NOT EXISTS FOR (a:AuditEvent) ON (a.timestamp);
CREATE INDEX audit_event_target IF NOT EXISTS FOR (a:AuditEvent) ON (a.targetType, a.targetId);
CREATE INDEX audit_event_actor IF NOT EXISTS FOR (a:AuditEvent) ON (a.actor);
CREATE INDEX audit_event_action IF NOT EXISTS FOR (a:AuditEvent) ON (a.action);
