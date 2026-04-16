package com.dgm.api.invoice;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// ── Request DTOs ─────────────────────────────────────────────────────────────

record CreateInvoiceRequest(@NotBlank String woId) {}

// ── Controller ───────────────────────────────────────────────────────────────

@RestController
@RequestMapping("/api/invoices")
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService invoiceService;

    /**
     * POST /api/invoices/create
     * Called by tech or owner when closing a work order.
     * Creates a draft invoice record in Supabase.
     */
    @PostMapping("/create")
    public ResponseEntity<InvoiceCreatedResponse> createInvoice(
            @Valid @RequestBody CreateInvoiceRequest req) {
        String invoiceId = invoiceService.createInvoice(req.woId());
        return ResponseEntity.ok(new InvoiceCreatedResponse(invoiceId));
    }

    /**
     * POST /api/invoices/send/{invoiceId}
     * Owner only. Creates Stripe payment link and sends to customer via SMS.
     */
    @PostMapping("/send/{invoiceId}")
    public ResponseEntity<InvoiceSentResponse> sendInvoice(
            @PathVariable String invoiceId) {
        String paymentLink = invoiceService.sendInvoice(invoiceId);
        return ResponseEntity.ok(new InvoiceSentResponse(invoiceId, paymentLink));
    }

    /**
     * POST /api/invoices/mark-paid/{invoiceId}
     * Owner only. Marks a cash or manually confirmed invoice as paid.
     */
    @PostMapping("/mark-paid/{invoiceId}")
    public ResponseEntity<Void> markPaid(@PathVariable String invoiceId) {
        invoiceService.markPaid(invoiceId);
        return ResponseEntity.ok().build();
    }

    /**
     * POST /api/invoices/stripe-webhook
     * Public — Stripe calls this when a payment is completed.
     * Stripe signature is verified inside InvoiceService.
     */
    @PostMapping("/stripe-webhook")
    public ResponseEntity<Void> stripeWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader) {
        try {
            invoiceService.handleStripeWebhook(payload, sigHeader);
            return ResponseEntity.ok().build();
        } catch (InvoiceService.InvalidStripeSignatureException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    // ── Response records ──────────────────────────────────────────────────────
    record InvoiceCreatedResponse(String invoiceId) {}
    record InvoiceSentResponse(String invoiceId, String paymentLink) {}
}
