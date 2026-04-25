package com.dgm.api.crm;

import com.dgm.api.crm.CrmDTOs.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * CRM REST API — owner and admin only.
 * All endpoints secured at OWNER role in SecurityConfig.
 *
 * Endpoints:
 *   Customers   — CRUD, lead status pipeline, deactivate
 *   Notes       — append-only log per customer
 *   Follow-ups  — reminders with due dates, complete, snooze
 */
@RestController
@RequestMapping("/api/crm")
@RequiredArgsConstructor
public class CrmController {

    private final CrmService crmService;

    // ── Customers ─────────────────────────────────────────────────────────────

    /**
     * POST /api/crm/customers
     * Create a customer manually (owner or admin entry).
     */
    @PostMapping("/customers")
    public ResponseEntity<Map<String, Object>> createCustomer(
            @Valid @RequestBody CreateCustomerRequest req) {
        return ResponseEntity.ok(crmService.createCustomer(req));
    }

    /**
     * GET /api/crm/customers
     * List all customers. Optional filters: leadStatus, source, activeOnly.
     */
    @GetMapping("/customers")
    public ResponseEntity<List<Map<String, Object>>> listCustomers(
            @RequestParam(required = false) String  leadStatus,
            @RequestParam(required = false) String  source,
            @RequestParam(defaultValue = "true") boolean activeOnly) {
        return ResponseEntity.ok(
            crmService.getAllCustomers(leadStatus, source, activeOnly));
    }

    /**
     * GET /api/crm/customers/{id}
     * Get a single customer with full detail.
     */
    @GetMapping("/customers/{id}")
    public ResponseEntity<Map<String, Object>> getCustomer(
            @PathVariable String id) {
        return ResponseEntity.ok(crmService.getCustomer(id));
    }

    /**
     * PATCH /api/crm/customers/{id}
     * Update customer fields. Only supplied fields are updated.
     */
    @PatchMapping("/customers/{id}")
    public ResponseEntity<Void> updateCustomer(
            @PathVariable String id,
            @RequestBody UpdateCustomerRequest req) {
        crmService.updateCustomer(id, req);
        return ResponseEntity.ok().build();
    }

    /**
     * PATCH /api/crm/customers/{id}/status
     * Move customer through the lead pipeline.
     * Body: { "leadStatus": "contacted" }
     */
    @PatchMapping("/customers/{id}/status")
    public ResponseEntity<Void> updateLeadStatus(
            @PathVariable String id,
            @Valid @RequestBody UpdateLeadStatusRequest req) {
        crmService.updateLeadStatus(id, req.leadStatus());
        return ResponseEntity.ok().build();
    }

    /**
     * DELETE /api/crm/customers/{id}
     * Soft delete — sets active=false. Record is preserved.
     */
    @DeleteMapping("/customers/{id}")
    public ResponseEntity<Void> deactivateCustomer(@PathVariable String id) {
        crmService.deactivateCustomer(id);
        return ResponseEntity.noContent().build();
    }

    // ── Notes ─────────────────────────────────────────────────────────────────

    /**
     * POST /api/crm/customers/{id}/notes
     * Add a note to a customer. Append-only — no editing or deleting.
     */
    @PostMapping("/customers/{id}/notes")
    public ResponseEntity<Map<String, Object>> addNote(
            @PathVariable String id,
            @Valid @RequestBody AddNoteRequest req) {
        return ResponseEntity.ok(crmService.addNote(id, req));
    }

    /**
     * GET /api/crm/customers/{id}/notes
     * Get all notes for a customer, newest first.
     */
    @GetMapping("/customers/{id}/notes")
    public ResponseEntity<List<Map<String, Object>>> getNotes(
            @PathVariable String id) {
        return ResponseEntity.ok(crmService.getNotes(id));
    }

    // ── Follow-ups ────────────────────────────────────────────────────────────

    /**
     * POST /api/crm/customers/{id}/follow-ups
     * Create a follow-up reminder for a customer.
     */
    @PostMapping("/customers/{id}/follow-ups")
    public ResponseEntity<Map<String, Object>> createFollowUp(
            @PathVariable String id,
            @Valid @RequestBody CreateFollowUpRequest req) {
        return ResponseEntity.ok(crmService.createFollowUp(id, req));
    }

    /**
     * GET /api/crm/customers/{id}/follow-ups
     * Get all follow-ups for a specific customer.
     */
    @GetMapping("/customers/{id}/follow-ups")
    public ResponseEntity<List<Map<String, Object>>> getFollowUps(
            @PathVariable String id) {
        return ResponseEntity.ok(crmService.getFollowUps(id));
    }

    /**
     * GET /api/crm/follow-ups/pending
     * Get all pending follow-ups across all customers.
     * Used for the owner dashboard "Today's follow-ups" panel.
     */
    @GetMapping("/follow-ups/pending")
    public ResponseEntity<List<Map<String, Object>>> getPendingFollowUps() {
        return ResponseEntity.ok(crmService.getPendingFollowUps());
    }

    /**
     * PATCH /api/crm/follow-ups/{id}/complete
     * Mark a follow-up as done.
     */
    @PatchMapping("/follow-ups/{id}/complete")
    public ResponseEntity<Void> completeFollowUp(@PathVariable String id) {
        crmService.completeFollowUp(id);
        return ResponseEntity.ok().build();
    }

    /**
     * PATCH /api/crm/follow-ups/{id}/snooze
     * Snooze a follow-up to a future date.
     */
    @PatchMapping("/follow-ups/{id}/snooze")
    public ResponseEntity<Void> snoozeFollowUp(
            @PathVariable String id,
            @Valid @RequestBody SnoozeFollowUpRequest req) {
        crmService.snoozeFollowUp(id, req.snoozeUntilDate());
        return ResponseEntity.ok().build();
    }
}
