// Uniqueness is enforced by the database, not only by service checks, so two
// concurrent requests can never create duplicates.
CREATE CONSTRAINT employee_id IF NOT EXISTS FOR (e:Employee) REQUIRE e.id IS UNIQUE;
CREATE CONSTRAINT employee_code IF NOT EXISTS FOR (e:Employee) REQUIRE e.employeeCode IS UNIQUE;
CREATE CONSTRAINT employee_email IF NOT EXISTS FOR (e:Employee) REQUIRE e.email IS UNIQUE;
CREATE CONSTRAINT department_id IF NOT EXISTS FOR (d:Department) REQUIRE d.id IS UNIQUE;
CREATE CONSTRAINT department_code IF NOT EXISTS FOR (d:Department) REQUIRE d.code IS UNIQUE;
CREATE CONSTRAINT skill_id IF NOT EXISTS FOR (s:Skill) REQUIRE s.id IS UNIQUE;
CREATE CONSTRAINT skill_name IF NOT EXISTS FOR (s:Skill) REQUIRE s.name IS UNIQUE;
CREATE CONSTRAINT company_id IF NOT EXISTS FOR (c:Company) REQUIRE c.id IS UNIQUE;
CREATE CONSTRAINT app_user_username IF NOT EXISTS FOR (u:AppUser) REQUIRE u.username IS UNIQUE;

// Indexes for the directory filters: name prefix search, sorting and status.
CREATE INDEX employee_search_name IF NOT EXISTS FOR (e:Employee) ON (e.searchName);
CREATE INDEX employee_name IF NOT EXISTS FOR (e:Employee) ON (e.name);
CREATE INDEX employee_status IF NOT EXISTS FOR (e:Employee) ON (e.status);
CREATE INDEX department_name IF NOT EXISTS FOR (d:Department) ON (d.name);
