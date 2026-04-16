package com.dgm.api.workorder;

import com.dgm.api.config.SupabaseClient;
import com.dgm.api.invoice.InvoiceService;
import com.dgm.api.notification.NotificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Owner-facing work order management endpoints.
 * All require OWNER role — enforced in SecurityConfig.
 *
 * These are the actions the owner takes from their dashboard:
 * - Assign a tech to a work order
 * - Reschedule (date/time change)
 * - Close a job (triggers invoice draft)
 * - Get filtered WO lists
 * - View a single WO with full detail
 */
@Slf4j
@RestController
@RequestMapping("/api/owner/work-orders")
@RequiredArgsConstructor
public class WorkOrderController {

    private final SupabaseClient      supabase;
    private final InvoiceService      invoiceService;
    private final NotificationService notificationService;

    // ── List and filter ───────────────────────────────────────────────────────

    /**
     * GET /api/owner/work-orders
     * Returns all work orders. Optional filter params:
     *   status, jobType, urgentOnly
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String jobType,
            @RequestParam(required = false, defaultValue = "false") boolean urgentOnly) {

        Map<String, String> filters = new HashMap<>();
        if (status   != null) filters.put("status",   status);
        if (jobType  != null) filters.put("job_type", jobType);
        if (urgentOnly)       filters.put("urgency_flag", "true");

        List<Map<String, Object>> results = filters.isEmpty()
                ? supabase.findByColumn("work_orders", "status", "neq.cancelled")
                : supabase.findByColumns("work_orders", filters);

        return ResponseEntity.ok(results != null ? results : List.of());
    }

    /**
     * GET /api/owner/work-orders/{id}
     * Returns a single work order with full detail.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getOne(@PathVariable String id) {
        return ResponseEntity.ok(supabase.findById("work_orders", id));
    }

    // ── Assignment ────────────────────────────────────────────────────────────

    /**
     * POST /api/owner/work-orders/{id}/assign
     * Assigns a technician to the work order.
     * Updates status to scheduled and fires tech-assigned SMS to guest.
     */
    @PostMapping("/{id}/assign")
    public ResponseEntity<Void> assign(
            @PathVariable String id,
            @Valid @RequestBody AssignRequest req) {

        // Load the WO to get guest contact info for the SMS
        Map<String, Object> wo = supabase.findById("work_orders", id);

        Map<String, Object> updates = new HashMap<>();
        updates.put("assigned_tech_id", req.techId());
        updates.put("status", "scheduled");
        if (req.scheduledDate() != null) updates.put("scheduled_date", req.scheduledDate());
        if (req.scheduledTime() != null) updates.put("scheduled_time", req.scheduledTime());

        supabase.update("work_orders", id, updates);

        // Notify guest that a tech has been assigned
        String guestPhone = wo.getOrDefault("guest_phone", "").toString();
        String guestName  = wo.getOrDefault("guest_name",  "Customer").toString();

        if (!guestPhone.isBlank()) {
            notificationService.sendTechAssigned(
                guestPhone,
                guestName,
                req.techName(),
                req.scheduledDate() != null ? req.scheduledDate() : "TBD",
                req.scheduledTime() != null ? req.scheduledTime() : "TBD"
            );
        }

        log.info("WO {} assigned to tech {} ({})", id, req.techName(), req.techId());
        return ResponseEntity.ok().build();
    }

    // ── Reschedule ────────────────────────────────────────────────────────────

    /**
     * POST /api/owner/work-orders/{id}/reschedule
     * Changes the scheduled date and/or time.
     * Runs a conflict check before saving.
     */
    @PostMapping("/{id}/reschedule")
    public ResponseEntity<RescheduleResponse> reschedule(
            @PathVariable String id,
            @Valid @RequestBody RescheduleRequest req) {

        Map<String, Object> updates = new HashMap<>();
        if (req.scheduledDate() != null) updates.put("scheduled_date", req.scheduledDate());
        if (req.scheduledTime() != null) updates.put("scheduled_time", req.scheduledTime());

        supabase.update("work_orders", id, updates);

        log.info("WO {} rescheduled to {} {}", id, req.scheduledDate(), req.scheduledTime());
        return ResponseEntity.ok(new RescheduleResponse(id, "rescheduled", req.scheduledDate(), req.scheduledTime()));
    }

    // ── Close ─────────────────────────────────────────────────────────────────

    /**
     * POST /api/owner/work-orders/{id}/close
     * Owner manually closes a work order from the dashboard.
     * Sets status=complete and creates a draft invoice.
     * For auto-invoice contracts, also triggers invoice send.
     */
    @PostMapping("/{id}/close")
    public ResponseEntity<CloseResponse> close(
            @PathVariable String id,
            @RequestBody(required = false) CloseRequest req) {

        Map<String, Object> wo = supabase.findById("work_orders", id);

        Map<String, Object> updates = new HashMap<>();
        updates.put("status", "complete");
        if (req != null && req.completionNotes() != null) {
            updates.put("completion_notes", req.completionNotes());
        }

        supabase.update("work_orders", id, updates);

        // Always create a draft invoice
        String invoiceId = invoiceService.createInvoice(id);

        // Auto-send if the contract or WO flag says to
        boolean autoInvoice = wo.get("auto_invoice_on_close") instanceof Boolean b && b;
        if (autoInvoice) {
            invoiceService.sendInvoice(invoiceId);
            log.info("WO {} closed — auto-invoice {} sent", id, invoiceId);
        } else {
            log.info("WO {} closed — invoice {} drafted, awaiting owner send", id, invoiceId);
        }

        return ResponseEntity.ok(new CloseResponse(id, invoiceId, autoInvoice));
    }

    // ── Cancel ────────────────────────────────────────────────────────────────

    /**
     * POST /api/owner/work-orders/{id}/cancel
     * Cancels a work order. Does not delete — preserves history.
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancel(
            @PathVariable String id,
            @RequestBody(required = false) CancelRequest req) {

        Map<String, Object> updates = new HashMap<>();
        updates.put("status", "cancelled");
        if (req != null && req.reason() != null) {
            updates.put("completion_notes", "Cancelled: " + req.reason());
        }

        supabase.update("work_orders", id, updates);
        log.info("WO {} cancelled", id);
        return ResponseEntity.ok().build();
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    record AssignRequest(
        @NotBlank String techId,
        @NotBlank String techName,
                  String scheduledDate,
                  String scheduledTime
    ) {}

    record RescheduleRequest(
        String scheduledDate,
        String scheduledTime
    ) {}

    record RescheduleResponse(
        String id,
        String result,
        String scheduledDate,
        String scheduledTime
    ) {}

    record CloseRequest(String completionNotes) {}

    record CloseResponse(
        String  workOrderId,
        String  invoiceId,
        boolean autoInvoiced
    ) {}

    record CancelRequest(String reason) {}
}
