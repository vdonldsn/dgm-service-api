package com.dgm.api.contract;

import lombok.Data;
import java.util.List;

/**
 * A reusable job definition. One template can be referenced by many
 * maintenance contracts across different properties and clients.
 *
 * The checklist field is a JSON array of strings stored in Supabase.
 * Example: ["Check filter condition","Replace filter if dirty","Log filter size"]
 *
 * When a work order is generated from a contract, these checklist items
 * are copied into work_orders.checklist so the tech has a task list.
 */
@Data
public class TaskTemplate {

    private String       id;
    private String       name;             // "HVAC filter replacement"
    private String       serviceCategory;  // "PREVENTIVE_MAINTENANCE"
    private String       description;      // instructions for the tech
    private List<String> checklist;        // ordered list of checklist items
    private Integer      estimatedDurationMins;
    private Double       defaultFlatRate;  // can be overridden in contract
    private Boolean      requiresLicense;  // triggers scope flag if true
    private String       createdAt;
}
