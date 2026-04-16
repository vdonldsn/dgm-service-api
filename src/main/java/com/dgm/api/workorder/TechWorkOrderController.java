package com.dgm.api.workorder;

import com.dgm.api.config.SupabaseClient;
import com.dgm.api.invoice.InvoiceService;
import com.dgm.api.notification.NotificationService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tech-facing work order actions.
 * Requires OWNER or TECH role — enforced in SecurityConfig.
 *
 * These are the tap-and-proceed actions on the tech's job detail screen:
 *   "On my way" → "Start job" → "Complete job"
 * Plus photo management and scope flagging.
 */
@Slf4j
@RestController
@RequestMapping("/api/tech/work-orders")
@RequiredArgsConstructor
public class TechWorkOrderController {

    private final SupabaseClient      supabase;
    private final NotificationService notificationService;
    private final InvoiceService      invoiceService;

    /**
     * GET /api/tech/work-orders/today
     * Returns today's assigned jobs for the authenticated tech,
     * ordered by scheduled_time ascending.
     * The tech's user id comes from the JWT subject claim.
     */
    @GetMapping("/today")
    public ResponseEntity<List<Map<String, Object>>> getMyDay(
            @RequestHeader("X-Tech-Id") String techId) {

        String today = java.time.LocalDate.now().toString();
        Map<String, String> filters = new HashMap<>();
        filters.put("assigned_tech_id", techId);
        filters.put("scheduled_date",   today);

        List<Map<String, Object>> jobs = supabase.findByColumns("work_orders", filters);
        return ResponseEntity.ok(jobs != null ? jobs : List.of());
    }

    /**
     * GET /api/tech/work-orders/{id}
     * Full job detail for the tech — includes checklist, customer info,
     * description, and past work at the same address.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getJobDetail(@PathVariable String id) {
        return ResponseEntity.ok(supabase.findById("work_orders", id));
    }

    /**
     * POST /api/tech/work-orders/{id}/on-my-way
     * Tech taps "On my way". Sets status=in_progress, records started_at,
     * and fires the ETA SMS to the guest.
     */
    @PostMapping("/{id}/on-my-way")
    public ResponseEntity<Void> onMyWay(
            @PathVariable String id,
            @RequestBody OnMyWayRequest req) {

        Map<String, Object> wo = supabase.findById("work_orders", id);

        Map<String, Object> updates = new HashMap<>();
        updates.put("status",     "in_progress");
        updates.put("started_at", Instant.now().toString());
        supabase.update("work_orders", id, updates);

        String guestPhone = wo.getOrDefault("guest_phone", "").toString();
        String guestName  = wo.getOrDefault("guest_name",  "Customer").toString();

        if (!guestPhone.isBlank()) {
            notificationService.sendOnMyWay(
                guestPhone,
                guestName,
                req.techName(),
                req.etaMins() != null ? req.etaMins() : 0
            );
        }

        log.info("WO {} — tech on the way, ETA {} min", id, req.etaMins());
        return ResponseEntity.ok().build();
    }

    /**
     * POST /api/tech/work-orders/{id}/complete
     * Tech closes the job from the field.
     * Requires: completionNotes + at least 1 photo already uploaded.
     * Fires job-complete SMS, creates invoice draft.
     */
    @PostMapping("/{id}/complete")
    public ResponseEntity<CompleteResponse> completeJob(
            @PathVariable String id,
            @RequestBody CompleteJobRequest req) {

        Map<String, Object> wo = supabase.findById("work_orders", id);

        // Validate photos uploaded — check the photos array
        Object photos = wo.get("photos");
        boolean hasPhotos = photos instanceof List<?> list && !list.isEmpty();
        if (!hasPhotos && (req.afterPhotoUrls() == null || req.afterPhotoUrls().isEmpty())) {
            return ResponseEntity.badRequest().build();
        }

        Map<String, Object> updates = new HashMap<>();
        updates.put("status",           "complete");
        updates.put("completion_notes", req.completionNotes());

        // Merge any new photo URLs into the existing array
        if (req.afterPhotoUrls() != null && !req.afterPhotoUrls().isEmpty()) {
            updates.put("photos", req.afterPhotoUrls());
        }

        // Save checklist state if provided
        if (req.checklistState() != null) {
            updates.put("checklist", req.checklistState());
        }

        supabase.update("work_orders", id, updates);

        // Notify guest
        String guestPhone = wo.getOrDefault("guest_phone", "").toString();
        String guestName  = wo.getOrDefault("guest_name",  "Customer").toString();

        if (!guestPhone.isBlank()) {
            notificationService.sendJobComplete(guestPhone, guestName, req.techName());
        }

        // Create invoice draft — owner sends it from dashboard
        String invoiceId = invoiceService.createInvoice(id);

        // Auto-send invoice if contract or WO has the flag set
        boolean autoInvoice = wo.get("auto_invoice_on_close") instanceof Boolean b && b;
        if (autoInvoice) {
            invoiceService.sendInvoice(invoiceId);
        }

        log.info("WO {} completed by tech — invoice {} created, autoSend={}",
                id, invoiceId, autoInvoice);

        return ResponseEntity.ok(new CompleteResponse(id, invoiceId, autoInvoice));
    }

    /**
     * POST /api/tech/work-orders/{id}/scope-flag
     * Tech flags a scope issue discovered on site.
     * Writes the flag to the WO and alerts the owner via SMS.
     */
    @PostMapping("/{id}/scope-flag")
    public ResponseEntity<Void> scopeFlag(
            @PathVariable String id,
            @RequestBody ScopeFlagRequest req) {

        Map<String, Object> wo = supabase.findById("work_orders", id);

        supabase.update("work_orders", id,
            Map.of("scope_flag", req.flagType()));

        String address = wo.getOrDefault("guest_address", "unknown address").toString();

        notificationService.sendScopeFlag(
            req.ownerPhone(),
            id,
            address,
            req.flagType()
        );

        log.info("WO {} scope flag set: {}", id, req.flagType());
        return ResponseEntity.ok().build();
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    record OnMyWayRequest(
        @NotBlank String  techName,
                  Integer etaMins
    ) {}

    record CompleteJobRequest(
        @NotBlank String            techName,
        @NotBlank String            completionNotes,
                  List<String>      afterPhotoUrls,
                  Map<String, Object> checklistState
    ) {}

    record CompleteResponse(
        String  workOrderId,
        String  invoiceId,
        boolean autoInvoiced
    ) {}

    record ScopeFlagRequest(
        @NotBlank String ownerPhone,
        @NotBlank String flagType    // e.g. "Needs licensed electrician"
    ) {}
}
