package com.dgm.api.contract;

import lombok.Data;
import java.time.LocalDate;

/**
 * Represents a recurring maintenance agreement between a property manager
 * and the business. This is the engine that drives auto-generated work orders.
 *
 * Cadence types:
 *   DAILY     - runs every N days (rare, used for daily checks)
 *   WEEKLY    - runs every N weeks on a specific day
 *   MONTHLY   - runs on a specific day of month every N months
 *   QUARTERLY - shorthand for every 3 months
 *   SEASONAL  - spring, summer, fall, winter (4x per year)
 *   ANNUAL    - once per year on a specific date
 *   TRIGGER   - does NOT auto-schedule; fires on an event (unit vacancy, etc.)
 *
 * Pricing types:
 *   FLAT_RATE        - fixed amount per occurrence
 *   TIME_AND_MATERIAL - rate per hour + parts; invoice is built after job closes
 */
@Data
public class MaintenanceContract {

    private String   id;
    private String   pmAccountId;          // FK → pm_accounts.id
    private String   propertyId;           // FK → properties.id (nullable = all units)
    private String   unitId;               // specific unit (nullable = whole property)
    private String   taskTemplateId;       // FK → task_templates.id
    private String   taskName;             // denormalized for quick reads
    private String   serviceCategory;

    // Scheduling
    private String   cadenceType;          // DAILY|WEEKLY|MONTHLY|QUARTERLY|SEASONAL|ANNUAL|TRIGGER
    private Integer  cadenceIntervalValue; // e.g. every 2 weeks → 2
    private Integer  dayOfWeek;            // 0-6 for WEEKLY cadence
    private Integer  dayOfMonth;           // 1-31 for MONTHLY/ANNUAL
    private String   seasonalMonth;        // JAN|APR|JUL|OCT for SEASONAL
    private LocalDate startDate;
    private LocalDate endDate;             // null = indefinite
    private LocalDate nextDueDate;         // computed; scheduler uses this field
    private LocalDate lastGeneratedDate;   // when the last WO was created

    // Pricing
    private String   pricingType;          // FLAT_RATE|TIME_AND_MATERIAL
    private Double   flatRate;             // used when pricingType=FLAT_RATE
    private Double   hourlyRate;           // used when pricingType=TIME_AND_MATERIAL
    private Integer  estimatedDurationMins;

    // Behavior flags
    private Boolean  autoInvoiceOnClose;   // true = invoice fires automatically on WO complete
    private Boolean  requiresPhotoOnClose; // enforce before/after photos
    private Boolean  active;

    private String   notes;
    private String   createdAt;
    private String   updatedAt;
}
