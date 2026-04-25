package com.dgm.api.crm;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * All CRM request and response DTOs in one file.
 */
public class CrmDTOs {

    // ── Customer requests ─────────────────────────────────────────────────────

    public record CreateCustomerRequest(
        @NotBlank String  name,
                  String  phone,
                  String  email,
                  String  address,
                  String  unit,
                  String  city,
                  String  zip,
                  String  leadStatus,   // defaults to new_lead
                  String  source,       // defaults to manual_entry
                  Boolean isPm,
                  String  notes
    ) {}

    public record UpdateCustomerRequest(
        String       name,
        String       phone,
        String       email,
        String       address,
        String       unit,
        String       city,
        String       zip,
        String       leadStatus,
        String       notes,
        List<String> tags,
        Boolean      isPm
    ) {}

    public record UpdateLeadStatusRequest(
        @NotBlank String leadStatus
    ) {}

    // ── Note requests ─────────────────────────────────────────────────────────

    public record AddNoteRequest(
        @NotBlank String authorId,
        @NotBlank String authorName,
        @NotBlank String body
    ) {}

    // ── Follow-up requests ────────────────────────────────────────────────────

    public record CreateFollowUpRequest(
        @NotBlank String createdBy,
        @NotBlank String title,
                  String body,
        @NotBlank String dueDate    // ISO date string e.g. "2025-08-01"
    ) {}

    public record SnoozeFollowUpRequest(
        @NotBlank String snoozeUntilDate  // ISO date string
    ) {}
}
