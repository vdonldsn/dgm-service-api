package com.dgm.api.pm;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public class PmDTOs {

    public record CreatePmRequest(
        @NotBlank String  companyName,
        @NotBlank String  contactName,
        @NotBlank @Email  String  email,
        @NotBlank String  phone,
                  String  billingAddress,
                  Boolean autoInvoiceDefault,
                  String  notes
    ) {}

    public record UnitVacancyRequest(
        @NotBlank String pmAccountId,
        @NotBlank String propertyId,
        @NotBlank String unitId,
        @NotBlank String targetMoveInDate   // ISO date — when unit needs to be ready
    ) {}
}
