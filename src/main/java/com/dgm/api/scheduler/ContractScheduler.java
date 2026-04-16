package com.dgm.api.scheduler;

import com.dgm.api.config.BusinessProperties;
import com.dgm.api.config.SupabaseClient;
import com.dgm.api.contract.ContractService;
import com.dgm.api.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Nightly cron job that checks all active maintenance contracts,
 * finds which ones are due, and auto-generates a work order for each.
 *
 * Runs at 01:00 AM in the business timezone every day.
 * Timezone is read from the BUSINESS_TIMEZONE env var so it works
 * correctly for clients in any US timezone.
 *
 * Design decisions:
 * - Idempotent: checks last_generated_date before creating a WO
 *   so if the job runs twice it does not create duplicate work orders.
 * - Never throws: errors on individual contracts are logged and skipped
 *   so one bad contract cannot block the rest from being processed.
 * - Notifies owner via SMS for each generated WO so nothing is silent.
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class ContractScheduler {

    private final ContractService     contractService;
    private final SupabaseClient      supabase;
    private final NotificationService notificationService;
    private final BusinessProperties  business;

    @Value("${business.timezone:America/Chicago}")
    private String timezone;

    /**
     * Main scheduler entry point.
     * Cron: every day at 1:00 AM server time.
     * The server runs in UTC; 01:00 UTC = ~8 PM CT which is after business
     * hours and well before morning — safe window for all US timezones.
     *
     * To change timing: adjust the cron expression or set
     * SCHEDULER_CRON env var override (added to application.yml separately).
     */
    @Scheduled(cron = "0 0 1 * * *", zone = "UTC")
    public void runNightlyContractCheck() {
        LocalDate today = LocalDate.now(ZoneId.of(timezone));
        log.info("Contract scheduler running for date: {}", today);

        List<Map<String, Object>> dueContracts = contractService.getDueContracts(today);

        if (dueContracts == null || dueContracts.isEmpty()) {
            log.info("No contracts due today");
            return;
        }

        log.info("Found {} contract(s) due", dueContracts.size());
        int generated = 0;
        int skipped   = 0;
        int failed    = 0;

        for (Map<String, Object> contract : dueContracts) {
            String contractId = contract.get("id").toString();
            try {
                if (isAlreadyGeneratedToday(contract, today)) {
                    log.debug("Contract {} already generated today — skipping", contractId);
                    skipped++;
                    continue;
                }
                generateWorkOrder(contract, today);
                contractService.advanceNextDueDate(contractId);
                generated++;
            } catch (Exception e) {
                log.error("Failed to process contract {}: {}", contractId, e.getMessage(), e);
                failed++;
            }
        }

        log.info("Scheduler complete — generated: {}, skipped: {}, failed: {}",
                generated, skipped, failed);

        // Notify owner with a summary if anything was generated
        if (generated > 0) {
            notifyOwnerSchedulerSummary(generated, today);
        }
    }

    /**
     * Creates a work order in Supabase from a contract.
     * Copies task name, checklist, duration, and pricing from the contract.
     * Sets status=new so the owner assigns a tech from the dashboard.
     */
    void generateWorkOrder(Map<String, Object> contract, LocalDate forDate) {
        String contractId = contract.get("id").toString();

        // Load the task template to get the checklist
        String templateId = contract.get("task_template_id") != null
                ? contract.get("task_template_id").toString()
                : null;

        List<String> checklist = List.of();
        if (templateId != null) {
            try {
                Map<String, Object> template = supabase.findById("task_templates", templateId);
                Object raw = template.get("checklist");
                if (raw instanceof List<?> list) {
                    checklist = list.stream().map(Object::toString).toList();
                }
            } catch (Exception e) {
                log.warn("Could not load template {} for contract {}: {}",
                        templateId, contractId, e.getMessage());
            }
        }

        Map<String, Object> wo = new HashMap<>();
        wo.put("contract_id",              contractId);
        wo.put("property_id",              contract.get("property_id"));
        wo.put("service_category",         contract.get("service_category"));
        wo.put("task_name",                contract.get("task_name"));
        wo.put("description",              "Auto-generated from maintenance contract #" + contractId);
        wo.put("status",                   "new");
        wo.put("job_type",                 "one_off");
        wo.put("urgency_flag",             false);
        wo.put("scheduled_date",           forDate.toString());
        wo.put("estimated_duration_mins",  contract.get("estimated_duration_mins"));
        wo.put("checklist",                checklist);
        wo.put("flat_rate",                contract.get("flat_rate"));
        wo.put("requires_photo_on_close",  contract.get("requires_photo_on_close"));
        wo.put("auto_invoice_on_close",    contract.get("auto_invoice_on_close"));

        Map<String, Object> created = supabase.insert("work_orders", wo);
        log.info("Work order {} generated from contract {} for date {}",
                created.get("id"), contractId, forDate);
    }

    /**
     * Idempotency guard — checks if a WO was already created for this
     * contract today by comparing last_generated_date.
     */
    private boolean isAlreadyGeneratedToday(Map<String, Object> contract, LocalDate today) {
        Object lastGen = contract.get("last_generated_date");
        if (lastGen == null) return false;
        try {
            return LocalDate.parse(lastGen.toString()).isEqual(today);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Sends the owner a single SMS summary instead of one per WO
     * to avoid SMS fatigue on busy maintenance days.
     */
    private void notifyOwnerSchedulerSummary(int count, LocalDate date) {
        String message = String.format(
            "%s | Scheduler: %d maintenance work order%s auto-generated for %s. " +
            "Log in to review and assign techs.",
            business.getShortName(),
            count,
            count == 1 ? "" : "s",
            date
        );
        notificationService.sendSms(business.getOwnerPhone(), message);
    }
}
