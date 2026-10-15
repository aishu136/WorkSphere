// Leave requests: an employee asks for time off, a manager above them or HR approves or rejects.
CREATE CONSTRAINT leave_request_id IF NOT EXISTS FOR (r:LeaveRequest) REQUIRE r.id IS UNIQUE;
CREATE INDEX leave_request_employee IF NOT EXISTS FOR (r:LeaveRequest) ON (r.employeeId, r.status);
CREATE INDEX leave_request_dates IF NOT EXISTS FOR (r:LeaveRequest) ON (r.startDate, r.endDate);
