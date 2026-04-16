package com.dgm.api.contract;

import com.dgm.api.contract.ContractDTOs.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for maintenance contract management.
 * All endpoints require OWNER role — enforced in SecurityConfig.
 */
@RestController
@RequestMapping("/api/contracts")
@RequiredArgsConstructor
public class ContractController {

    private final ContractService contractService;

    /**
     * POST /api/contracts
     * Create a new maintenance contract for a PM and property.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @Valid @RequestBody CreateContractRequest req) {
        Map<String, Object> created = contractService.createContract(req);
        return ResponseEntity.ok(created);
    }

    /**
     * GET /api/contracts/pm/{pmAccountId}
     * List all contracts for a property manager.
     */
    @GetMapping("/pm/{pmAccountId}")
    public ResponseEntity<List<Map<String, Object>>> getByPm(
            @PathVariable String pmAccountId) {
        return ResponseEntity.ok(contractService.getContractsByPm(pmAccountId));
    }

    /**
     * GET /api/contracts/property/{propertyId}
     * List all contracts for a specific property.
     */
    @GetMapping("/property/{propertyId}")
    public ResponseEntity<List<Map<String, Object>>> getByProperty(
            @PathVariable String propertyId) {
        return ResponseEntity.ok(contractService.getContractsByProperty(propertyId));
    }

    /**
     * POST /api/contracts/{id}/pause
     * Pauses a contract — stops auto-scheduling until resumed.
     */
    @PostMapping("/{id}/pause")
    public ResponseEntity<Void> pause(@PathVariable String id) {
        contractService.pauseContract(id);
        return ResponseEntity.ok().build();
    }

    /**
     * POST /api/contracts/{id}/resume
     * Resumes a paused contract.
     */
    @PostMapping("/{id}/resume")
    public ResponseEntity<Void> resume(@PathVariable String id) {
        contractService.resumeContract(id);
        return ResponseEntity.ok().build();
    }

    /**
     * DELETE /api/contracts/{id}
     * Permanently removes a contract. Does not affect existing work orders.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        contractService.deleteContract(id);
        return ResponseEntity.noContent().build();
    }
}
