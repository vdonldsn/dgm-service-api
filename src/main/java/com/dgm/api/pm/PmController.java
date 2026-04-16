package com.dgm.api.pm;

import com.dgm.api.pm.PmDTOs.*;
import com.dgm.api.pm.PmService.PortfolioDashboard;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for property manager accounts and their portfolio views.
 * All endpoints require OWNER role — PMs do not get direct API access
 * in v2; the owner manages everything on their behalf.
 */
@RestController
@RequestMapping("/api/pm")
@RequiredArgsConstructor
public class PmController {

    private final PmService pmService;

    // ── PM Accounts ───────────────────────────────────────────────────────────

    @PostMapping("/accounts")
    public ResponseEntity<Map<String, Object>> createAccount(
            @Valid @RequestBody CreatePmRequest req) {
        return ResponseEntity.ok(pmService.createPmAccount(req));
    }

    @GetMapping("/accounts")
    public ResponseEntity<List<Map<String, Object>>> getAllAccounts() {
        return ResponseEntity.ok(pmService.getAllPmAccounts());
    }

    @GetMapping("/accounts/{pmId}")
    public ResponseEntity<Map<String, Object>> getAccount(@PathVariable String pmId) {
        return ResponseEntity.ok(pmService.getPmAccount(pmId));
    }

    @DeleteMapping("/accounts/{pmId}")
    public ResponseEntity<Void> deactivate(@PathVariable String pmId) {
        pmService.deactivatePmAccount(pmId);
        return ResponseEntity.noContent().build();
    }

    // ── Portfolio views ───────────────────────────────────────────────────────

    /**
     * GET /api/pm/accounts/{pmId}/dashboard
     * Returns the full portfolio summary for the owner to review on behalf
     * of a PM — property count, open WOs, active contracts, outstanding invoices.
     */
    @GetMapping("/accounts/{pmId}/dashboard")
    public ResponseEntity<PortfolioDashboard> getDashboard(@PathVariable String pmId) {
        return ResponseEntity.ok(pmService.getPortfolioDashboard(pmId));
    }

    /**
     * GET /api/pm/history?propertyId=X&unitId=Y
     * Full maintenance history for a property or specific unit.
     */
    @GetMapping("/history")
    public ResponseEntity<List<Map<String, Object>>> getUnitHistory(
            @RequestParam String propertyId,
            @RequestParam(required = false) String unitId) {
        return ResponseEntity.ok(pmService.getUnitHistory(propertyId, unitId));
    }

    // ── Trigger endpoints ─────────────────────────────────────────────────────

    /**
     * POST /api/pm/unit-vacancy
     * PM marks a unit as vacant. Creates a make-ready work order.
     * Checks for an existing TRIGGER-type contract for this property
     * and copies its checklist if found.
     */
    @PostMapping("/unit-vacancy")
    public ResponseEntity<Map<String, Object>> triggerUnitVacancy(
            @Valid @RequestBody UnitVacancyRequest req) {
        return ResponseEntity.ok(pmService.triggerUnitVacancy(req));
    }
}
