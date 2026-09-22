// A login can be linked to at most one employee, and an employee to at most one login.
CREATE CONSTRAINT app_user_employee IF NOT EXISTS FOR (u:AppUser) REQUIRE u.employeeId IS UNIQUE;
