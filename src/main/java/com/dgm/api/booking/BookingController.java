package com.dgm.api.booking;

import com.dgm.api.config.BusinessProperties;
import com.dgm.api.crm.CrmService;
import com.dgm.api.config.SupabaseClient;
import com.dgm.api.notification.NotificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Public booking endpoint — no auth required.
 * Called by the Lovable frontend when a guest submits the booking form.
 *
 * Handles both standard (one_off) and urgent (reactive) bookings.
 * Creates the work_order record in Supabase and fires the
 * booking-confirmed SMS to the guest.
 *
 * This is deliberately thin — all it does is write the record
 * and trigger the notification. Business logic lives in services.
 */
@Slf4j
@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final SupabaseClient      supabase;
    private final NotificationService notificationService;
    private final BusinessProperties  business;
    private final CrmService          crmService;

    /**
     * POST /api/bookings
     * Guest submits the booking form. No auth token required.
     * Returns the created work order id and a human-readable confirmation.
     */
    @PostMapping
    public ResponseEntity<BookingResponse> createBooking(
            @Valid @RequestBody BookingRequest req) {

        // Build guest address string from individual fields
        String fullAddress = req.address()
                + (req.unitNumber() != null && !req.unitNumber().isBlank()
                    ? " Unit " + req.unitNumber() : "")
                + ", " + req.city() + ", TN " + req.zip();

        Map<String, Object> wo = new HashMap<>();
        wo.put("guest_name",              req.guestName());
        wo.put("guest_phone",             req.guestPhone());
        wo.put("guest_email",             req.guestEmail());
        wo.put("guest_address",           fullAddress);
        wo.put("service_category",        req.serviceCategory());
        wo.put("task_name",               req.taskName());
        wo.put("description",             req.description());
        wo.put("status",                  "new");
        wo.put("job_type",                req.isUrgent() ? "reactive" : "one_off");
        wo.put("urgency_flag",            req.isUrgent());
        wo.put("scheduled_date",          req.isUrgent() ? null : req.scheduledDate());
        wo.put("scheduled_time",          req.isUrgent() ? null : req.scheduledTime());
        wo.put("estimated_duration_mins", req.estimatedDurationMins() != null
                                            ? req.estimatedDurationMins() : 60);

        Map<String, Object> created = supabase.insert("work_orders", wo);
        String woId = created.get("id").toString();

        // Auto-create or match CRM customer profile — fire-and-forget
        try {
            crmService.findOrCreateFromBooking(
                req.guestName(), req.guestPhone(), req.guestEmail(),
                fullAddress, req.unitNumber(), req.city(), req.zip(), woId);
        } catch (Exception e) {
            log.warn("CRM customer create failed for WO {}: {}", woId, e.getMessage());
        }

        // Booking confirmed SMS to guest — fire-and-forget
        notificationService.sendBookingConfirmed(req.guestPhone(), req.guestName());

        // Urgent jobs also alert the owner immediately via SMS
        if (req.isUrgent()) {
            notificationService.sendUrgentJob(
                business.getOwnerPhone(),
                woId,
                fullAddress,
                req.taskName()
            );
        }

        log.info("Booking created: WO {} | {} | urgent={}",
                woId, req.taskName(), req.isUrgent());

        return ResponseEntity.ok(new BookingResponse(
            woId,
            "Booking confirmed! We will reach out shortly to confirm your appointment.",
            req.isUrgent()
        ));
    }

    // ── Request ───────────────────────────────────────────────────────────────

    public record BookingRequest(
        @NotBlank String  guestName,
        @NotBlank String  guestPhone,
                  String  guestEmail,        // optional
        @NotBlank String  address,
                  String  unitNumber,        // optional
        @NotBlank String  city,
        @NotBlank String  zip,
        @NotBlank String  serviceCategory,
        @NotBlank String  taskName,
        @NotBlank String  description,
                  Boolean urgencyFlag,
                  String  scheduledDate,     // null for urgent jobs
                  String  scheduledTime,     // null for urgent jobs
                  Integer estimatedDurationMins
    ) {
        // Convenience method — null-safe boolean check
        public boolean isUrgent() {
            return Boolean.TRUE.equals(urgencyFlag);
        }
    }

    // ── Response ──────────────────────────────────────────────────────────────

    public record BookingResponse(
        String  workOrderId,
        String  message,
        Boolean urgent
    ) {}
}
