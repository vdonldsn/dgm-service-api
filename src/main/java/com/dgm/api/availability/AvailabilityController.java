package com.dgm.api.availability;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@RestController
@RequestMapping("/api/availability")
@RequiredArgsConstructor
public class AvailabilityController {

    private final AvailabilityService availabilityService;

    /**
     * GET /api/availability/slots?date=2025-08-01&durationMins=60
     * Public — called by the customer booking calendar.
     * Returns open time windows for the given date and job duration.
     */
    @GetMapping("/slots")
    public ResponseEntity<List<TimeSlotDTO>> getSlots(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "60") int durationMins) {

        List<TimeSlotDTO> slots = availabilityService.getAvailableSlots(date, durationMins);
        return ResponseEntity.ok(slots);
    }

    /**
     * POST /api/availability/check-conflict
     * Called by the owner calendar before saving a reschedule.
     * Returns whether the proposed time conflicts with an existing booking.
     */
    @PostMapping("/check-conflict")
    public ResponseEntity<ConflictResultDTO> checkConflict(
            @RequestBody ConflictCheckRequest request) {

        LocalDate date      = LocalDate.parse(request.date());
        LocalTime startTime = LocalTime.parse(request.startTime());
        int durationMins    = request.durationMins() != null ? request.durationMins() : 60;

        ConflictResultDTO result = availabilityService.checkConflict(
                date, startTime, durationMins);
        return ResponseEntity.ok(result);
    }
}
