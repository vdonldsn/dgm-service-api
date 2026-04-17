package com.dgm.api.pm;

import com.dgm.api.config.SupabaseClient;
import com.dgm.api.pm.PmDTOs.CreatePmRequest;
import com.dgm.api.pm.PmDTOs.UnitVacancyRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Business logic for property manager accounts and their portfolio views.
 * The PM dashboard aggregates data across multiple properties and work orders
 * into summary views that a PM needs for their day-to-day management.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmService {

    private final SupabaseClient supabase;

    // ── PM Account CRUD ───────────────────────────────────────────────────────

    public Map<String, Object> createPmAccount(CreatePmRequest req) {
        Map<String, Object> record = new HashMap<>();
        record.put("company_name",          req.companyName());
        record.put("contact_name",          req.contactName());
        record.put("email",                 req.email());
        record.put("phone",                 req.phone());
        record.put("billing_address",       req.billingAddress());
        record.put("auto_invoice_default",  req.autoInvoiceDefault() != null
                                            && req.autoInvoiceDefault());
        record.put("notes",                 req.notes());
        record.put("active",                true);

        Map<String, Object> created = supabase.insert("pm_accounts", record);
        log.info("PM account created: {} — {}", created.get("id"), req.companyName());
        return created;
    }

    public List<Map<String, Object>> getAllPmAccounts() {
        return supabase.findByColumn("pm_accounts", "active", "true");
    }

    public Map<String, Object> getPmAccount(String pmId) {
        return supabase.findById("pm_accounts", pmId);
    }

    public void updatePmAccount(String pmId, Map<String, Object> updates) {
        supabase.update("pm_accounts", pmId, updates);
    }

    public void deactivatePmAccount(String pmId) {
        supabase.update("pm_accounts", pmId, Map.of("active", false));
        log.info("PM account {} deactivated", pmId);
    }

    // ── Portfolio views ───────────────────────────────────────────────────────

    /**
     * Returns a summary dashboard for a PM:
     * - All their properties
     * - Open work order count per property
     * - Active contract count per property
     * - Outstanding invoice total
     *
     * This is assembled from multiple Supabase queries and merged here
     * rather than relying on a complex join — keeps the Supabase RLS
     * policies simple and queries fast.
     */
    public PortfolioDashboard getPortfolioDashboard(String pmId) {
        // Properties for this PM
        List<Map<String, Object>> properties =
                supabase.findByColumn("properties", "pm_account_id", pmId);

        // Open work orders across all their properties
        List<Map<String, Object>> openWos =
                supabase.findByColumns("work_orders",
                        Map.of("pm_account_id", pmId, "status", "neq.complete"));

        // Active contracts
        List<Map<String, Object>> contracts =
                supabase.findByColumns("maintenance_contracts",
                        Map.of("pm_account_id", pmId, "active", "true"));

        // Outstanding invoices
        List<Map<String, Object>> unpaidInvoices =
                supabase.findByColumns("invoices",
                        Map.of("pm_account_id", pmId, "status", "sent"));

        double outstandingTotal = unpaidInvoices.stream()
                .mapToDouble(inv -> {
                    Object amt = inv.get("amount");
                    return amt != null ? ((Number) amt).doubleValue() : 0.0;
                })
                .sum();

        return new PortfolioDashboard(
                pmId,
                properties != null ? properties.size() : 0,
                openWos    != null ? openWos.size()    : 0,
                contracts  != null ? contracts.size()  : 0,
                outstandingTotal,
                properties,
                openWos
        );
    }

    /**
     * Returns all work orders for a specific unit across all time.
     * This gives PMs the full maintenance history for any unit —
     * useful at tenant turnover and during property inspections.
     */
    public List<Map<String, Object>> getUnitHistory(String propertyId, String unitId) {
        Map<String, String> filters = new HashMap<>();
        filters.put("property_id", propertyId);
        if (unitId != null && !unitId.isBlank()) {
            filters.put("unit_id", unitId);
        }
        return supabase.findByColumns("work_orders", filters);
    }

    /**
     * Unit vacancy trigger — called by the PM when a tenant moves out.
     * Creates a make-ready work order from the property's unit-turn
     * task template if one exists, otherwise creates a bare WO.
     */
    public Map<String, Object> triggerUnitVacancy(UnitVacancyRequest req) {
        // Look for a unit-turn contract for this property
        List<Map<String, Object>> contracts =
                supabase.findByColumns("maintenance_contracts",
                        Map.of("property_id", req.propertyId(),
                               "cadence_type", "TRIGGER",
                               "active", "true"));

        Map<String, Object> wo = new HashMap<>();
        wo.put("property_id",     req.propertyId());
        wo.put("pm_account_id",   req.pmAccountId());
        wo.put("service_category","GENERAL_REPAIRS");
        wo.put("task_name",       "Unit make-ready / turn");
        wo.put("description",     "Unit vacancy triggered make-ready. " +
                                  "Unit: " + req.unitId() +
                                  ". Target move-in: " + req.targetMoveInDate());
        wo.put("status",          "new");
        wo.put("job_type",        "trigger");
        wo.put("urgency_flag",    false);
        wo.put("scheduled_date",  req.targetMoveInDate());

        // If there is a unit-turn contract, copy its checklist
        if (contracts != null && !contracts.isEmpty()) {
            Map<String, Object> contract = contracts.get(0);
            wo.put("contract_id",  contract.get("id"));
            wo.put("checklist",    contract.get("checklist"));
            wo.put("flat_rate",    contract.get("flat_rate"));
        }

        Map<String, Object> created = supabase.insert("work_orders", wo);
        log.info("Unit vacancy WO {} created for property {} unit {}",
                created.get("id"), req.propertyId(), req.unitId());
        return created;
    }

    // ── Response record ───────────────────────────────────────────────────────

    public record PortfolioDashboard(
            String                     pmId,
            int                        propertyCount,
            int                        openWorkOrderCount,
            int                        activeContractCount,
            double                     outstandingInvoiceTotal,
            List<Map<String, Object>>  properties,
            List<Map<String, Object>>  openWorkOrders
    ) {}
}
