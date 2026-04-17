package com.dgm.api.contract;

import com.dgm.api.config.SupabaseClient;
import com.dgm.api.contract.ContractDTOs.CreateContractRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Manages the lifecycle of maintenance contracts.
 * Creating, pausing, resuming, and computing the next due date
 * for each cadence type all live here.
 *
 * The scheduler calls computeNextDueDate() after each WO generation
 * to advance the contract forward automatically.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractService {

    private final SupabaseClient supabase;

    // ── CRUD ─────────────────────────────────────────────────────────────────

    public Map<String, Object> createContract(CreateContractRequest req) {
        LocalDate nextDue = computeInitialDueDate(req);

        Map<String, Object> record = new HashMap<>();
        record.put("pm_account_id",           req.pmAccountId());
        record.put("property_id",             req.propertyId());
        record.put("unit_id",                 req.unitId());
        record.put("task_template_id",        req.taskTemplateId());
        record.put("task_name",               req.taskName());
        record.put("service_category",        req.serviceCategory());
        record.put("cadence_type",            req.cadenceType());
        record.put("cadence_interval_value",  req.cadenceIntervalValue());
        record.put("day_of_week",             req.dayOfWeek());
        record.put("day_of_month",            req.dayOfMonth());
        record.put("seasonal_month",          req.seasonalMonth());
        record.put("start_date",              req.startDate());
        record.put("end_date",               req.endDate());
        record.put("next_due_date",           nextDue.toString());
        record.put("pricing_type",            req.pricingType());
        record.put("flat_rate",               req.flatRate());
        record.put("hourly_rate",             req.hourlyRate());
        record.put("estimated_duration_mins", req.estimatedDurationMins());
        record.put("auto_invoice_on_close",   req.autoInvoiceOnClose());
        record.put("requires_photo_on_close", req.requiresPhotoOnClose());
        record.put("active",                  true);
        record.put("notes",                   req.notes());

        Map<String, Object> created = supabase.insert("maintenance_contracts", record);
        log.info("Contract created: {} for property {}", created.get("id"), req.propertyId());
        return created;
    }

    public List<Map<String, Object>> getContractsByPm(String pmAccountId) {
        return supabase.findByColumn("maintenance_contracts", "pm_account_id", pmAccountId);
    }

    public List<Map<String, Object>> getContractsByProperty(String propertyId) {
        return supabase.findByColumn("maintenance_contracts", "property_id", propertyId);
    }

    public List<Map<String, Object>> getDueContracts(LocalDate asOf) {
        // Returns all active contracts where next_due_date <= asOf
        // Uses PostgREST lte filter
        String url = "next_due_date=lte." + asOf + "&active=eq.true";
        return supabase.findByRawFilter("maintenance_contracts", url);
    }

    public void pauseContract(String contractId) {
        supabase.update("maintenance_contracts", contractId, Map.of("active", false));
        log.info("Contract {} paused", contractId);
    }

    public void resumeContract(String contractId) {
        supabase.update("maintenance_contracts", contractId, Map.of("active", true));
        log.info("Contract {} resumed", contractId);
    }

    public void deleteContract(String contractId) {
        supabase.deleteById("maintenance_contracts", contractId);
        log.info("Contract {} deleted", contractId);
    }

    // ── Next due date computation ─────────────────────────────────────────────

    /**
     * Advances the contract's next_due_date forward by one cadence period
     * and updates last_generated_date. Called by the scheduler after each
     * work order is generated.
     *
     * Uses the LAST_GENERATED_DATE (not today) as the base so the schedule
     * never drifts — a job completed late still generates the next one
     * on the correct future date.
     */
    public void advanceNextDueDate(String contractId) {
        Map<String, Object> contract = supabase.findById("maintenance_contracts", contractId);
        LocalDate currentDue = LocalDate.parse(contract.get("next_due_date").toString());
        LocalDate nextDue    = computeNextDueDate(contract, currentDue);

        Map<String, Object> updates = new HashMap<>();
        updates.put("next_due_date",      nextDue.toString());
        updates.put("last_generated_date", currentDue.toString());
        supabase.update("maintenance_contracts", contractId, updates);

        log.info("Contract {} advanced: next due {}", contractId, nextDue);
    }

    // ── Private date logic ────────────────────────────────────────────────────

    private LocalDate computeInitialDueDate(CreateContractRequest req) {
        LocalDate start = req.startDate() != null
                ? LocalDate.parse(req.startDate())
                : LocalDate.now();
        return start;
    }

    /**
     * Core scheduling logic. Given a contract and its current due date,
     * returns the next due date based on cadence type.
     *
     * TRIGGER cadences return null — they are not auto-advanced.
     */
    LocalDate computeNextDueDate(Map<String, Object> contract, LocalDate fromDate) {
        String cadence = contract.get("cadence_type").toString();
        int interval   = contract.get("cadence_interval_value") != null
                ? ((Number) contract.get("cadence_interval_value")).intValue()
                : 1;

        return switch (cadence) {
            case "DAILY"     -> fromDate.plusDays(interval);
            case "WEEKLY"    -> fromDate.plusWeeks(interval);
            case "MONTHLY"   -> fromDate.plusMonths(interval);
            case "QUARTERLY" -> fromDate.plusMonths(3);
            case "ANNUAL"    -> fromDate.plusYears(1);
            case "SEASONAL"  -> computeNextSeasonal(fromDate);
            case "TRIGGER"   -> null; // trigger contracts never auto-advance
            default          -> {
                log.warn("Unknown cadence type: {}", cadence);
                yield fromDate.plusMonths(1);
            }
        };
    }

    /**
     * Seasonal cadence: every 3 months aligned to the start of each season.
     * Spring=Apr, Summer=Jul, Fall=Oct, Winter=Jan.
     */
    private LocalDate computeNextSeasonal(LocalDate fromDate) {
        // Seasonal months: 1 (Jan), 4 (Apr), 7 (Jul), 10 (Oct)
        int[] seasonalMonths = {1, 4, 7, 10};
        int currentMonth = fromDate.getMonthValue();

        for (int month : seasonalMonths) {
            if (month > currentMonth) {
                return LocalDate.of(fromDate.getYear(), month, 1);
            }
        }
        // Wrap to next year January
        return LocalDate.of(fromDate.getYear() + 1, 1, 1);
    }
}
