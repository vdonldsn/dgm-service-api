package com.dgm.api.crm;

import com.dgm.api.config.SupabaseClient;
import com.dgm.api.crm.CrmDTOs.AddNoteRequest;
import com.dgm.api.crm.CrmDTOs.CreateCustomerRequest;
import com.dgm.api.crm.CrmDTOs.CreateFollowUpRequest;
import com.dgm.api.crm.CrmDTOs.UpdateCustomerRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * CRM service — manages customer profiles, notes, and follow-ups.
 *
 * Customers enter the CRM two ways:
 *   1. Auto-created when a booking form is submitted (source=booking_form)
 *   2. Manually entered by owner or admin (source=manual_entry)
 *
 * Phone number is the deduplication key — if a booking comes in from
 * a phone number already in the customers table, we link to the existing
 * record rather than creating a duplicate.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrmService {

    private final SupabaseClient supabase;

    // ── Customer CRUD ─────────────────────────────────────────────────────────

    public Map<String, Object> createCustomer(CreateCustomerRequest req) {
        Map<String, Object> record = new HashMap<>();
        record.put("name",        req.name());
        record.put("phone",       req.phone());
        record.put("email",       req.email());
        record.put("address",     req.address());
        record.put("unit",        req.unit());
        record.put("city",        req.city() != null ? req.city() : "Nashville");
        record.put("state",       "TN");
        record.put("zip",         req.zip());
        record.put("lead_status", req.leadStatus() != null ? req.leadStatus() : "new_lead");
        record.put("source",      req.source() != null ? req.source() : "manual_entry");
        record.put("is_pm",       req.isPm() != null && req.isPm());
        record.put("notes",       req.notes());
        record.put("active",      true);

        Map<String, Object> created = supabase.insert("customers", record);
        log.info("Customer created: {} — {}", created.get("id"), req.name());
        return created;
    }

    /**
     * Called automatically when a booking form is submitted.
     * Deduplicates by phone number — returns existing customer if found,
     * creates new one if not. Links the work order to the customer profile.
     */
    public String findOrCreateFromBooking(String name, String phone,
                                           String email, String address,
                                           String unit, String city,
                                           String zip, String woId) {
        // Try to find existing customer by phone
        if (phone != null && !phone.isBlank()) {
            List<Map<String, Object>> existing =
                supabase.findByColumn("customers", "phone", phone);
            if (existing != null && !existing.isEmpty()) {
                String customerId = existing.get(0).get("id").toString();
                log.info("Booking matched existing customer {} by phone", customerId);
                linkWorkOrder(woId, customerId);
                return customerId;
            }
        }

        // Create new customer from booking data
        Map<String, Object> record = new HashMap<>();
        record.put("name",        name);
        record.put("phone",       phone);
        record.put("email",       email);
        record.put("address",     address);
        record.put("unit",        unit);
        record.put("city",        city != null ? city : "Nashville");
        record.put("state",       "TN");
        record.put("zip",         zip);
        record.put("lead_status", "booked");
        record.put("source",      "booking_form");
        record.put("active",      true);

        Map<String, Object> created = supabase.insert("customers", record);
        String customerId = created.get("id").toString();
        log.info("New customer {} created from booking form", customerId);
        linkWorkOrder(woId, customerId);
        return customerId;
    }

    public List<Map<String, Object>> getAllCustomers(String leadStatus,
                                                      String source,
                                                      boolean activeOnly) {
        Map<String, String> filters = new HashMap<>();
        if (leadStatus != null) filters.put("lead_status", leadStatus);
        if (source     != null) filters.put("source",      source);
        if (activeOnly)         filters.put("active",      "true");

        if (filters.isEmpty()) {
            return supabase.findByColumn("customers", "active", "true");
        }
        return supabase.findByColumns("customers", filters);
    }

    public Map<String, Object> getCustomer(String customerId) {
        return supabase.findById("customers", customerId);
    }

    public void updateCustomer(String customerId, UpdateCustomerRequest req) {
        Map<String, Object> updates = new HashMap<>();
        if (req.name()        != null) updates.put("name",        req.name());
        if (req.phone()       != null) updates.put("phone",       req.phone());
        if (req.email()       != null) updates.put("email",       req.email());
        if (req.address()     != null) updates.put("address",     req.address());
        if (req.unit()        != null) updates.put("unit",        req.unit());
        if (req.city()        != null) updates.put("city",        req.city());
        if (req.zip()         != null) updates.put("zip",         req.zip());
        if (req.leadStatus()  != null) updates.put("lead_status", req.leadStatus());
        if (req.notes()       != null) updates.put("notes",       req.notes());
        if (req.tags()        != null) updates.put("tags",        req.tags());
        if (req.isPm()        != null) updates.put("is_pm",       req.isPm());

        if (!updates.isEmpty()) {
            supabase.update("customers", customerId, updates);
            log.info("Customer {} updated", customerId);
        }
    }

    public void updateLeadStatus(String customerId, String newStatus) {
        supabase.update("customers", customerId, Map.of("lead_status", newStatus));
        log.info("Customer {} lead status → {}", customerId, newStatus);
    }

    public void deactivateCustomer(String customerId) {
        supabase.update("customers", customerId, Map.of("active", false));
        log.info("Customer {} deactivated", customerId);
    }

    // ── Notes ─────────────────────────────────────────────────────────────────

    public Map<String, Object> addNote(String customerId, AddNoteRequest req) {
        Map<String, Object> record = new HashMap<>();
        record.put("customer_id",  customerId);
        record.put("author_id",    req.authorId());
        record.put("author_name",  req.authorName());
        record.put("body",         req.body());

        Map<String, Object> created = supabase.insert("customer_notes", record);
        log.info("Note added to customer {} by {}", customerId, req.authorName());
        return created;
    }

    public List<Map<String, Object>> getNotes(String customerId) {
        return supabase.findByColumn("customer_notes", "customer_id", customerId);
    }

    // ── Follow-ups ────────────────────────────────────────────────────────────

    public Map<String, Object> createFollowUp(String customerId,
                                               CreateFollowUpRequest req) {
        Map<String, Object> record = new HashMap<>();
        record.put("customer_id", customerId);
        record.put("created_by",  req.createdBy());
        record.put("title",       req.title());
        record.put("body",        req.body());
        record.put("due_date",    req.dueDate());
        record.put("status",      "pending");

        Map<String, Object> created = supabase.insert("follow_ups", record);
        log.info("Follow-up created for customer {} due {}", customerId, req.dueDate());
        return created;
    }

    public List<Map<String, Object>> getFollowUps(String customerId) {
        return supabase.findByColumn("follow_ups", "customer_id", customerId);
    }

    public List<Map<String, Object>> getPendingFollowUps() {
        return supabase.findByColumn("follow_ups", "status", "pending");
    }

    public void completeFollowUp(String followUpId) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("status",       "done");
        updates.put("completed_at", Instant.now().toString());
        supabase.update("follow_ups", followUpId, updates);
        log.info("Follow-up {} marked complete", followUpId);
    }

    public void snoozeFollowUp(String followUpId, String snoozeUntilDate) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("status",         "snoozed");
        updates.put("snoozed_until",  snoozeUntilDate);
        supabase.update("follow_ups", followUpId, updates);
        log.info("Follow-up {} snoozed until {}", followUpId, snoozeUntilDate);
    }

    // ── Billing summary update ─────────────────────────────────────────────────

    /**
     * Called when an invoice is created or paid.
     * Keeps the denormalized totals on the customer record current
     * so the CRM list view doesn't need to JOIN invoices every time.
     */
    public void updateBillingSummary(String customerId,
                                      double totalInvoiced,
                                      double totalPaid) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("total_invoiced", totalInvoiced);
        updates.put("total_paid",     totalPaid);
        supabase.update("customers", customerId, updates);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void linkWorkOrder(String woId, String customerId) {
        if (woId == null || customerId == null) return;
        try {
            supabase.update("work_orders", woId,
                Map.of("customer_profile_id", customerId));
        } catch (Exception e) {
            log.warn("Could not link WO {} to customer {}: {}", woId, customerId, e.getMessage());
        }
    }
}
