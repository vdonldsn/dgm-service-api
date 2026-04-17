package com.dgm.api.availability;

import com.dgm.api.config.SupabaseClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AvailabilityServiceTest {

    @Mock
    private SupabaseClient supabase;

    @InjectMocks
    private AvailabilityService service;

    private final LocalDate TEST_DATE = LocalDate.of(2025, 8, 1);

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(service, "timezone", "America/Chicago");
        // Stub all three findByColumn calls leniently — each test uses a subset
        when(supabase.findByColumn(eq("availability"), eq("day_of_week"), anyString()))
            .thenReturn(List.of(availRow("08:00:00", "17:00:00", 30, 6)));
        when(supabase.findByColumns(eq("work_orders"), any()))
            .thenReturn(List.of());
        when(supabase.findByColumn(eq("blocked_slots"), eq("date"), anyString()))
            .thenReturn(List.of());
    }

    private Map<String, Object> availRow(String start, String end, int buffer, int maxJobs) {
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
        List<TimeSlotDTO> slots = service.getAvailableSlots(TEST_DATE, 60);

        assertThat(slots).isNotEmpty();
        assertThat(slots.get(0).startTime()).startsWith("08:00");
    }

    @Test
    void bookingInMiddle_splitsDayCorrectly() {
        when(supabase.findByColumns(eq("work_orders"), any()))
            .thenReturn(List.of(booking("10:00:00", 60)));

        List<TimeSlotDTO> slots = service.getAvailableSlots(TEST_DATE, 60);

        boolean hasBlockedSlot = slots.stream()
            .anyMatch(s -> s.startTime().startsWith("10:"));
        assertThat(hasBlockedSlot).isFalse();

        boolean hasMorningSlot = slots.stream()
            .anyMatch(s -> s.startTime().startsWith("08:"));
        assertThat(hasMorningSlot).isTrue();
    }

    @Test
    void bufferMins_appliedAfterEachJob() {
        when(supabase.findByColumn(eq("availability"), eq("day_of_week"), anyString()))
            .thenReturn(List.of(availRow("08:00:00", "17:00:00", 45, 6)));
        when(supabase.findByColumns(eq("work_orders"), any()))
            .thenReturn(List.of(booking("09:00:00", 60)));

        List<TimeSlotDTO> slots = service.getAvailableSlots(TEST_DATE, 60);

        // 09:00 job + 60 min + 45 buffer = blocked until 10:45
        boolean slotAt1000 = slots.stream()
            .anyMatch(s -> s.startTime().equals("10:00"));
        assertThat(slotAt1000).isFalse();
    }

    @Test
    void blockedSlot_removedFromAvailableWindows() {
        when(supabase.findByColumn(eq("blocked_slots"), eq("date"), anyString()))
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
        when(supabase.findByColumn(eq("availability"), eq("day_of_week"), anyString()))
            .thenReturn(List.of(availRow("08:00:00", "17:00:00", 30, 2)));
        when(supabase.findByColumns(eq("work_orders"), any()))
            .thenReturn(List.of(booking("08:00:00", 60), booking("10:00:00", 60)));

        List<TimeSlotDTO> slots = service.getAvailableSlots(TEST_DATE, 60);
        assertThat(slots).isEmpty();
    }

    @Test
    void noDayConfig_returnsEmptyList() {
        when(supabase.findByColumn(eq("availability"), eq("day_of_week"), anyString()))
            .thenReturn(List.of());

        List<TimeSlotDTO> slots = service.getAvailableSlots(TEST_DATE, 60);
        assertThat(slots).isEmpty();
    }

    @Test
    void checkConflict_detectsOverlap() {
        when(supabase.findByColumns(eq("work_orders"), any()))
            .thenReturn(List.of(booking("10:00:00", 60)));

        ConflictResultDTO result = service.checkConflict(
            TEST_DATE, LocalTime.of(10, 30), 60);

        assertThat(result.conflict()).isTrue();
        assertThat(result.conflictingWoId()).isNotNull();
    }

    @Test
    void checkConflict_noOverlapAfterJob() {
        when(supabase.findByColumns(eq("work_orders"), any()))
            .thenReturn(List.of(booking("10:00:00", 60)));

        ConflictResultDTO result = service.checkConflict(
            TEST_DATE, LocalTime.of(11, 0), 60);

        assertThat(result.conflict()).isFalse();
    }
}
