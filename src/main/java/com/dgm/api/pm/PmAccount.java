package com.dgm.api.pm;

import lombok.Data;

/**
 * A property manager account. One PM can manage many properties.
 * This is the B2B customer type — distinct from homeowner (guest) bookings.
 *
 * PM accounts are created by the owner after an offline sales conversation.
 * PMs do not self-register; the owner onboards them.
 *
 * Relationship structure:
 *   pm_account (1) → properties (many) → units (many) → work_orders (many)
 *                  → maintenance_contracts (many)
 */
@Data
public class PmAccount {
    private String  id;
    private String  companyName;
    private String  contactName;
    private String  email;
    private String  phone;
    private String  billingAddress;
    private Boolean autoInvoiceDefault; // default for new contracts
    private String  notes;
    private Boolean active;
    private String  createdAt;
}
