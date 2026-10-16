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
  disables their login. Finished project assignments are kept as history. Pending leave, and approved
  leave that hasn't started, is cancelled.
- **Leave management.** Employees request annual, sick, unpaid or other leave. A manager above them in
  the org chart, or HR, approves or rejects it. Annual leave is checked against a yearly allowance,
  overlapping requests are refused, and managers can see who in their team is away. See
  [Leave](#leave).
- **Directory search.** Filter by name prefix, status, department, skill, company, office and free
  capacity. All list endpoints are paginated.
- **Offices.** Each employee can be placed in one office. Offices can be searched by name or city.
  Each office keeps its own public holidays, which leave and availability skip.
- **Projects and staffing.** Projects have a status, dates, an owning department and a lead. People
  join with a role, an allocation percentage and dates. **Nobody can be allocated more than 100% on
  any day**, so someone fully booked until March can be booked from April. Search can find people
  with free capacity today, e.g. `/employees?skill=java&maxAllocation=50`.
- **Skill levels and matching.** Every skill has a level from 1 (beginner) to 5 (expert). Projects
  list the skills they need and the minimum level for each, and `/projects/{id}/candidates` ranks
  who could join over the project's dates: best skill match first, then people in the project's
  department, then those least on leave, then most free capacity. See
  [Skill levels and matching](#skill-levels-and-matching).
- **Staffing approvals.** A project lead can request to add, change or remove people on their own
  open project. HR approves or rejects each request, and the change is only applied on approval.
  HR, the lead and the affected employee are notified by email, with automatic retries. See
  [Staffing approvals](#staffing-approvals).
- **Roles and manager self-service.** Roles are ADMIN, HR and EMPLOYEE. A login linked to an employee
  can manage that employee's team. The org chart decides who is in the team, so there is no separate
  manager role to keep in sync.
- **Sessions and login protection.** Short-lived access tokens with refresh tokens that work once.
  Logging out, revoking a login's sessions, or disabling a login (e.g. terminating the employee)
  takes effect at once. Repeated failed logins lock the username or address out for a while. See
  [Sessions and login protection](#sessions-and-login-protection).
- **Audit trail.** Every change and every login attempt is recorded. Updates store only the fields
  that changed, with their before and after values. Each event is written in the same transaction as
  its change, and events can't be edited or deleted.
- **AI assistant (LangGraph4j agent).** Ask questions about the organisation in plain English, for
  example "Who in Engineering knows Kafka?" or "Who does Priya's manager report to?". The assistant
  looks the answer up in the org chart before replying, and never changes data. See
  [AI assistant](#ai-assistant).
- **Org analytics.** For HR: headcount, span of control (managers with too many or too few reports),
  org depth, skills on a project that only one person has (the "bus factor"), and skill gaps per
  department. See [Org analytics](#org-analytics).
- **Org chart history.** Who someone reported to, and which department they were in, over time.
  Also who reported to a manager on a past date. It is rebuilt from the audit trail. See
  [Org chart history](#org-chart-history).
- **Web app.** A browser front end served by the application at **http://localhost:8081/**. It has
  the org chart, your leave, approval inboxes, a team calendar, analytics and the assistant. See
  [Web app](#web-app).
- **Versioned database migrations.** Constraints, indexes and data conversions run automatically at
  startup.

## Data model

```
(:Employee)-[:REPORTS_TO]->(:Employee)        manager
(:Employee)-[:MEMBER_OF]->(:Department)       department membership
(:Department)-[:PART_OF]->(:Department)       sub-departments, any depth
(:Department)-[:HEADED_BY]->(:Employee)       department head
(:Employee)-[:HAS_SKILL {level}]->(:Skill)    level 1-5
(:Project)-[:REQUIRES {level}]->(:Skill)      skills a project needs, minimum level 1-5
(:Employee)-[:WORKS_FOR]->(:Company)
(:Employee)-[:LOCATED_AT]->(:Office)
(:Employee)-[:WORKS_ON {role, allocationPercent, since, startDate, endDate}]->(:Project)
(:Project)-[:LED_BY]->(:Employee)             project lead
(:Project)-[:OWNED_BY]->(:Department)         owning department
(:AppUser {employeeId})                        login, optionally linked to an employee
(:AuditEvent)                                  append-only audit trail
```

The `Employee` entity maps only its link to a company. All other links (manager, membership,
parent, head, office, skills, projects) are read and written with targeted Cypher
in the `*Queries` classes. That way, loading one employee never loads the rest of the organisation.

### Projects and allocation

- Project status is `PLANNED`, `ACTIVE`, `ON_HOLD`, `COMPLETED` or `CANCELLED`. The first three
  count as **open**.
- Each assignment runs from `startDate` to `endDate`, both inclusive. With no `endDate` it is
  open-ended. When someone joins, the dates default to the project's dates, starting no earlier than
  today. When an assignment is changed, a date that is left out keeps its current value.
  `"openEnded": true` removes the end date, even if the project has one. `since` is
  the day they were added. Assignments made before dates existed start on that day (migration V0010).
- **On any day**, the sum of `allocationPercent` over the open-project assignments running that day
  can't exceed 100%. This is checked when someone joins a project, when their allocation or dates
  change, when a staffing request is made and approved, and when a finished project is reopened. The
  error names the busiest day, e.g. "already 100% allocated on 2030-03-15".
- An employee's **current allocation**, shown on their profile and used by `maxAllocation`, counts
  only the assignments running today.
- Assignments on completed and cancelled projects are kept as history but don't count toward
  allocation. People can only join open projects.
- A project with any members, including past ones, can't be deleted. Set it to `CANCELLED` instead.

### Skill levels and matching

Levels are 1 beginner, 2 basic, 3 intermediate, 4 advanced, 5 expert.

- Skills are added with `{"name":"Java","level":4}`. The level can be left out: a skill the person
  already has keeps its level, and a new one gets 3. Adding a skill someone already has changes its
  level. Skills recorded before levels existed were set to 3 by migration V0008.
- The directory can filter by level: `/employees?skill=java&minSkillLevel=4`.
- A project's required skills use the same format, where the level is the minimum the project needs.
  HR or the project's lead can change them.
- `GET /projects/{id}/candidates?allocationPercent=50&from=&to=` suggests people for an open project
  over a period:
  - **Period:** `from` to `to`. It defaults to the project's dates, starting no earlier than today.
    If the project has no end date, the period is 90 days.
  - **Who:** active employees who aren't on the project yet and have at least one required skill.
    They need at least `allocationPercent` free on their busiest day in the period (default 1, so
    anyone not fully booked), and can't be on approved leave for the whole period.
  - **Match %:** the average over the required skills. Meeting the level counts fully, being below
    it counts as your level divided by the required level, and not having the skill counts as 0.
    For example, Java 2 of a required 4, and no Kafka, gives (0.5 + 0) / 2 = 25%.
  - **Leave:** each candidate shows their working days of approved and pending leave in the period,
    skipping their own office's holidays.
    They also get an availability percentage: the share of the period's working days they are not on
    approved leave.
  - **Order:** highest match first, then people in the owning department or one of its
    sub-departments, then highest availability, then most free capacity.
  - Each candidate lists their level in every required skill, so the gaps are visible.
- Candidates are suggestions only. Adding someone still goes through a staffing request or HR.
- Changes to skills and required skills are recorded in the history with what was added, removed and
  re-levelled. Employees changed by their manager are emailed as before, now including level changes.

### Leave

Employees request time off for themselves, and someone above them decides it:

```
employee: POST /leave-requests  ->  PENDING
                                     |-- manager or HR approves -> APPROVED
                                     |-- manager or HR rejects  -> REJECTED (reason recorded)
                                     |-- employee cancels       -> CANCELLED
```

- **Types:** `ANNUAL`, `SICK`, `UNPAID` and `OTHER`. Leave is counted in **working days**: Monday to
  Friday, minus the public holidays of the employee's office. A request's `workingDays` is counted
  when it is made. Balances are always counted against today's holidays, so a holiday added later
  frees up the day.
- **Who requests:** an employee for themselves. Their login must be linked to their employee record.
  Past dates are allowed, so sick leave can be recorded afterwards. One request can't span more than
  a year.
- **Who decides:** any manager above the employee in the org chart, or HR. **Nobody can decide their
  own leave**, HR included. If two people decide the same request at once, the second gets a 409.
- **No overlaps:** a person can't have two pending or approved requests on the same day.
- **Annual allowance:** `ANNUAL` leave must fit `app.leave.annual-allowance-days` (20 by default) per
  calendar year. Pending days count too when asking, so nobody can queue more than they have.
  Approval checks again. Leave over New Year counts against each year.
  `GET /employees/{id}/leave-balance?year=2026` shows the allowance and the approved, pending and
  remaining days.
- **Cancelling:** the requester can cancel a pending request, or approved leave that hasn't started.
  Terminating an employee cancels their pending leave and approved leave that hasn't started. Leave
  already taken is kept.
- **Seeing leave:** everyone sees their own (`/leave-requests/mine`). Managers see everyone below
  them (`/leave-requests/team`), which is both their approval queue (`?status=PENDING`) and a team
  calendar (`?status=APPROVED&from=2026-12-01&to=2026-12-31` lists who is away then). HR sees everyone's
  (`/leave-requests`).
- **History:** every step is recorded in the employee's history.
- **Status:** the `ON_LEAVE` employment status is separate and still set by hand, e.g. for long-term
  leave. Approved leave doesn't change it.

| When | Who is emailed |
|---|---|
| Leave is requested | The employee's direct manager, or the HR mailbox if they have none |
| Leave is approved or rejected | The employee, with the reason when rejected |
| Leave is cancelled | The employee's direct manager, or the HR mailbox |

These emails go through the same outbox as the others.

### Org analytics

HR and ADMIN only. Everything counts current employees; terminated people have no links left, so
they drop out.

- `GET /analytics/overview`: headcount, how many are on leave, how many managers there are, the
  average and widest span of control (direct reports), the number of levels in the longest reporting
  line, and how many people have no manager or no department.
- `GET /analytics/managers`: managers with the most direct reports first. For each, their total
  reports and how many levels sit below them. Managers above `app.analytics.max-span` (10 by
  default) are flagged `WIDE`, and those with a single report `NARROW`.
- `GET /analytics/project-skill-risks`: for each open project's required skills, the current
  members who have the skill at the required level. A skill is **at risk** when one person or nobody
  covers it. By default only projects with a risk are listed, most risks first; `?atRiskOnly=false`
  lists all of them. Members whose assignment has ended don't count.
- `GET /analytics/skill-gaps?departmentId=`: for each department that owns open projects, the
  skills those projects need. The level is the highest any of them needs. Each skill shows how many
  active people in the department or its sub-departments have it at that level. A skill is
  **short** when fewer people qualify than projects need it.

### Org chart history

The history is rebuilt from the audit trail, which records every change of manager or department
with its before and after value. Termination records the links it removed.

- `GET /employees/{id}/reporting-history`: who the person reported to, and which department they
  were in, oldest first. Each period has a `from` and `to`; `to` is null while it still applies.
- `GET /employees/{id}/reports-on?date=2026-03-15`: who reported directly to this person at the end
  of that day.
- Who can see it: the employee, a manager above them now, or HR.
- Links from before the audit trail existed show as starting at an unknown time (`from` is null).
  Changes made directly in the database, bypassing the API, aren't seen. Names are today's names.

### Web app

The application serves a small browser front end at its root URL, e.g. **http://localhost:8081/**.
It needs no build step: plain HTML, CSS and JavaScript in `src/main/resources/static`. It runs on
the same address as the API, so no CORS setup is needed.

- **Login:** use the same username and password as the API. The token is kept in the browser tab's
  session storage and is gone when the tab is closed.
- **Pages:**
  - Org chart: a person, their reporting line, direct reports, projects and reporting history.
  - My leave: balance, request, cancel.
  - Approvals: your team's pending leave, plus staffing requests for HR.
  - Team calendar: who is away in a month.
  - Analytics: HR only.
  - Assistant.
- The pages only call the API, so every permission rule still applies. Pages you can't use are
  hidden, and anything else you can't see shows "You don't have access to this".
- Everything shown is rendered as text, never as HTML, so names and notes can't inject markup.

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
- skills with levels, and suggesting candidates for a project
- leave: who is away and when, and leave balances. Callers see their own leave and their team's;
  HR sees everyone's, the same as the API. The model is told today's date, so it can work out
  "next week".

The assistant can't change data or read audit history, so it can't be used to get around
permissions. Staffing requests and leave are checked against who is asking. If a tool fails, for example with an unknown id, the error goes back to the model as a
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
| `NOTIFY_HR_EMAIL` | for email | Shared HR mailbox. It receives new and withdrawn staffing requests, leave requests from people without a manager, and leave and reporting-line changes made by managers. |
| `NOTIFY_FROM` | no | Sender address. Defaults to `no-reply@worksphere.local`. |
| `APP_BASE_URL` | no | Frontend address used for links in emails, e.g. `https://worksphere.example.com`. |
| `app.analytics.max-span` (property) | no | Managers with more direct reports than this are flagged `WIDE`. Defaults to 10. |
| `app.leave.annual-allowance-days` (property) | no | Working days of annual leave per person per calendar year. Defaults to 20. |
| `app.outbox.*` (properties) | no | `poll-interval` (PT30S), `batch-size` (50), `max-attempts` (8), `retention` (P30D). |
| AWS credentials | for `/ai` | Picked up in the standard AWS way, e.g. `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` or `~/.aws/credentials`. The region is `us-east-1`. |

Other settings are in `src/main/resources/application.properties`:

- server port: 8081
- token lifetimes: `app.jwt.expiry-minutes` (15) and `app.jwt.refresh-days` (7)
- failed-login limits: `app.auth.*`; see [Sessions and login protection](#sessions-and-login-protection)
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

The web app is at **http://localhost:8081/**. The interactive API docs are at
**http://localhost:8081/swagger-ui.html**: log in, then paste the token into the **Authorize** button.

### First steps

1. Log in: `POST /auth/login` with `{"username":"admin","password":"..."}`. The response contains an
   `accessToken` and a `refreshToken`.
2. Send `Authorization: Bearer <accessToken>` with every other request. When it expires, get a new one
   with `POST /auth/refresh`.
3. Create departments and employees.
4. Create logins for your staff with `POST /users`. Pass an `employeeId` to link a login to an
   employee.

## Roles and permissions

| Who | Can do |
|---|---|
| **ADMIN** | Everything, including managing logins, reading the full audit log and handling failed emails. |
| **HR** | Hire, update, terminate and move employees. Manage departments, offices and projects, including who works on them. Read the change history of any employee, department, office or project. See org analytics and anyone's reporting history. |
| **Manager** (any login linked to an employee who has reports) | For people below them in the org chart: approve or reject leave, see their leave, balance and reporting history, change leave status, edit skills, and move a report to another manager inside their own team. |
| **Project lead** (a login linked to a project's lead) | Request staffing changes on their own open projects, and view or cancel those requests. HR must approve. Set which skills their project needs. |
| **Everyone logged in** | Read the directory, org chart, departments, offices and projects. Request and cancel their own leave. Use the AI assistant. |

Managers can't edit their own record, and they can't reach anyone outside their reporting line.

### Sessions and login protection

- **Tokens.** `POST /auth/login` returns an `accessToken` (valid for `app.jwt.expiry-minutes`, 15 by
  default) and a `refreshToken` (valid for `app.jwt.refresh-days`, 7 by default).
- **Refreshing.** Before the access token expires, `POST /auth/refresh` with `{"refreshToken":"..."}`
  returns a new pair. Each refresh token works **once**. Only a hash of it is stored, so the database
  never holds a usable token. The web app does this for you.
- **Reuse means theft.** If a refresh token that was already used comes back, two parties have it.
  Every session of that login is revoked, so both have to log in again. This is recorded in the
  audit trail as `REFRESH_TOKEN_REUSED`.
- **Revoking access at once.** Every request's token is checked against its login. It's refused if:
  - the login was disabled (terminating an employee disables theirs), or
  - the token was issued before the login's last logout or session revocation.

  This costs one lookup per request.
  - `POST /auth/logout` logs you out everywhere.
  - `DELETE /users/{id}/sessions` (ADMIN) does the same for someone else, e.g. after a lost laptop.
- **Failed logins.** After `app.auth.max-failures-per-user` (5) failures for a username, or
  `app.auth.max-failures-per-address` (20) from one address, within `app.auth.failure-window` (15
  minutes), further attempts are refused with **429 Too Many Requests** and a `Retry-After` header.
  This lasts for `app.auth.lockout` (15 minutes), even with the right password. Lockouts are recorded
  as failed logins with reason `LOCKED_OUT`.
  - The counts are kept in memory, so each instance counts on its own and a restart clears them.
  - Behind a proxy, set `server.forward-headers-strategy` so the real client address is used.
- Expired refresh tokens are deleted every night.

### Manager self-service notifications

Changes a manager makes don't need approval, so the people affected are told by email:

| A manager... | Who is emailed |
|---|---|
| changes someone's status (`ACTIVE` / `ON_LEAVE`) | the employee, and the HR mailbox |
| moves a report to another manager in their team | the employee, the new manager, and the HR mailbox |
| changes someone's skills | the employee |

- The same changes made by HR or ADMIN are recorded in the audit trail but not emailed.
- Nobody is emailed about their own action, e.g. a manager who moves someone under themselves.
- Requests that change nothing (a status already set, a skill already held) send nothing.
- These emails go through the same outbox as staffing emails (see
  [Email outbox and retries](#email-outbox-and-retries)).

## API overview

All list endpoints accept `?page=0&size=20` (the maximum size is 100) and return
`{content, page, size, totalElements, totalPages, first, last}`.

### Auth and users

| Method | Endpoint | Description |
|---|---|---|
| POST | `/auth/login` | Log in. Returns an access token and a refresh token. 429 after too many failures. |
| POST | `/auth/refresh` | `{"refreshToken":"..."}`: a new access token and refresh token. Each refresh token works once. |
| POST | `/auth/logout` | Log out everywhere: every token of this login stops working. |
| GET | `/auth/me` | The current user: username, roles and linked `employeeId`. |
| POST / GET | `/users` | Create a login, or list logins. ADMIN only. |
| PUT / DELETE | `/users/{id}/employee/{employeeId}` | Link or unlink a login and an employee. ADMIN only. |
| DELETE | `/users/{id}/sessions` | End every session of a login at once. ADMIN only. |

### Employees

| Method | Endpoint | Description |
|---|---|---|
| GET | `/employees?name=&status=&departmentId=&skill=&minSkillLevel=&company=&officeId=&maxAllocation=` | Directory search. `minSkillLevel` (1-5) needs `skill`. `maxAllocation` (0-100) finds people with free capacity. |
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
| POST / PUT | `/employees/{id}/skills` | Add one skill or change its level (`{"name":"Java","level":4}`), or replace all skills (a list). |
| DELETE | `/employees/{id}/skills/{skillId}` | Remove a skill. |
| GET | `/employees/{id}/history` | Change history for this employee. HR or ADMIN. |
| GET | `/employees/{id}/reporting-history` | Their managers and departments over time. The employee, a manager above them, or HR. |
| GET | `/employees/{id}/reports-on?date=` | Who reported directly to them at the end of that day. The employee, a manager above them, or HR. |

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
| GET | `/offices/{id}/holidays?year=` | The office's public holidays in a year (this year by default). |
| POST | `/offices/{id}/holidays` | Add a holiday: `{"date":"2026-12-25","name":"Christmas Day"}`. One per day. HR or ADMIN. |
| DELETE | `/offices/{id}/holidays/{holidayId}` | Remove a holiday. HR or ADMIN. |

### Projects

| Method | Endpoint | Description |
|---|---|---|
| GET / POST | `/projects?name=&status=&departmentId=` | List or create projects. |
| GET / PUT / DELETE | `/projects/{id}` | Read, update or delete. Only projects that never had members can be deleted. |
| PUT | `/projects/{id}/status` | Change status. Reopening is refused if it would put anyone over 100%. |
| PUT / DELETE | `/projects/{id}/lead/{employeeId}` | Set or remove the project lead. |
| PUT / DELETE | `/projects/{id}/department/{departmentId}` | Set or remove the owning department. |
| GET | `/projects/{id}/members` | Who is on the project, with role and allocation. |
| PUT | `/projects/{id}/members/{employeeId}` | Add someone, or change their role, allocation or dates: `{"role":"...","allocationPercent":50,"startDate":"2026-11-01","endDate":"2027-03-31"}`. The dates are optional; `"openEnded":true` removes the end date. |
| DELETE | `/projects/{id}/members/{employeeId}` | Remove someone from the project. |
| POST / PUT | `/projects/{id}/skills` | Add one required skill or change its minimum level, or replace them all. HR or the project lead. |
| DELETE | `/projects/{id}/skills/{skillId}` | Remove a required skill. HR or the project lead. |
| GET | `/projects/{id}/candidates?allocationPercent=&from=&to=` | People who could join over a period, best skill match first, with their leave in that period. See [Skill levels and matching](#skill-levels-and-matching). |
| GET | `/projects/{id}/history` | Change history, including membership changes and staffing requests. HR or ADMIN. |

### Staffing requests

| Method | Endpoint | Description |
|---|---|---|
| POST | `/projects/{id}/staffing-requests` | Project lead: request a change, e.g. `{"employeeId":"...","action":"ASSIGN","role":"...","allocationPercent":50,"startDate":"2026-11-01"}` (dates optional) or `{"employeeId":"...","action":"REMOVE"}`. |
| GET | `/projects/{id}/staffing-requests?status=` | The project's requests. Project lead, HR or ADMIN. |
| POST | `/staffing-requests/{id}/cancel` | Withdraw a pending request. The requester only. |
| GET | `/staffing-requests?status=PENDING&projectId=` | HR's approval queue, oldest first. HR or ADMIN. |
| GET | `/staffing-requests/{id}` | One request. HR or ADMIN. |
| POST | `/staffing-requests/{id}/approve` | Apply the change. HR or ADMIN, but not the requester. |
| POST | `/staffing-requests/{id}/reject` | `{"reason":"..."}`. HR or ADMIN. |

### Leave

All lists accept `?status=&from=&to=`. `from` and `to` (ISO dates) find leave that overlaps those days.

| Method | Endpoint | Description |
|---|---|---|
| POST | `/leave-requests` | Request your own leave: `{"type":"ANNUAL","startDate":"2026-12-21","endDate":"2026-12-24","note":"..."}`. |
| GET | `/leave-requests/mine` | Your leave. |
| GET | `/leave-requests/team` | Leave of everyone below you in the org chart. |
| GET | `/leave-requests` | Everyone's leave, with an optional `employeeId`. HR or ADMIN. |
| GET | `/leave-requests/{id}` | One request. The employee, a manager above them, or HR. |
| POST | `/leave-requests/{id}/approve` | A manager above the employee, or HR. Not your own. |
| POST | `/leave-requests/{id}/reject` | `{"reason":"..."}`. A manager above the employee, or HR. Not your own. |
| POST | `/leave-requests/{id}/cancel` | Withdraw pending leave, or approved leave that hasn't started. The requester only. |
| GET | `/employees/{id}/leave-balance?year=` | Annual leave allowance and days used. The employee, a manager above them, or HR. |

### Analytics

All HR or ADMIN. See [Org analytics](#org-analytics).

| Method | Endpoint | Description |
|---|---|---|
| GET | `/analytics/overview` | Headcount, managers, span of control, org depth, people without a manager or department. |
| GET | `/analytics/managers` | Managers by number of direct reports, flagged `WIDE` or `NARROW`. |
| GET | `/analytics/project-skill-risks?atRiskOnly=` | Required skills on open projects covered by one person or nobody. |
| GET | `/analytics/skill-gaps?departmentId=` | Skills departments' open projects need against qualified people. |

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
| V0008 | Gives existing skills a level of 3 (intermediate). |
| V0009 | Constraint and indexes for leave requests. |
| V0010 | Gives existing project assignments a start date: the day the person joined. |
| V0011 | Constraints and index for refresh tokens. |
| V0012 | Constraint and index for office holidays. |

To change the schema, add a new `V0005__description.cypher` file. Never edit a migration that has
already been applied.

## Tests

```bash
./gradlew test
```

The tests run against an **embedded Neo4j** started inside the test process. You don't need Docker or
access to Aura to run them. Service tests marked `@ServiceTest` (and extending `SharedNeo4jTest`) share one
database and one Spring context, so the database starts and the migrations run once. Each test rolls
back its own changes. Tests that need committed data or old-format data before migrations keep a
database of their own. They cover:

- **Web layer:** authentication, role and team permissions, validation and paging. Lockout after
  failed logins with `Retry-After`, refresh without an access token, logout, and revoked or disabled
  logins losing access at once.
- **Sessions:** refresh tokens working once and stored only as hashes, reuse revoking every session,
  expired, unknown and disabled cases, logout and admin revocation, and cleanup. The lockout rules
  are tested on their own with a controllable clock.
- **Services:** reporting and department cycles, termination rules, directory filters, uniqueness.
- **Projects and offices:** the 100% allocation limit, reopening checks, availability search, and
  how termination handles projects and offices.
- **Allocation over time:** booking people one after another, the busiest-day check and its message,
  default and kept dates, current allocation counting only today, reopening against each member's
  own dates, and staffing requests for later dates. Candidates over a period: bookings before,
  during and after it, and approved, pending and whole-period leave.
- **Leave:** requesting and deciding, who may decide (managers at any depth, HR, never yourself),
  overlaps, the annual allowance including pending days and leave over New Year, cancelling before
  and after leave starts, cancelling on termination, and who can see whose leave. Leave emails and
  working-day counting are tested on their own.
- **Staffing approvals:** request and approve, rules re-checked at approval, requests refused up
  front, no self-approval, requester-only cancel, and a stale second decision being refused.
- **Skill levels and matching:** default and kept levels, level changes in the history, filtering
  by minimum level, required skills, and candidate ranking: match %, department, free capacity,
  and who is left out.
- **Email notifications and outbox:** recipients and content for each step, and missing addresses.
  Manager self-service emails: sent only for manager changes, not for HR changes, no-op changes, or
  changes that fail.
  Also, against a real database: emails saved with the change and none after a rollback; retries with
  back-off until delivery; giving up and admin retry; claiming; waiting without a mail server; and
  cleanup of old sent emails.
- **Manager access:** the team check against a real org chart.
- **Audit trail:** only changed fields are recorded, and a failed change leaves no audit event.
- **Migrations:** run against data in the old `Person` format.
- **AI assistant:** the LangGraph4j loop driven by a scripted model. Covers tool results reaching
  the model, errors handed back as messages, and the step limit. The leave tools show only your own
  and your team's leave, HR sees everyone's, and the model is told today's date.
- **Org analytics:** overview figures, managers and their flags, skills at risk (including ended
  assignments not counting), and department skill gaps across sub-departments.
- **Org chart history:** periods built from real changes, termination ending them, who reported to
  whom on past dates, and links from before the audit trail.
- **End to end:** the whole application started and driven over HTTP, including an `/ai` question
  answered from real Neo4j data. The model is replaced with a stand-in, so Bedrock isn't needed.
  The web app's files load without logging in, while the API still needs a token.

## Project structure

```
src/main/java/com/example/neo4j/
├── controller/     REST endpoints
├── service/        Business rules. Every write is transactional and audited.
├── repository/     Spring Data repositories, plus hand-written Cypher (the *Queries classes)
├── entity/         Graph entities, e.g. Employee, Department, Project, LeaveRequest
├── dto/            Request and response types
├── security/       JWT, role rules and the manager team check (SecurityConfig, TeamAuthorization)
├── audit/          Audit trail: AuditLog and the /audit endpoints
├── aiservice/      AI assistant: agent/ holds the LangGraph4j graph and its read-only tools
├── route/          Camel route for the AI request
└── exception/      Maps errors to HTTP status codes

src/main/resources/static/   The web app (index.html, app.js, app.css)
```

## Known limitations

- **Failed-login counts are per instance.** With several instances, each one counts on its own, so
  an attacker spreading guesses across instances gets proportionally more tries.
- **Companies.** There's no API to create companies yet. Assigning an existing company works.
- **Emails can occasionally be sent twice.** Delivery is at least once (see
  [Email outbox and retries](#email-outbox-and-retries)).
- **Audit tamper protection.** The application can't change audit events, but anyone with direct
  database access could. For strict compliance, also stream audit events to a write-once store.
- **Allowances.** The annual allowance is the same for everyone. Someone without an office has no
  public holidays.
- **Checks under concurrency.** Leave requests lock the employee while they are checked, so overlaps
  and the allowance hold. The reporting-loop check and the 100% allocation check aren't
  protected by a database lock. Two changes made at exactly the same moment could, rarely, get past
  them.
