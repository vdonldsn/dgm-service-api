package com.dgm.api.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request and response DTOs for the contract API.
 * All records — immutable, no boilerplate needed.
 */
public class ContractDTOs {

    public record CreateContractRequest(
        @NotBlank String  pmAccountId,
        @NotBlank String  propertyId,
                  String  unitId,               // nullable — null means whole property
        @NotBlank String  taskTemplateId,
        @NotBlank String  taskName,
        @NotBlank String  serviceCategory,

        // Cadence
        @NotBlank String  cadenceType,           // DAILY|WEEKLY|MONTHLY|QUARTERLY|SEASONAL|ANNUAL|TRIGGER
                  Integer cadenceIntervalValue,  // e.g. 2 for "every 2 weeks"
                  Integer dayOfWeek,             // 0-6 for WEEKLY
                  Integer dayOfMonth,            // 1-31 for MONTHLY/ANNUAL
                  String  seasonalMonth,         // JAN|APR|JUL|OCT
                  String  startDate,             // ISO date string; defaults to today
                  String  endDate,               // ISO date; null = indefinite

        // Pricing
        @NotBlank String  pricingType,           // FLAT_RATE|TIME_AND_MATERIAL
                  Double  flatRate,
                  Double  hourlyRate,
                  Integer estimatedDurationMins,

        // Behavior
                  Boolean autoInvoiceOnClose,
                  Boolean requiresPhotoOnClose,
                  String  notes
    ) {}

    public record ContractResponse(
        String  id,
        String  taskName,
        String  serviceCategory,
        String  cadenceType,
        String  nextDueDate,
        String  pricingType,
        Double  flatRate,
        Boolean active
    ) {}

    public record TriggerWorkOrderRequest(
        @NotBlank String contractId,
        @NotBlank String triggerReason  // e.g. "unit_vacancy", "inspection_request"
    ) {}
}
