package com.dgm.api.crm;

import com.dgm.api.config.SupabaseClient;
import com.dgm.api.crm.CrmDTOs.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CrmServiceTest {

    @Mock
    private SupabaseClient supabase;

    @InjectMocks
    private CrmService crmService;

    // ── createCustomer ────────────────────────────────────────────────────────

    @Test
    void createCustomer_insertsCorrectRecord() {
        when(supabase.insert(eq("customers"), any()))
            .thenReturn(Map.of("id", "cust-1"));

        CreateCustomerRequest req = new CreateCustomerRequest(
            "Jane Smith", "+16155550000", "jane@example.com",
            "123 Main St", null, "Nashville", "37201",
            "new_lead", "manual_entry", false, "Met at church"
        );

        Map<String, Object> result = crmService.createCustomer(req);

        assertThat(result.get("id")).isEqualTo("cust-1");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(supabase).insert(eq("customers"), captor.capture());
        Map<String, Object> inserted = captor.getValue();
        assertThat(inserted.get("name")).isEqualTo("Jane Smith");
        assertThat(inserted.get("lead_status")).isEqualTo("new_lead");
        assertThat(inserted.get("source")).isEqualTo("manual_entry");
    }

    @Test
    void createCustomer_defaultsLeadStatusWhenNull() {
        when(supabase.insert(eq("customers"), any()))
            .thenReturn(Map.of("id", "cust-2"));

        CreateCustomerRequest req = new CreateCustomerRequest(
            "Bob Jones", "+16155550001", null,
            null, null, null, null,
            null, null, null, null
        );

        crmService.createCustomer(req);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(supabase).insert(eq("customers"), captor.capture());
        assertThat(captor.getValue().get("lead_status")).isEqualTo("new_lead");
        assertThat(captor.getValue().get("source")).isEqualTo("manual_entry");
        assertThat(captor.getValue().get("city")).isEqualTo("Nashville");
    }

    // ── findOrCreateFromBooking ───────────────────────────────────────────────

    @Test
    void findOrCreateFromBooking_existingPhone_returnsExistingCustomer() {
        Map<String, Object> existing = new HashMap<>();
        existing.put("id", "existing-cust");
        when(supabase.findByColumn("customers", "phone", "+16155550000"))
            .thenReturn(List.of(existing));
        doNothing().when(supabase).update(anyString(), anyString(), any());

        String customerId = crmService.findOrCreateFromBooking(
            "Jane", "+16155550000", null,
            "123 Main St", null, "Nashville", "37201", "wo-1");

        assertThat(customerId).isEqualTo("existing-cust");
        verify(supabase, never()).insert(eq("customers"), any());
    }

    @Test
    void findOrCreateFromBooking_newPhone_createsCustomer() {
        when(supabase.findByColumn("customers", "phone", "+16155550099"))
            .thenReturn(List.of());
        when(supabase.insert(eq("customers"), any()))
            .thenReturn(Map.of("id", "new-cust"));
        doNothing().when(supabase).update(anyString(), anyString(), any());

        String customerId = crmService.findOrCreateFromBooking(
            "New Person", "+16155550099", null,
            "456 Oak Ave", null, "Nashville", "37203", "wo-2");

        assertThat(customerId).isEqualTo("new-cust");
        verify(supabase).insert(eq("customers"), any());
    }

    @Test
    void findOrCreateFromBooking_setsLeadStatusToBooked() {
        when(supabase.findByColumn("customers", "phone", "+16155550099"))
            .thenReturn(List.of());
        when(supabase.insert(eq("customers"), any()))
            .thenReturn(Map.of("id", "new-cust"));
        doNothing().when(supabase).update(anyString(), anyString(), any());

        crmService.findOrCreateFromBooking(
            "New", "+16155550099", null, "Addr", null, "Nashville", "37201", "wo-3");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(supabase).insert(eq("customers"), captor.capture());
        assertThat(captor.getValue().get("lead_status")).isEqualTo("booked");
        assertThat(captor.getValue().get("source")).isEqualTo("booking_form");
    }

    // ── updateLeadStatus ──────────────────────────────────────────────────────

    @Test
    void updateLeadStatus_callsSupabaseWithCorrectStatus() {
        doNothing().when(supabase).update(anyString(), anyString(), any());

        crmService.updateLeadStatus("cust-1", "contacted");

        verify(supabase).update(eq("customers"), eq("cust-1"),
            argThat(m -> "contacted".equals(m.get("lead_status"))));
    }

    // ── addNote ───────────────────────────────────────────────────────────────

    @Test
    void addNote_insertsNoteWithCorrectFields() {
        when(supabase.insert(eq("customer_notes"), any()))
            .thenReturn(Map.of("id", "note-1"));

        AddNoteRequest req = new AddNoteRequest(
            "owner-uuid", "Virgil Donaldson", "Called to follow up — very interested");

        Map<String, Object> result = crmService.addNote("cust-1", req);

        assertThat(result.get("id")).isEqualTo("note-1");
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(supabase).insert(eq("customer_notes"), captor.capture());
        assertThat(captor.getValue().get("customer_id")).isEqualTo("cust-1");
        assertThat(captor.getValue().get("author_name")).isEqualTo("Virgil Donaldson");
        assertThat(captor.getValue().get("body").toString()).contains("Called to follow up");
    }

    // ── follow-ups ────────────────────────────────────────────────────────────

    @Test
    void createFollowUp_insertsWithPendingStatus() {
        when(supabase.insert(eq("follow_ups"), any()))
            .thenReturn(Map.of("id", "fu-1"));

        CreateFollowUpRequest req = new CreateFollowUpRequest(
            "owner-uuid", "Call back about estimate", null, "2025-08-01");

        Map<String, Object> result = crmService.createFollowUp("cust-1", req);

        assertThat(result.get("id")).isEqualTo("fu-1");
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(supabase).insert(eq("follow_ups"), captor.capture());
        assertThat(captor.getValue().get("status")).isEqualTo("pending");
        assertThat(captor.getValue().get("due_date")).isEqualTo("2025-08-01");
    }

    @Test
    void completeFollowUp_setsStatusToDone() {
        doNothing().when(supabase).update(anyString(), anyString(), any());

        crmService.completeFollowUp("fu-1");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(supabase).update(eq("follow_ups"), eq("fu-1"), captor.capture());
        assertThat(captor.getValue().get("status")).isEqualTo("done");
        assertThat(captor.getValue().get("completed_at")).isNotNull();
    }

    @Test
    void snoozeFollowUp_setsStatusAndDate() {
        doNothing().when(supabase).update(anyString(), anyString(), any());

        crmService.snoozeFollowUp("fu-1", "2025-08-15");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(supabase).update(eq("follow_ups"), eq("fu-1"), captor.capture());
        assertThat(captor.getValue().get("status")).isEqualTo("snoozed");
        assertThat(captor.getValue().get("snoozed_until")).isEqualTo("2025-08-15");
    }
}
