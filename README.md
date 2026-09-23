# WorkSphere

An employee management system built on a graph database. WorkSphere models an organisation the way it actually
is (people, reporting lines, departments inside departments, skills) and uses that graph for search,
permissions and an AI assistant.

**Stack:** Java 21 · Spring Boot 3.5 · Spring Data Neo4j · Neo4j 5 / Aura · Spring Security (JWT) ·
neo4j-migrations · Apache Camel · LangGraph4j + LangChain4j + Amazon Bedrock

## Features

- **Org chart.** Every employee has a manager, and departments can sit inside other departments. You can
  list someone's direct reports, everyone below them at any depth, or their chain of managers. The API
  rejects changes that would create a loop.
- **Employee lifecycle.** Create, update and put on leave. Terminating an employee keeps their record,
  removes their reporting, department and office links and their open project assignments, and
  disables their login. Finished project assignments are kept as history.
- **Directory search.** Filter by name prefix, status, department, skill, company, office and free
  capacity. All list endpoints are paginated.
- **Offices.** Each employee can be placed in one office. Offices can be searched by name or city.
- **Projects and staffing.** Projects have a status, dates, an owning department and a lead. People
  join with a role and an allocation percentage. **Nobody can be allocated more than 100% across
  open projects**, and search can find people with free capacity, e.g.
  `/employees?skill=java&maxAllocation=50`.
- **Staffing approvals.** A project lead can request to add, change or remove people on their own
  open project. HR approves or rejects each request, and the change is only applied on approval.
  HR, the lead and the affected employee are notified by email, with automatic retries. See
  [Staffing approvals](#staffing-approvals).
- **Roles and manager self-service.** Roles are ADMIN, HR and EMPLOYEE. A login linked to an employee
  can manage that employee's team. The org chart decides who is in the team, so there is no separate
  manager role to keep in sync.
- **Audit trail.** Every change and every login attempt is recorded. Updates store only the fields
  that changed, with their before and after values. Each event is written in the same transaction as
  its change, and events can't be edited or deleted.
- **AI assistant (LangGraph4j agent).** Ask questions about the organisation in plain English, for
  example "Who in Engineering knows Kafka?" or "Who does Priya's manager report to?". The assistant
  looks the answer up in the org chart before replying, and never changes data. See
  [AI assistant](#ai-assistant).
- **Versioned database migrations.** Constraints, indexes and data conversions run automatically at
  startup.

## Data model

```
(:Employee)-[:REPORTS_TO]->(:Employee)        manager
(:Employee)-[:MEMBER_OF]->(:Department)       department membership
(:Department)-[:PART_OF]->(:Department)       sub-departments, any depth
(:Department)-[:HEADED_BY]->(:Employee)       department head
(:Employee)-[:HAS_SKILL]->(:Skill)
(:Employee)-[:WORKS_FOR]->(:Company)
(:Employee)-[:LOCATED_AT]->(:Office)
(:Employee)-[:WORKS_ON {role, allocationPercent, since}]->(:Project)
(:Project)-[:LED_BY]->(:Employee)             project lead
(:Project)-[:OWNED_BY]->(:Department)         owning department
(:AppUser {employeeId})                        login, optionally linked to an employee
(:AuditEvent)                                  append-only audit trail
```

The `Employee` and `Department` entities map only their links to skills and companies. All other
links (manager, membership, parent, head, office, projects) are read and written with targeted Cypher
in the `*Queries` classes. That way, loading one employee never loads the rest of the organisation.

### Projects and allocation

- Project status is `PLANNED`, `ACTIVE`, `ON_HOLD`, `COMPLETED` or `CANCELLED`. The first three
  count as **open**.
- An employee's allocation is the sum of `allocationPercent` over their open projects. It can never
  exceed 100%. This is checked when someone joins a project or their allocation changes, and when a
  finished project is reopened.
- Assignments on completed and cancelled projects are kept as history but don't count toward
  allocation. People can only join open projects.
- A project with any members, including past ones, can't be deleted. Set it to `CANCELLED` instead.

### Staffing approvals

Project leads don't change their project's members directly. They raise a request, and HR decides it:

```
lead: POST /projects/{id}/staffing-requests  ->  PENDING
                                                  |-- HR approves -> APPROVED (change applied)
                                                  |-- HR rejects  -> REJECTED (reason recorded)
                                                  |-- lead cancels -> CANCELLED
```

- Only the project's **current lead** can raise a request. They need a login linked to their employee
  record, and they can't be terminated.
- Requests that could never be applied are refused straight away: a finished project, someone who
  would go over 100%, removing someone who isn't on the project, or a second pending request for the
  same person.
- On approval, every rule is **checked again** against the current state. If one now fails, for
  example because the person was booked elsewhere in the meantime, HR gets a 409 and the request stays
  pending.
- **Nobody can approve their own request**, even if they are both the lead and HR.
- If two HR users decide the same request at the same moment, only the first one succeeds; the
  second gets a 409.
- Every step, and the resulting member change, is recorded in the project's history.

#### Email notifications

| When | Who is emailed |
|---|---|
| A request is raised | The HR mailbox (`NOTIFY_HR_EMAIL`) |
| A request is approved | The lead who asked, and the employee who was added or removed |
| A request is rejected | The lead who asked, with HR's reason |
| A request is withdrawn | The HR mailbox |

- Leads and employees are emailed at the address on their employee record. People without an
  email address are skipped, and the skip is logged.
- Emails are plain text. Line breaks are removed from subject lines, because names come from user
  input.

#### Email outbox and retries

Emails go through a **transactional outbox**:

1. Each email is saved as an `(:OutboxEmail)` record **in the same transaction as the change**. They
   are saved together or not at all: no email about a rolled-back change, and no saved change without
   its email.
2. A background job (every 30 seconds by default) sends pending emails.
3. A failed send is retried after 1, 2, 4, 8 ... minutes (at most 1 hour apart), up to 8 attempts.
   The email is then marked **FAILED**. An admin can see it at `GET /outbox?status=FAILED` and requeue
   it with `POST /outbox/{id}/retry`.
4. A mail server problem never fails or undoes the change itself.
5. Without a mail server configured, emails wait as PENDING and go out once one is configured.
6. Sent emails are deleted after 30 days, because they contain personal details.

Delivery is **at least once**. Each email is claimed before sending, so several app instances never
send it at the same moment. However, if an instance crashes after sending but before recording it,
the email can be sent again once its 5-minute claim expires.

## AI assistant

`POST /ai` runs a [LangGraph4j](https://github.com/langgraph4j/langgraph4j) state graph (see
`aiservice/agent/OrgAssistantAgent`):

```
START -> [agent] --tool calls--> [tools] --+
            ^                              |
            +------------------------------+
         [agent] --final answer or step limit--> END
```

- **agent** sends the conversation and the list of available tools to the model (Amazon Bedrock).
- **tools** runs the lookups the model asked for and adds the results to the conversation.
- The graph loops until the model gives a final answer. It stops after **5 tool rounds**, so a
  confused model can't run forever or run up costs.

The tools are in `aiservice/agent/OrgTools`. They are **read-only** and cover only what every
logged-in user can already read through the API:

- searching employees, including by office and free capacity
- employee details
- reporting chain, direct reports and whole team
- finding departments and their members
- finding offices
- finding projects, project members and an employee's projects

The assistant can't change data or read audit history, so it can't be used to get around
permissions. If a tool fails, for example with an unknown id, the error goes back to the model as a
message, so it can correct itself instead of failing the request. The system prompt tells the model
to treat tool results as data and to ignore any instructions inside them.

## Getting started

### Prerequisites

- **Java 21.** The Gradle wrapper is included, so you don't need to install Gradle.
- **Neo4j 5.23 or later.** A free [Neo4j Aura](https://neo4j.com/cloud/aura/) instance works.
- **AWS credentials with Amazon Bedrock access.** Only needed for the AI assistant endpoint.

### Configuration

All secrets come from environment variables. None are stored in the repository.

| Variable | Required | Description |
|---|---|---|
| `NEO4J_URI` | yes | e.g. `neo4j+s://xxxx.databases.neo4j.io` |
| `NEO4J_USERNAME` | yes | Database user |
| `NEO4J_PASSWORD` | yes | Database password |
| `NEO4J_DATABASE` | yes | Database name |
| `JWT_SECRET` | yes | Key used to sign login tokens. At least 32 characters. The app won't start with a shorter one. |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | first start | Creates the first ADMIN account when the database has no users yet. Can be removed afterwards. |
| `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` | for email | SMTP server for notifications. For STARTTLS also set `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true`. Without these, emails are only logged. |
| `NOTIFY_HR_EMAIL` | for email | Shared HR mailbox that receives new and withdrawn staffing requests. |
| `NOTIFY_FROM` | no | Sender address. Defaults to `no-reply@worksphere.local`. |
| `APP_BASE_URL` | no | Frontend address used for links in emails, e.g. `https://worksphere.example.com`. |
| `app.outbox.*` (properties) | no | `poll-interval` (PT30S), `batch-size` (50), `max-attempts` (8), `retention` (P30D). |
| AWS credentials | for `/ai` | Picked up in the standard AWS way, e.g. `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` or `~/.aws/credentials`. The region is `us-east-1`. |

Other settings are in `src/main/resources/application.properties`:

- server port: 8081
- token lifetime: `app.jwt.expiry-minutes`, 60 by default
- allowed frontend origins: `app.cors.allowed-origins`, `http://localhost:4200` by default

### Run

PowerShell:

```powershell
$env:NEO4J_URI="neo4j+s://xxxx.databases.neo4j.io"
$env:NEO4J_USERNAME="neo4j"
$env:NEO4J_PASSWORD="..."
$env:NEO4J_DATABASE="neo4j"
$env:JWT_SECRET="<random string, 32+ characters>"
$env:ADMIN_USERNAME="admin"
$env:ADMIN_PASSWORD="<strong password>"
./gradlew bootRun
```

On startup the app applies any pending database migrations, then creates the admin account if the
database has no users yet.

The interactive API docs are at **http://localhost:8081/swagger-ui.html**. Log in, then paste the
token into the **Authorize** button.

### First steps

1. Log in: `POST /auth/login` with `{"username":"admin","password":"..."}`. The response contains an
   `accessToken`.
2. Send `Authorization: Bearer <accessToken>` with every other request.
3. Create departments and employees.
4. Create logins for your staff with `POST /users`. Pass an `employeeId` to link a login to an
   employee.

## Roles and permissions

| Who | Can do |
|---|---|
| **ADMIN** | Everything, including managing logins, reading the full audit log and handling failed emails. |
| **HR** | Hire, update, terminate and move employees. Manage departments, offices and projects, including who works on them. Read the change history of any employee, department, office or project. |
| **Manager** (any login linked to an employee who has reports) | For people below them in the org chart: change leave status, edit skills, and move a report to another manager inside their own team. |
| **Project lead** (a login linked to a project's lead) | Request staffing changes on their own open projects, and view or cancel those requests. HR must approve. |
| **Everyone logged in** | Read the directory, org chart, departments, offices and projects. Use the AI assistant. |

Managers can't edit their own record, and they can't reach anyone outside their reporting line.

## API overview

All list endpoints accept `?page=0&size=20` (the maximum size is 100) and return
`{content, page, size, totalElements, totalPages, first, last}`.

### Auth and users

| Method | Endpoint | Description |
|---|---|---|
| POST | `/auth/login` | Log in and get a token. |
| GET | `/auth/me` | The current user: username, roles and linked `employeeId`. |
| POST / GET | `/users` | Create a login, or list logins. ADMIN only. |
| PUT / DELETE | `/users/{id}/employee/{employeeId}` | Link or unlink a login and an employee. ADMIN only. |

### Employees

| Method | Endpoint | Description |
|---|---|---|
| GET | `/employees?name=&status=&departmentId=&skill=&company=&officeId=&maxAllocation=` | Directory search. `maxAllocation` (0-100) finds people with free capacity. |
| GET | `/employees/me` | Your own employee profile. |
| POST | `/employees` | Hire. `departmentId` and `managerId` are optional. |
| GET / PUT | `/employees/{id}` | Read or update a profile. |
| DELETE | `/employees/{id}` | Terminate. The record is kept. |
| PUT | `/employees/{id}/status` | `ACTIVE` or `ON_LEAVE`. |
| PUT / DELETE | `/employees/{id}/manager/{managerId}` | Set or remove a manager. |
| GET | `/employees/{id}/direct-reports` | People who report directly to this employee. |
| GET | `/employees/{id}/reports` | Everyone below this employee, at any depth. |
| GET | `/employees/{id}/reporting-chain` | Managers from this employee up to the top. |
| PUT / DELETE | `/employees/{id}/department/{departmentId}` | Set or remove department membership. |
| PUT | `/employees/{id}/company/{companyId}` | Set the employee's company. |
| PUT / DELETE | `/employees/{id}/office/{officeId}` | Set or remove the employee's office. |
| GET | `/employees/{id}/projects` | The employee's projects and total allocation on open ones. |
| POST / PUT | `/employees/{id}/skills` | Add one skill, or replace all skills. |
| DELETE | `/employees/{id}/skills/{skillId}` | Remove a skill. |
| GET | `/employees/{id}/history` | Change history for this employee. HR or ADMIN. |

### Departments

| Method | Endpoint | Description |
|---|---|---|
| GET / POST | `/departments?topLevelOnly=` | List or create departments. |
| GET / PUT / DELETE | `/departments/{id}` | Read, update or delete. Only empty departments can be deleted. |
| GET | `/departments/{id}/members?includeSubDepartments=` | Employees in this department, optionally including its sub-departments. |
| GET | `/departments/{id}/sub-departments` | Departments directly below this one. |
| PUT / DELETE | `/departments/{id}/parent/{parentId}` | Set or remove the parent department. |
| PUT / DELETE | `/departments/{id}/head/{employeeId}` | Set or remove the department head. |
| GET | `/departments/{id}/history` | Change history for this department. HR or ADMIN. |

### Offices

| Method | Endpoint | Description |
|---|---|---|
| GET / POST | `/offices?q=` | List or create offices. `q` matches name or city. |
| GET / PUT / DELETE | `/offices/{id}` | Read, update or delete. Only empty offices can be deleted. |
| GET | `/offices/{id}/employees` | People in this office. |
| GET | `/offices/{id}/history` | Change history. HR or ADMIN. |

### Projects

| Method | Endpoint | Description |
|---|---|---|
| GET / POST | `/projects?name=&status=&departmentId=` | List or create projects. |
| GET / PUT / DELETE | `/projects/{id}` | Read, update or delete. Only projects that never had members can be deleted. |
| PUT | `/projects/{id}/status` | Change status. Reopening is refused if it would put anyone over 100%. |
| PUT / DELETE | `/projects/{id}/lead/{employeeId}` | Set or remove the project lead. |
| PUT / DELETE | `/projects/{id}/department/{departmentId}` | Set or remove the owning department. |
| GET | `/projects/{id}/members` | Who is on the project, with role and allocation. |
| PUT | `/projects/{id}/members/{employeeId}` | Add someone, or change their role or allocation: `{"role":"...","allocationPercent":50}`. |
| DELETE | `/projects/{id}/members/{employeeId}` | Remove someone from the project. |
| GET | `/projects/{id}/history` | Change history, including membership changes and staffing requests. HR or ADMIN. |

### Staffing requests

| Method | Endpoint | Description |
|---|---|---|
| POST | `/projects/{id}/staffing-requests` | Project lead: request a change, e.g. `{"employeeId":"...","action":"ASSIGN","role":"...","allocationPercent":50}` or `{"employeeId":"...","action":"REMOVE"}`. |
| GET | `/projects/{id}/staffing-requests?status=` | The project's requests. Project lead, HR or ADMIN. |
| POST | `/staffing-requests/{id}/cancel` | Withdraw a pending request. The requester only. |
| GET | `/staffing-requests?status=PENDING&projectId=` | HR's approval queue, oldest first. HR or ADMIN. |
| GET | `/staffing-requests/{id}` | One request. HR or ADMIN. |
| POST | `/staffing-requests/{id}/approve` | Apply the change. HR or ADMIN, but not the requester. |
| POST | `/staffing-requests/{id}/reject` | `{"reason":"..."}`. HR or ADMIN. |

### Audit and AI

| Method | Endpoint | Description |
|---|---|---|
| GET | `/outbox?status=` | Email outbox, newest first, e.g. `status=FAILED`. The email body isn't shown. ADMIN only. |
| POST | `/outbox/{id}/retry` | Put a FAILED email back in the queue. ADMIN only. |
| GET | `/audit?actor=&action=&targetType=&targetId=&from=&to=` | Full audit log, newest first. ADMIN only. `from` and `to` are ISO-8601, e.g. `2026-01-01T00:00:00Z`. |
| POST | `/ai` | `{"question":"..."}`, with an optional `"employeeName"` hint. The assistant looks the answer up with read-only tools. |

### Error responses

| Status | Meaning |
|---|---|
| 400 | Invalid input. Validation errors are listed per field. |
| 401 | Not logged in, or the token is invalid or expired. |
| 403 | Not allowed for your role or team. |
| 404 | Not found. |
| 409 | Conflict: a duplicate code or email, a reporting or department loop, an allocation over 100%, approving your own staffing request, a record changed by someone else at the same moment, or an action blocked by the current state, e.g. terminating someone who still has direct reports. |

## Database migrations

Scripts in `src/main/resources/neo4j/migrations` run in order at startup. Each one runs only once.

| Version | What it does |
|---|---|
| V0001 | Converts legacy `Person` nodes to `Employee` and merges duplicate skills. Nothing is deleted irreversibly. |
| V0002 | Unique constraints and indexes for employees, departments, skills, companies and logins. |
| V0003 | Makes sure each employee is linked to at most one login. |
| V0004 | Indexes for the audit trail. |
| V0005 | Constraints and indexes for offices and projects. |
| V0006 | Constraint and indexes for staffing requests. |
| V0007 | Constraint and indexes for the email outbox. |

To change the schema, add a new `V0005__description.cypher` file. Never edit a migration that has
already been applied.

## Tests

```bash
./gradlew test
```

The tests run against an **embedded Neo4j** started inside the test process. You don't need Docker or
access to Aura to run them. They cover:

- **Web layer:** authentication, role and team permissions, validation and paging.
- **Services:** reporting and department cycles, termination rules, directory filters, uniqueness.
- **Projects and offices:** the 100% allocation limit, reopening checks, availability search, and
  how termination handles projects and offices.
- **Staffing approvals:** request and approve, rules re-checked at approval, requests refused up
  front, no self-approval, requester-only cancel, and a stale second decision being refused.
- **Email notifications and outbox:** recipients and content for each step, and missing addresses.
  Also, against a real database: emails saved with the change and none after a rollback; retries with
  back-off until delivery; giving up and admin retry; claiming; waiting without a mail server; and
  cleanup of old sent emails.
- **Manager access:** the team check against a real org chart.
- **Audit trail:** only changed fields are recorded, and a failed change leaves no audit event.
- **Migrations:** run against data in the old `Person` format.
- **AI assistant:** the LangGraph4j loop driven by a scripted model. Covers tool results reaching
  the model, errors handed back as messages, and the step limit.
- **End to end:** the whole application started and driven over HTTP, including an `/ai` question
  answered from real Neo4j data. The model is replaced with a stand-in, so Bedrock isn't needed.

## Project structure

```
src/main/java/com/example/neo4j/
├── controller/     REST endpoints for employees, departments, auth and users
├── service/        Business rules. Every write is transactional and audited.
├── repository/     Spring Data repositories, plus hand-written Cypher (EmployeeQueries, DepartmentQueries)
├── entity/         Graph entities: Employee, Department, Skill, Company, AppUser
├── dto/            Request and response types
├── security/       JWT, role rules and the manager team check (SecurityConfig, TeamAuthorization)
├── audit/          Audit trail: AuditLog and the /audit endpoints
├── aiservice/      AI assistant: agent/ holds the LangGraph4j graph and its read-only tools
├── route/          Camel route for the AI request
└── exception/      Maps errors to HTTP status codes
```

## Known limitations

- **Revoking access.** Tokens can't be cancelled early. A terminated employee's existing token keeps
  working for read-only endpoints until it expires (60 minutes by default). Their manager rights stop
  immediately.
- **Companies.** There's no API to create companies yet. Assigning an existing company works.
- **Emails can occasionally be sent twice.** Delivery is at least once (see
  [Email outbox and retries](#email-outbox-and-retries)).
- **Audit tamper protection.** The application can't change audit events, but anyone with direct
  database access could. For strict compliance, also stream audit events to a write-once store.
- **Checks under concurrency.** The reporting-loop check and the 100% allocation check aren't
  protected by a database lock. Two changes made at exactly the same moment could, rarely, get past
  them.
