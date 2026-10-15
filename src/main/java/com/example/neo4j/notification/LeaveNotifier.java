package com.example.neo4j.notification;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.example.neo4j.dto.EmployeeResponse;
import com.example.neo4j.dto.LeaveRequestResponse;
import com.example.neo4j.entity.Employee;
import com.example.neo4j.outbox.OutboxService;
import com.example.neo4j.repository.EmployeeQueries;
import com.example.neo4j.repository.EmployeeRepository;
import com.example.neo4j.repository.LeaveQueries;

/**
 * Emails people about the leave workflow:
 *
 * <pre>
 *   REQUESTED -> the employee's manager (the HR mailbox if they have no manager or no email)
 *   APPROVED  -> the employee
 *   REJECTED  -> the employee, with the reason
 *   CANCELLED -> the employee's manager (the HR mailbox as above)
 * </pre>
 *
 * Like StaffingNotifier, it runs just before the business transaction commits and puts the emails
 * in the outbox, so the change and its emails are saved together or not at all. A problem building
 * an email is logged and never blocks the change. Emails are plain text.
 */
@Component
public class LeaveNotifier {

    private static final Logger log = LoggerFactory.getLogger(LeaveNotifier.class);

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);

    /** One email to send. */
    record Email(String to, String subject, String body) {
    }

    private final OutboxService outbox;
    private final LeaveQueries leaveQueries;
    private final EmployeeQueries employeeQueries;
    private final EmployeeRepository employeeRepository;
    private final String hrEmail;
    private final String baseUrl;

    public LeaveNotifier(OutboxService outbox, LeaveQueries leaveQueries, EmployeeQueries employeeQueries,
                         EmployeeRepository employeeRepository,
                         @Value("${app.notifications.hr-email:}") String hrEmail,
                         @Value("${app.notifications.base-url:}") String baseUrl) {
        this.outbox = outbox;
        this.leaveQueries = leaveQueries;
        this.employeeQueries = employeeQueries;
        this.employeeRepository = employeeRepository;
        this.hrEmail = hrEmail;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onLeaveRequestEvent(LeaveRequestEvent event) {

        List<Email> emails;
        try {
            emails = emailsFor(event);
        } catch (Exception e) {
            // A bug in building an email must never block the leave change itself.
            log.error("Could not prepare leave notification for request {}", event.requestId(), e);
            return;
        }

        String reference = "leave-request:" + event.requestId() + ":" + event.type();
        for (Email email : emails) {
            outbox.enqueue(email.to(), "[WorkSphere] " + StaffingNotifier.singleLine(email.subject()), email.body(),
                    reference);
        }
    }

    // ---- Building emails ----------------------------------------------------------

    List<Email> emailsFor(LeaveRequestEvent event) {

        Optional<LeaveRequestResponse> found = leaveQueries.findById(event.requestId());
        if (found.isEmpty() || found.get().employee() == null) {
            log.warn("Leave request {} or its employee not found; no notification sent", event.requestId());
            return List.of();
        }
        LeaveRequestResponse leave = found.get();
        String name = leave.employee().name();
        String what = leave.type().name().toLowerCase(Locale.ROOT) + " leave, " + dates(leave);

        List<Email> emails = new ArrayList<>();

        switch (event.type()) {
            case REQUESTED -> approverEmail(leave).ifPresent(to -> emails.add(new Email(to,
                    "Leave request from " + name + ": " + dates(leave),
                    """
                    %s has asked for %s and needs your approval.

                    Type:         %s
                    Dates:        %s
                    Working days: %d
                    Note:         %s

                    %s
                    """.formatted(name, what, leave.type(), dates(leave), leave.workingDays(),
                            orDash(leave.note()), link(leave)))));

            case CANCELLED -> approverEmail(leave).ifPresent(to -> emails.add(new Email(to,
                    "Leave cancelled by " + name + ": " + dates(leave),
                    """
                    %s has cancelled their %s. No action is needed.
                    """.formatted(name, what))));

            case APPROVED -> employeeEmail(leave).ifPresent(to -> emails.add(new Email(to,
                    "Leave approved: " + dates(leave),
                    """
                    Your %s was approved by %s.

                    Working days: %d

                    %s
                    """.formatted(what, leave.decidedBy(), leave.workingDays(), link(leave)))));

            case REJECTED -> employeeEmail(leave).ifPresent(to -> emails.add(new Email(to,
                    "Leave not approved: " + dates(leave),
                    """
                    Your %s was rejected by %s.

                    Reason: %s

                    %s
                    """.formatted(what, leave.decidedBy(), leave.reason(), link(leave)))));
        }

        if (emails.isEmpty()) {
            log.info("No recipients with an email address for {} of leave request {}", event.type(),
                    event.requestId());
        }
        return emails;
    }

    // ---- Helpers ------------------------------------------------------------------

    // The direct manager decides day to day; without one (or without their email), HR picks it up.
    private Optional<String> approverEmail(LeaveRequestResponse leave) {
        Optional<String> manager = employeeQueries.findById(leave.employee().id())
                .map(EmployeeResponse::manager)
                .flatMap(summary -> employeeRepository.findById(summary.id()))
                .map(Employee::getEmail)
                .filter(LeaveNotifier::hasText);
        return manager.isPresent() ? manager : hrMailbox();
    }

    private Optional<String> employeeEmail(LeaveRequestResponse leave) {
        return employeeRepository.findById(leave.employee().id())
                .map(Employee::getEmail)
                .filter(LeaveNotifier::hasText);
    }

    private Optional<String> hrMailbox() {
        if (!hasText(hrEmail)) {
            log.warn("app.notifications.hr-email is not set; HR is not notified of leave requests");
            return Optional.empty();
        }
        return Optional.of(hrEmail);
    }

    private static String dates(LeaveRequestResponse leave) {
        return leave.startDate().equals(leave.endDate())
                ? DAY.format(leave.startDate())
                : DAY.format(leave.startDate()) + " to " + DAY.format(leave.endDate());
    }

    private String link(LeaveRequestResponse leave) {
        return hasText(baseUrl)
                ? "Open the request: " + baseUrl + "/leave-requests/" + leave.id()
                : "Request id: " + leave.id();
    }

    private static String orDash(String value) {
        return hasText(value) ? value : "-";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
