package com.dgm.api.availability;

import com.dgm.api.config.SupabaseClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AvailabilityService {

    private final SupabaseClient supabase;

    @Value("${business.timezone:America/Chicago}")
    private String timezone;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Returns all open time windows for a given date and job duration.
     * Accounts for existing bookings, blocked slots, and travel buffer.
     */
    public List<TimeSlotDTO> getAvailableSlots(LocalDate date, int durationMins) {
        // 1. Load owner availability for this day of week
        int dayOfWeek = date.getDayOfWeek().getValue() % 7; // 0=Sun, 1=Mon...
        List<Map<String, Object>> availRows = supabase.findByColumn(
                "availability", "day_of_week", String.valueOf(dayOfWeek));

        if (availRows == null || availRows.isEmpty()) {
            log.info("No availability configured for day {}", dayOfWeek);
            return List.of();
        }

        Map<String, Object> avail = availRows.get(0);
        LocalTime workStart = LocalTime.parse(avail.get("start_time").toString(), TIME_FMT);
        LocalTime workEnd   = LocalTime.parse(avail.get("end_time").toString(), TIME_FMT);
        int bufferMins      = ((Number) avail.get("buffer_mins")).intValue();
        int maxJobs         = ((Number) avail.get("max_jobs_per_day")).intValue();

        // 2. Load booked work orders for this date
        List<Map<String, Object>> bookings = supabase.findByColumns(
                "work_orders",
                Map.of("scheduled_date", date.toString(),
                       "status", "neq.cancelled"));   // PostgREST syntax

        // Enforce max jobs per day
        if (bookings != null && bookings.size() >= maxJobs) {
            log.info("Max jobs ({}) reached for date {}", maxJobs, date);
            return List.of();
        }

        // 3. Load blocked slots for this date
        List<Map<String, Object>> blockedSlots = supabase.findByColumn(
                "blocked_slots", "date", date.toString());

        // 4. Build list of occupied intervals [start, end] including buffer
        List<LocalTime[]> occupied = new ArrayList<>();

        if (bookings != null) {
            for (Map<String, Object> booking : bookings) {
                if (booking.get("scheduled_time") == null) continue;
                LocalTime start = LocalTime.parse(
                        booking.get("scheduled_time").toString(), TIME_FMT);
                int estMins = booking.get("estimated_duration_mins") != null
                        ? ((Number) booking.get("estimated_duration_mins")).intValue()
                        : 60;
                LocalTime end = start.plusMinutes(estMins + bufferMins);
                occupied.add(new LocalTime[]{start, end});
            }
        }

        if (blockedSlots != null) {
            for (Map<String, Object> block : blockedSlots) {
                LocalTime start = LocalTime.parse(block.get("start_time").toString(), TIME_FMT);
                LocalTime end   = LocalTime.parse(block.get("end_time").toString(), TIME_FMT);
                occupied.add(new LocalTime[]{start, end});
            }
        }

        // Sort occupied intervals by start time
        occupied.sort(Comparator.comparing(a -> a[0]));

        // 5. Walk the working day and find free windows >= durationMins
        List<TimeSlotDTO> slots = new ArrayList<>();
        LocalTime cursor = workStart;

        for (LocalTime[] interval : occupied) {
            LocalTime blockStart = interval[0];
            LocalTime blockEnd   = interval[1];

            // Window before this block
            if (!cursor.isAfter(blockStart)) {
                addSlotsInWindow(cursor, blockStart, durationMins, slots);
            }
            // Advance cursor past this block
            if (blockEnd.isAfter(cursor)) {
                cursor = blockEnd;
            }
        }

        // Final window after all blocks
        addSlotsInWindow(cursor, workEnd, durationMins, slots);

        return slots;
    }

    /**
     * Checks whether a proposed booking would conflict with existing ones.
     */
    public ConflictResultDTO checkConflict(LocalDate date,
                                           LocalTime startTime,
                                           int durationMins) {
        List<Map<String, Object>> bookings = supabase.findByColumns(
                "work_orders",
                Map.of("scheduled_date", date.toString()));

        if (bookings == null) return new ConflictResultDTO(false, null);

        LocalTime proposedEnd = startTime.plusMinutes(durationMins);

        for (Map<String, Object> booking : bookings) {
            if (booking.get("scheduled_time") == null) continue;
            String status = booking.getOrDefault("status", "").toString();
            if (status.equals("cancelled")) continue;

            LocalTime existStart = LocalTime.parse(
                    booking.get("scheduled_time").toString(), TIME_FMT);
            int estMins = booking.get("estimated_duration_mins") != null
                    ? ((Number) booking.get("estimated_duration_mins")).intValue()
                    : 60;
            LocalTime existEnd = existStart.plusMinutes(estMins);

            // Overlap check: two intervals overlap if start1 < end2 AND start2 < end1
            boolean overlaps = startTime.isBefore(existEnd) && existStart.isBefore(proposedEnd);
            if (overlaps) {
                return new ConflictResultDTO(true, booking.get("id").toString());
            }
        }

        return new ConflictResultDTO(false, null);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Divides a free window into discrete slots of durationMins each,
     * stepping by 30-minute increments for customer-friendly choices.
     */
    private void addSlotsInWindow(LocalTime windowStart,
                                   LocalTime windowEnd,
                                   int durationMins,
                                   List<TimeSlotDTO> slots) {
        int stepMins = 30;
        LocalTime slotStart = windowStart;

        while (!slotStart.plusMinutes(durationMins).isAfter(windowEnd)) {
            LocalTime slotEnd = slotStart.plusMinutes(durationMins);
            slots.add(new TimeSlotDTO(slotStart.toString(), slotEnd.toString()));
            slotStart = slotStart.plusMinutes(stepMins);
        }
    }
}
