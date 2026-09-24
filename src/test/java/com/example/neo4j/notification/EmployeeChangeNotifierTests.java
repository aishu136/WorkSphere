package com.example.neo4j.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.example.neo4j.entity.AppUser;
import com.example.neo4j.entity.Employee;
import com.example.neo4j.entity.EmploymentStatus;
import com.example.neo4j.notification.EmployeeChangeEvent.Type;
import com.example.neo4j.outbox.OutboxService;
import com.example.neo4j.repository.AppUserRepository;
import com.example.neo4j.repository.EmployeeRepository;

/** Who is emailed about manager self-service changes, and what they're told. No database. */
class EmployeeChangeNotifierTests {

    record Queued(String to, String subject, String body, String reference) {
    }

    private OutboxService outbox;
    private EmployeeRepository employees;
    private AppUserRepository users;

    @BeforeEach
    void setUp() {
        outbox = mock(OutboxService.class);
        employees = mock(EmployeeRepository.class);
        users = mock(AppUserRepository.class);

        // Chitra (login "chitra") manages Lena, who manages Dev.
        givenEmployee("e-chitra", "Chitra CTO", "chitra@example.com");
        givenEmployee("e-lena", "Lena Lead", "lena@example.com");
        givenEmployee("e-dev", "Dev Kumar", "dev@example.com");

        AppUser chitra = new AppUser();
        chitra.setUsername("chitra");
        chitra.setEmployeeId("e-chitra");
        when(users.findByUsername("chitra")).thenReturn(Optional.of(chitra));
    }

    private void givenEmployee(String id, String name, String email) {
        Employee employee = new Employee();
        employee.setId(id);
        employee.setName(name);
        employee.setEmail(email);
        employee.setEmployeeCode(id.toUpperCase());
        when(employees.findById(id)).thenReturn(Optional.of(employee));
    }

    private EmployeeChangeNotifier notifier() {
        return new EmployeeChangeNotifier(outbox, employees, users, "hr@example.com", "https://worksphere.example.com");
    }

    private static Map<String, Object> change(String field, Object from, Object to) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("from", from);
        change.put("to", to);
        return Map.of(field, change);
    }

    private static EmployeeChangeEvent byManager(Type type, Map<String, Object> details) {
        return new EmployeeChangeEvent("e-dev", type, details, "chitra", true);
    }

    private List<Queued> queued(int expected) {
        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> reference = ArgumentCaptor.forClass(String.class);
        verify(outbox, times(expected)).enqueue(to.capture(), subject.capture(), body.capture(), reference.capture());
        return IntStream.range(0, expected)
                .mapToObj(i -> new Queued(to.getAllValues().get(i), subject.getAllValues().get(i),
                        body.getAllValues().get(i), reference.getAllValues().get(i)))
                .toList();
    }

    @Test
    void changesByHrAreNotEmailed() {
        notifier().onEmployeeChange(new EmployeeChangeEvent("e-dev", Type.STATUS_CHANGED,
                change("status", EmploymentStatus.ACTIVE, EmploymentStatus.ON_LEAVE), "hr.user", false));

        verify(outbox, never()).enqueue(any(), any(), any(), any());
    }

    @Test
    void leaveSetByAManagerTellsTheEmployeeAndHr() {
        notifier().onEmployeeChange(byManager(Type.STATUS_CHANGED,
                change("status", EmploymentStatus.ACTIVE, EmploymentStatus.ON_LEAVE)));

        List<Queued> emails = queued(2);

        assertThat(emails.get(0).to()).isEqualTo("dev@example.com");
        assertThat(emails.get(0).subject()).isEqualTo("[WorkSphere] Your status is now: On leave");
        assertThat(emails.get(0).body())
                .contains("Chitra CTO changed your employment status")
                .contains("Previous status: Active")
                .contains("New status:      On leave");
        assertThat(emails.get(0).reference()).isEqualTo("employee-change:e-dev:STATUS_CHANGED");

        assertThat(emails.get(1).to()).isEqualTo("hr@example.com");
        assertThat(emails.get(1).subject())
                .isEqualTo("[WorkSphere] Status changed by a manager: Dev Kumar is now On leave");
        assertThat(emails.get(1).body())
                .contains("Employee:   Dev Kumar (E-DEV)")
                .contains("Active -> On leave")
                .contains("Changed by: Chitra CTO")
                .contains("https://worksphere.example.com/employees/e-dev/history");
    }

    @Test
    void aMoveTellsTheEmployeeTheNewManagerAndHr() {
        notifier().onEmployeeChange(byManager(Type.MANAGER_CHANGED, change("managerId", "e-chitra", "e-lena")));

        List<Queued> emails = queued(3);
        assertThat(emails).extracting(Queued::to)
                .containsExactly("dev@example.com", "lena@example.com", "hr@example.com");

        assertThat(emails.get(0).subject()).isEqualTo("[WorkSphere] You now report to Lena Lead");
        assertThat(emails.get(0).body()).contains("Previous manager: Chitra CTO").contains("New manager:      Lena Lead");
        assertThat(emails.get(1).subject()).isEqualTo("[WorkSphere] Dev Kumar now reports to you");
        assertThat(emails.get(2).subject()).isEqualTo("[WorkSphere] Reporting line changed by a manager: Dev Kumar");
    }

    @Test
    void nobodyIsEmailedAboutTheirOwnAction() {
        // Chitra moves Dev to report to herself: she isn't told she has a new report.
        notifier().onEmployeeChange(byManager(Type.MANAGER_CHANGED, change("managerId", "e-lena", "e-chitra")));

        assertThat(queued(2)).extracting(Queued::to).containsExactly("dev@example.com", "hr@example.com");
    }

    @Test
    void skillChangesOnlyTellTheEmployee() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("added", List.of("Kafka", "Spring"));
        details.put("removed", List.of());

        notifier().onEmployeeChange(byManager(Type.SKILLS_CHANGED, details));

        Queued email = queued(1).get(0);
        assertThat(email.to()).isEqualTo("dev@example.com");
        assertThat(email.subject()).isEqualTo("[WorkSphere] Your skills were updated");
        assertThat(email.body())
                .contains("Chitra CTO updated your skills")
                .contains("Added:   Kafka, Spring")
                .contains("Removed: -");
    }

    @Test
    void peopleWithoutAnEmailAreSkipped() {
        givenEmployee("e-dev", "Dev Kumar", null);

        notifier().onEmployeeChange(byManager(Type.STATUS_CHANGED,
                change("status", EmploymentStatus.ACTIVE, EmploymentStatus.ON_LEAVE)));

        assertThat(queued(1).get(0).to()).isEqualTo("hr@example.com");
    }

    @Test
    void aProblemBuildingTheEmailNeverBlocksTheChange() {
        when(employees.findById("e-dev")).thenThrow(new IllegalStateException("unexpected data"));

        notifier().onEmployeeChange(byManager(Type.SKILLS_CHANGED, Map.of("added", List.of("Go"))));

        verify(outbox, never()).enqueue(any(), any(), any(), any());
    }
}
