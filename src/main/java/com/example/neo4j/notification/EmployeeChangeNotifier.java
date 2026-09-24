package com.example.neo4j.notification;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.example.neo4j.entity.Employee;
import com.example.neo4j.outbox.OutboxService;
import com.example.neo4j.repository.AppUserRepository;
import com.example.neo4j.repository.EmployeeRepository;

/**
 * Emails people about changes a manager makes through self-service (changes by HR or ADMIN are
 * only audited, not emailed):
 *
 * <pre>
 *   STATUS_CHANGED  -> the employee, the HR mailbox  (leave matters for payroll and absence records)
 *   MANAGER_CHANGED -> the employee, the new manager, the HR mailbox
 *   SKILLS_CHANGED  -> the employee
 * </pre>
 *
 * Nobody is emailed about their own action. Emails go through the outbox inside the change's
 * transaction, like staffing emails; a problem building one is logged and never blocks the change.
 */
@Component
public class EmployeeChangeNotifier {

    private static final Logger log = LoggerFactory.getLogger(EmployeeChangeNotifier.class);

    record Email(String to, String subject, String body) {
    }

    private final OutboxService outbox;
    private final EmployeeRepository employeeRepository;
    private final AppUserRepository userRepository;
    private final String hrEmail;
    private final String baseUrl;

    public EmployeeChangeNotifier(OutboxService outbox, EmployeeRepository employeeRepository,
                                  AppUserRepository userRepository,
                                  @Value("${app.notifications.hr-email:}") String hrEmail,
                                  @Value("${app.notifications.base-url:}") String baseUrl) {
        this.outbox = outbox;
        this.employeeRepository = employeeRepository;
        this.userRepository = userRepository;
        this.hrEmail = hrEmail;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onEmployeeChange(EmployeeChangeEvent event) {

        if (!event.selfService()) {
            return;
        }

        List<Email> emails;
        try {
            emails = emailsFor(event);
        } catch (Exception e) {
            // A bug in building an email must never block the manager's change itself.
            log.error("Could not prepare notification for {} of employee {}", event.type(), event.employeeId(), e);
            return;
        }

        String reference = "employee-change:" + event.employeeId() + ":" + event.type();
        for (Email email : emails) {
            outbox.enqueue(email.to(), "[WorkSphere] " + StaffingNotifier.singleLine(email.subject()), email.body(),
                    reference);
        }
    }

    // ---- Building emails ----------------------------------------------------------

    List<Email> emailsFor(EmployeeChangeEvent event) {

        Optional<Employee> found = employeeRepository.findById(event.employeeId());
        if (found.isEmpty()) {
            return List.of();
        }
        Employee employee = found.get();

        Optional<Employee> actorEmployee = userRepository.findByUsername(event.actor())
                .map(user -> user.getEmployeeId())
                .flatMap(id -> id == null ? Optional.empty() : employeeRepository.findById(id));
        String actorId = actorEmployee.map(Employee::getId).orElse(null);
        String actorName = actorEmployee.map(Employee::getName).orElse(event.actor());

        List<Email> emails = new ArrayList<>();

        switch (event.type()) {
            case STATUS_CHANGED -> {
                String from = statusLabel(change(event, "status", "from"));
                String to = statusLabel(change(event, "status", "to"));

                emailTo(employee, actorId).ifPresent(address -> emails.add(new Email(address,
                        "Your status is now: " + to,
                        """
                        %s changed your employment status in WorkSphere.

                        Previous status: %s
                        New status:      %s

                        If this isn't right, please contact your manager or HR.
                        """.formatted(actorName, from, to))));

                hrMailbox().ifPresent(address -> emails.add(new Email(address,
                        "Status changed by a manager: " + employee.getName() + " is now " + to,
                        """
                        A manager changed an employee's status through self-service.

                        Employee:   %s (%s)
                        Changed:    %s -> %s
                        Changed by: %s

                        %s
                        """.formatted(employee.getName(), orDash(employee.getEmployeeCode()), from, to, actorName,
                                historyLink(employee)))));
            }

            case MANAGER_CHANGED -> {
                Optional<Employee> previous = employeeById(change(event, "managerId", "from"));
                Optional<Employee> next = employeeById(change(event, "managerId", "to"));
                String previousName = previous.map(Employee::getName).orElse("nobody");
                String nextName = next.map(Employee::getName).orElse("nobody");

                emailTo(employee, actorId).ifPresent(address -> emails.add(new Email(address,
                        "You now report to " + nextName,
                        """
                        %s changed your reporting line in WorkSphere.

                        Previous manager: %s
                        New manager:      %s

                        If this isn't right, please contact your manager or HR.
                        """.formatted(actorName, previousName, nextName))));

                next.flatMap(manager -> emailTo(manager, actorId)).ifPresent(address -> emails.add(new Email(address,
                        employee.getName() + " now reports to you",
                        """
                        %s moved %s to report to you in WorkSphere.

                        Previous manager: %s
                        """.formatted(actorName, employee.getName(), previousName))));

                hrMailbox().ifPresent(address -> emails.add(new Email(address,
                        "Reporting line changed by a manager: " + employee.getName(),
                        """
                        A manager moved an employee within their team through self-service.

                        Employee:         %s (%s)
                        Previous manager: %s
                        New manager:      %s
                        Changed by:       %s

                        %s
                        """.formatted(employee.getName(), orDash(employee.getEmployeeCode()), previousName, nextName,
                                actorName, historyLink(employee)))));
            }

            case SKILLS_CHANGED -> emailTo(employee, actorId).ifPresent(address -> emails.add(new Email(address,
                    "Your skills were updated",
                    """
                    %s updated your skills in WorkSphere.

                    Added:   %s
                    Removed: %s

                    If this isn't right, please contact your manager.
                    """.formatted(actorName, listOrDash(event.details().get("added")),
                            listOrDash(event.details().get("removed"))))));
        }

        return emails;
    }

    // ---- Helpers ------------------------------------------------------------------

    // The person's address, unless they are the one who made the change (nobody is emailed about
    // their own action) or they have no email on record.
    private static Optional<String> emailTo(Employee person, String actorEmployeeId) {
        if (Objects.equals(person.getId(), actorEmployeeId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(person.getEmail()).filter(address -> !address.isBlank());
    }

    private Optional<Employee> employeeById(Object id) {
        return id == null ? Optional.empty() : employeeRepository.findById(id.toString());
    }

    private Optional<String> hrMailbox() {
        if (hrEmail == null || hrEmail.isBlank()) {
            log.warn("app.notifications.hr-email is not set; HR is not notified of manager changes");
            return Optional.empty();
        }
        return Optional.of(hrEmail);
    }

    private String historyLink(Employee employee) {
        return baseUrl.isBlank()
                ? "Employee id: " + employee.getId()
                : "Change history: " + baseUrl + "/employees/" + employee.getId() + "/history";
    }

    // Reads {"field": {"from": ..., "to": ...}} as recorded by audit.Changes.
    private static Object change(EmployeeChangeEvent event, String field, String side) {
        return event.details().get(field) instanceof Map<?, ?> change ? change.get(side) : null;
    }

    private static String statusLabel(Object status) {
        if (status == null) {
            return "-";
        }
        String text = status.toString().replace('_', ' ').toLowerCase();
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String listOrDash(Object values) {
        return values instanceof List<?> list && !list.isEmpty()
                ? String.join(", ", list.stream().map(Object::toString).toList())
                : "-";
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
