package com.dgm.api.booking;

import com.dgm.api.booking.BookingController.BookingRequest;
import com.dgm.api.config.BusinessProperties;
import com.dgm.api.config.SupabaseClient;
import com.dgm.api.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BookingControllerTest {

    @Mock private SupabaseClient      supabase;
    @Mock private NotificationService notificationService;
    @Mock private BusinessProperties  business;

    @InjectMocks
    private BookingController controller;

    @BeforeEach
    void setup() {
        when(supabase.insert(eq("work_orders"), any()))
            .thenReturn(Map.of("id", "wo-test-123"));
        when(business.getOwnerPhone()).thenReturn("+16155550001");
        // sendBookingConfirmed and sendUrgentJob are void — no stubbing needed
        doNothing().when(notificationService).sendBookingConfirmed(anyString(), anyString());
        doNothing().when(notificationService).sendUrgentJob(any(), anyString(), anyString(), anyString());
    }

    private BookingRequest standardRequest() {
        return new BookingRequest(
            "Jane Smith", "+16155550000", "jane@example.com",
            "123 Main St", null, "Nashville", "37201",
            "GENERAL_REPAIRS", "Leaky faucet repair",
            "Kitchen faucet drips constantly",
            false, "2025-08-01", "10:00", 60
        );
    }

    private BookingRequest urgentRequest() {
        return new BookingRequest(
            "Bob Jones", "+16155550002", null,
            "456 Oak Ave", "2B", "Nashville", "37203",
            "EMERGENCY", "Burst pipe first response",
            "Water spraying from under sink — need help now",
            true, null, null, null
        );
    }

    @Test
    void standardBooking_createsWorkOrderWithCorrectFields() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);

        controller.createBooking(standardRequest());

        verify(supabase).insert(eq("work_orders"), captor.capture());
        Map<String, Object> wo = captor.getValue();

        assertThat(wo.get("guest_name")).isEqualTo("Jane Smith");
        assertThat(wo.get("task_name")).isEqualTo("Leaky faucet repair");
        assertThat(wo.get("status")).isEqualTo("new");
        assertThat(wo.get("job_type")).isEqualTo("one_off");
        assertThat(wo.get("urgency_flag")).isEqualTo(false);
        assertThat(wo.get("scheduled_date")).isEqualTo("2025-08-01");
    }

    @Test
    void standardBooking_returnsWorkOrderId() {
        ResponseEntity<BookingController.BookingResponse> res =
            controller.createBooking(standardRequest());

        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().workOrderId()).isEqualTo("wo-test-123");
        assertThat(res.getBody().urgent()).isFalse();
    }

    @Test
    void standardBooking_sendsConfirmationSms() {
        controller.createBooking(standardRequest());

        verify(notificationService).sendBookingConfirmed(
            eq("+16155550000"), eq("Jane Smith"));
    }

    @Test
    void standardBooking_doesNotAlertOwner() {
        controller.createBooking(standardRequest());

        verify(notificationService, never()).sendUrgentJob(
            any(), any(), any(), any());
    }

    @Test
    void urgentBooking_setsCorrectJobType() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);

        controller.createBooking(urgentRequest());

        verify(supabase).insert(eq("work_orders"), captor.capture());
        Map<String, Object> wo = captor.getValue();

        assertThat(wo.get("job_type")).isEqualTo("reactive");
        assertThat(wo.get("urgency_flag")).isEqualTo(true);
        assertThat(wo.get("scheduled_date")).isNull();
        assertThat(wo.get("scheduled_time")).isNull();
    }

    @Test
    void urgentBooking_alertsOwner() {
        controller.createBooking(urgentRequest());

        verify(notificationService).sendUrgentJob(
            eq("+16155550001"),
            eq("wo-test-123"),
            contains("456 Oak Ave"),
            eq("Burst pipe first response")
        );
    }

    @Test
    void urgentBooking_unitNumberIncludedInAddress() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);

        controller.createBooking(urgentRequest());

        verify(supabase).insert(eq("work_orders"), captor.capture());
        String address = captor.getValue().get("guest_address").toString();
        assertThat(address).contains("Unit 2B");
    }

    @Test
    void nullUrgencyFlag_treatedAsNonUrgent() {
        BookingRequest req = new BookingRequest(
            "Test User", "+16155550000", null,
            "789 Elm St", null, "Nashville", "37204",
            "GENERAL_REPAIRS", "Door repair", "Sticking door",
            null, "2025-08-02", "14:00", 60
        );

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        controller.createBooking(req);

        verify(supabase).insert(eq("work_orders"), captor.capture());
        assertThat(captor.getValue().get("urgency_flag")).isEqualTo(false);
        verify(notificationService, never()).sendUrgentJob(any(), any(), any(), any());
    }
}
