package com.dgm.api.notification;

import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

// ── Request DTOs ─────────────────────────────────────────────────────────────

record BookingConfirmedRequest(
        @NotBlank String guestPhone,
        @NotBlank String guestName) {}

record TechAssignedRequest(
        @NotBlank String guestPhone,
        @NotBlank String guestName,
        @NotBlank String techName,
        @NotBlank String date,
        @NotBlank String time) {}

record OnMyWayRequest(
        @NotBlank String guestPhone,
        @NotBlank String guestName,
        @NotBlank String techName,
        int etaMins) {}

record JobCompleteRequest(
        @NotBlank String guestPhone,
        @NotBlank String guestName,
        @NotBlank String techName) {}

record InvoiceSentRequest(
        @NotBlank String guestPhone,
        @NotBlank String guestName,
        @NotBlank String amount,
        @NotBlank String stripePaymentLink) {}

record ScopeFlagRequest(
        @NotBlank String ownerPhone,
        @NotBlank String woId,
        @NotBlank String address,
        @NotBlank String flagType) {}

record UrgentJobRequest(
        @NotBlank String ownerPhone,
        @NotBlank String woId,
        @NotBlank String address,
        @NotBlank String taskName) {}

// ── Controller ───────────────────────────────────────────────────────────────

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
class NotificationController {

    private final NotificationService notificationService;

    @PostMapping("/booking-confirmed")
    public ResponseEntity<Void> bookingConfirmed(
            @Valid @RequestBody BookingConfirmedRequest req) {
        notificationService.sendBookingConfirmed(req.guestPhone(), req.guestName());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/tech-assigned")
    public ResponseEntity<Void> techAssigned(
            @Valid @RequestBody TechAssignedRequest req) {
        notificationService.sendTechAssigned(
                req.guestPhone(), req.guestName(),
                req.techName(), req.date(), req.time());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/on-my-way")
    public ResponseEntity<Void> onMyWay(
            @Valid @RequestBody OnMyWayRequest req) {
        notificationService.sendOnMyWay(
                req.guestPhone(), req.guestName(),
                req.techName(), req.etaMins());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/job-complete")
    public ResponseEntity<Void> jobComplete(
            @Valid @RequestBody JobCompleteRequest req) {
        notificationService.sendJobComplete(
                req.guestPhone(), req.guestName(), req.techName());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/invoice-sent")
    public ResponseEntity<Void> invoiceSent(
            @Valid @RequestBody InvoiceSentRequest req) {
        notificationService.sendInvoiceSent(
                req.guestPhone(), req.guestName(),
                req.amount(), req.stripePaymentLink());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/scope-flag")
    public ResponseEntity<Void> scopeFlag(
            @Valid @RequestBody ScopeFlagRequest req) {
        notificationService.sendScopeFlag(
                req.ownerPhone(), req.woId(),
                req.address(), req.flagType());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/urgent-job")
    public ResponseEntity<Void> urgentJob(
            @Valid @RequestBody UrgentJobRequest req) {
        notificationService.sendUrgentJob(
                req.ownerPhone(), req.woId(),
                req.address(), req.taskName());
        return ResponseEntity.ok().build();
    }
}
