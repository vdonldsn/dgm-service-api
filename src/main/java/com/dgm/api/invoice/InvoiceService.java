package com.dgm.api.invoice;

import com.dgm.api.config.SupabaseClient;
import com.dgm.api.notification.NotificationService;
import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.PaymentLink;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentLinkCreateParams;
import com.stripe.param.PriceCreateParams;
import com.stripe.model.Price;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final SupabaseClient supabase;
    private final NotificationService notificationService;

    @Value("${stripe.secret-key}")
    private String stripeSecretKey;

    @Value("${stripe.webhook-secret}")
    private String webhookSecret;

    @PostConstruct
    public void initStripe() {
        Stripe.apiKey = stripeSecretKey;
        log.info("Stripe initialized");
    }

    // ── Create draft invoice from a completed work order ─────────────────────

    public String createInvoice(String woId) {
        Map<String, Object> wo = supabase.findById("work_orders", woId);

        // Build line items — flat rate if available, otherwise $0 (quote on site)
        Object flatRate = wo.get("flat_rate");
        double amount = flatRate != null
                ? ((Number) flatRate).doubleValue()
                : 0.00;

        Map<String, Object> lineItem = new HashMap<>();
        lineItem.put("description", wo.getOrDefault("task_name", "Service").toString());
        lineItem.put("amount", amount);

        Map<String, Object> invoiceData = new HashMap<>();
        invoiceData.put("work_order_id", woId);
        invoiceData.put("guest_email",   wo.getOrDefault("guest_email", ""));
        invoiceData.put("guest_phone",   wo.getOrDefault("guest_phone", ""));
        invoiceData.put("amount",        amount);
        invoiceData.put("status",        "draft");
        invoiceData.put("line_items",    lineItem);

        Map<String, Object> created = supabase.insert("invoices", invoiceData);
        String invoiceId = created.get("id").toString();
        log.info("Invoice draft created: {} for WO {}", invoiceId, woId);

        // Link invoice back to work order
        supabase.update("work_orders", woId, Map.of("invoice_id", invoiceId));

        return invoiceId;
    }

    // ── Send invoice: create Stripe payment link and notify customer ──────────

    public String sendInvoice(String invoiceId) {
        Map<String, Object> invoice = supabase.findById("invoices", invoiceId);

        double amount = ((Number) invoice.get("amount")).doubleValue();
        String guestPhone = invoice.getOrDefault("guest_phone", "").toString();
        String guestEmail = invoice.getOrDefault("guest_email", "").toString();
        String woId       = invoice.get("work_order_id").toString();

        Map<String, Object> wo = supabase.findById("work_orders", woId);
        String taskName  = wo.getOrDefault("task_name", "Service").toString();
        String guestName = wo.getOrDefault("guest_name", "Customer").toString();

        String paymentLink;
        try {
            paymentLink = createStripePaymentLink(amount, taskName, invoiceId, woId);
        } catch (Exception e) {
            log.error("Stripe payment link creation failed for invoice {}: {}", invoiceId, e.getMessage());
            throw new RuntimeException("Failed to create payment link: " + e.getMessage());
        }

        // Update invoice record
        Map<String, Object> updates = new HashMap<>();
        updates.put("stripe_payment_link", paymentLink);
        updates.put("status", "sent");
        updates.put("sent_at", Instant.now().toString());
        supabase.update("invoices", invoiceId, updates);

        // Notify customer
        if (!guestPhone.isBlank()) {
            notificationService.sendInvoiceSent(
                    guestPhone, guestName,
                    String.format("%.2f", amount),
                    paymentLink);
        }

        log.info("Invoice {} sent — Stripe link created", invoiceId);
        return paymentLink;
    }

    // ── Mark invoice paid (manual or via Stripe webhook) ─────────────────────

    public void markPaid(String invoiceId) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("status",  "paid");
        updates.put("paid_at", Instant.now().toString());
        supabase.update("invoices", invoiceId, updates);
        log.info("Invoice {} marked as paid", invoiceId);
    }

    // ── Stripe webhook handler ────────────────────────────────────────────────

    public void handleStripeWebhook(String payload, String sigHeader) {
        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            log.warn("Stripe webhook signature verification failed: {}", e.getMessage());
            throw new InvalidStripeSignatureException("Invalid Stripe signature");
        }

        log.info("Stripe webhook received: {}", event.getType());

        switch (event.getType()) {
            case "payment_intent.succeeded" -> handlePaymentIntentSucceeded(event);
            case "payment_link.completed"   -> handlePaymentLinkCompleted(event);
            default -> log.debug("Unhandled Stripe event: {}", event.getType());
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String createStripePaymentLink(double amount, String description,
                                            String invoiceId, String woId)
            throws Exception {
        // Stripe amounts are in cents
        long amountCents = BigDecimal.valueOf(amount)
                .multiply(BigDecimal.valueOf(100))
                .longValue();

        // Create a one-time price for this service amount
        Price price = Price.create(
            PriceCreateParams.builder()
                .setCurrency("usd")
                .setUnitAmount(amountCents)
                .setProductData(PriceCreateParams.ProductData.builder()
                    .setName(description)
                    .build())
                .build()
        );

        // Create the payment link with metadata to trace back to our invoice
        PaymentLink link = PaymentLink.create(
            PaymentLinkCreateParams.builder()
                .addLineItem(PaymentLinkCreateParams.LineItem.builder()
                    .setPrice(price.getId())
                    .setQuantity(1L)
                    .build())
                .putMetadata("invoice_id", invoiceId)
                .putMetadata("wo_id", woId)
                .build()
        );

        return link.getUrl();
    }

    private void handlePaymentIntentSucceeded(Event event) {
        // Extract invoice_id from metadata if present
        event.getDataObjectDeserializer().getObject().ifPresent(obj -> {
            try {
                var pi = (com.stripe.model.PaymentIntent) obj;
                String invoiceId = pi.getMetadata().get("invoice_id");
                if (invoiceId != null) {
                    markPaid(invoiceId);
                }
            } catch (Exception e) {
                log.error("Failed processing payment_intent.succeeded: {}", e.getMessage());
            }
        });
    }

    private void handlePaymentLinkCompleted(Event event) {
        event.getDataObjectDeserializer().getObject().ifPresent(obj -> {
            try {
                var session = (com.stripe.model.checkout.Session) obj;
                String invoiceId = session.getMetadata().get("invoice_id");
                if (invoiceId != null) {
                    markPaid(invoiceId);
                }
            } catch (Exception e) {
                log.error("Failed processing payment_link.completed: {}", e.getMessage());
            }
        });
    }

    // ── Custom exception for bad Stripe signatures ────────────────────────────
    static class InvalidStripeSignatureException extends RuntimeException {
        InvalidStripeSignatureException(String message) { super(message); }
    }
}
