// Staffing requests: a project lead asks to add/change/remove a member, HR approves or rejects.
CREATE CONSTRAINT staffing_request_id IF NOT EXISTS FOR (r:StaffingRequest) REQUIRE r.id IS UNIQUE;
CREATE INDEX staffing_request_status IF NOT EXISTS FOR (r:StaffingRequest) ON (r.status);
CREATE INDEX staffing_request_project IF NOT EXISTS FOR (r:StaffingRequest) ON (r.projectId, r.status);
