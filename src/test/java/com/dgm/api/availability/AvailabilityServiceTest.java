package com.dgm.api.availability;

import com.dgm.api.config.SupabaseClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AvailabilityServiceTest {

    @Mock
    private SupabaseClient supabase;

    @InjectMocks
    private AvailabilityService service;

    private final LocalDate TEST_DATE = LocalDate.of(2025, 8, 1);

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(service, "timezone", "America/Chicago");
    }

    private Map<String, Object> availRow(String start, String end,
                                          int buffer, int maxJobs) {
        return Map.of(
            "start_time",       start,
            "end_time",         end,
            "buffer_mins",      buffer,
            "max_jobs_per_day", maxJobs
        );
    }

    private Map<String, Object> booking(String startTime, int durationMins) {
        return Map.of(
            "id",                      UUID.randomUUID().toString(),
            "scheduled_time",          startTime,
            "estimated_duration_mins", durationMins,
            "status",                  "scheduled"
        );
    }

    @Test
    void noBookings_returnsFullDayInSlots() {
        when(supabase.findByColumn("availability", "day_of_week", any()))
            .thenReturn(List.of(availRow("08:00:00", "17:00:00", 30, 6)));
        when(supabase.findByColumns(eq("work_orders"), any())).thenReturn(List.of());
        when(supabase.findByColumn("blocked_slots", "date", any())).thenReturn(List.of());

        List<TimeSlotDTO> slots = service.getAvailableSlots(TEST_DATE, 60);

        assertThat(slots).isNotEmpty();
        assertThat(slots.get(0).startTime()).isEqualTo("08:00");
    }

    @Test
    void bookingInMiddle_splitsDayCorrectly() {
        when(supabase.findByColumn("availability", "day_of_week", any()))
            .thenReturn(List.of(availRow("08:00:00", "17:00:00", 30, 6)));
        when(supabase.findByColumns(eq("work_orders"), any()))
            .thenReturn(List.of(booking("10:00:00", 60)));
        when(supabase.findByColumn("blocked_slots", "date", any())).thenReturn(List.of());

        List<TimeSlotDTO> slots = service.getAvailableSlots(TEST_DATE, 60);

        // 10:00–11:30 (60min job + 30min buffer) should be blocked
        boolean hasBlockedSlot = slots.stream()
            .anyMatch(s -> s.startTime().startsWith("10:") || s.startTime().startsWith("10:30"));
        assertThat(hasBlockedSlot).isFalse();

        // 08:00 should still be available
        boolean hasMorningSlot = slots.stream()
            .anyMatch(s -> s.startTime().startsWith("08:"));
        assertThat(hasMorningSlot).isTrue();
    }

    @Test
    void bufferMins_appliedAfterEachJob() {
        when(supabase.findByColumn("availability", "day_of_week", any()))
            .thenReturn(List.of(availRow("08:00:00", "17:00:00", 45, 6)));
        when(supabase.findByColumns(eq("work_orders"), any()))
            .thenReturn(List.of(booking("09:00:00", 60)));
        when(supabase.findByColumn("blocked_slots", "date", any())).thenReturn(List.of());

        List<TimeSlotDTO> slots = service.getAvailableSlots(TEST_DATE, 60);

        // 09:00 job + 60 min + 45 buffer = blocked until 10:45
        boolean slotAt1000 = slots.stream()
            .anyMatch(s -> s.startTime().equals("10:00"));
        assertThat(slotAt1000).isFalse();
    }

    @Test
    void blockedSlot_removedFromAvailableWindows() {
        when(supabase.findByColumn("availability", "day_of_week", any()))
            .thenReturn(List.of(availRow("08:00:00", "17:00:00", 30, 6)));
        when(supabase.findByColumns(eq("work_orders"), any())).thenReturn(List.of());
        when(supabase.findByColumn("blocked_slots", "date", any()))
            .thenReturn(List.of(Map.of(
                "start_time", "12:00:00",
                "end_time",   "13:00:00")));

        List<TimeSlotDTO> slots = service.getAvailableSlots(TEST_DATE, 60);

        boolean hasNoonSlot = slots.stream()
            .anyMatch(s -> s.startTime().startsWith("12:"));
        assertThat(hasNoonSlot).isFalse();
    }

    @Test
    void maxJobsReached_returnsEmptyList() {
        when(supabase.findByColumn("availability", "day_of_week", any()))
            .thenReturn(List.of(availRow("08:00:00", "17:00:00", 30, 2)));

        List<Map<String, Object>> fullDay = List.of(
            booking("08:00:00", 60),
            booking("10:00:00", 60)
        );
        when(supabase.findByColumns(eq("work_orders"), any())).thenReturn(fullDay);

        List<TimeSlotDTO> slots = service.getAvailableSlots(TEST_DATE, 60);
        assertThat(slots).isEmpty();
    }

    @Test
    void noDayConfig_returnsEmptyList() {
        when(supabase.findByColumn("availability", "day_of_week", any()))
            .thenReturn(List.of());

        List<TimeSlotDTO> slots = service.getAvailableSlots(TEST_DATE, 60);
        assertThat(slots).isEmpty();
    }

    @Test
    void checkConflict_detectsOverlap() {
        when(supabase.findByColumns(eq("work_orders"), any()))
            .thenReturn(List.of(booking("10:00:00", 60)));

        // Propose 10:30 — overlaps with 10:00–11:00 job
        ConflictResultDTO result = service.checkConflict(
            TEST_DATE,
            java.time.LocalTime.of(10, 30),
            60
        );
        assertThat(result.conflict()).isTrue();
        assertThat(result.conflictingWoId()).isNotNull();
    }

    @Test
    void checkConflict_noOverlapAfterJob() {
        when(supabase.findByColumns(eq("work_orders"), any()))
            .thenReturn(List.of(booking("10:00:00", 60)));

        // Propose 11:00 — starts exactly when previous job ends (no overlap)
        ConflictResultDTO result = service.checkConflict(
            TEST_DATE,
            java.time.LocalTime.of(11, 0),
            60
        );
        assertThat(result.conflict()).isFalse();
    }
}
