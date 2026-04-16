package com.dgm.api.invoice;

import com.dgm.api.config.SupabaseClient;
import com.dgm.api.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InvoiceServiceTest {

    @Mock
    private SupabaseClient supabase;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private InvoiceService invoiceService;

    private Map<String, Object> mockWorkOrder() {
        Map<String, Object> wo = new HashMap<>();
        wo.put("id",           "wo-123");
        wo.put("task_name",    "Leaky faucet repair");
        wo.put("flat_rate",    95.00);
        wo.put("guest_name",   "Jane Smith");
        wo.put("guest_phone",  "+16155550000");
        wo.put("guest_email",  "jane@example.com");
        return wo;
    }

    private Map<String, Object> mockInvoice(String status) {
        Map<String, Object> inv = new HashMap<>();
        inv.put("id",              "inv-456");
        inv.put("work_order_id",   "wo-123");
        inv.put("amount",          95.00);
        inv.put("status",          status);
        inv.put("guest_phone",     "+16155550000");
        inv.put("guest_email",     "jane@example.com");
        return inv;
    }

    @BeforeEach
    void setup() {
        // Inject fake Stripe/webhook secrets so @PostConstruct doesn't fail
        org.springframework.test.util.ReflectionTestUtils
            .setField(invoiceService, "stripeSecretKey",  "sk_test_fake");
        org.springframework.test.util.ReflectionTestUtils
            .setField(invoiceService, "webhookSecret", "whsec_fake");
    }

    // ── createInvoice ──────────────────────────────────────────────────────

    @Test
    void createInvoice_buildsCorrectRecord() {
        when(supabase.findById("work_orders", "wo-123"))
            .thenReturn(mockWorkOrder());

        Map<String, Object> createdRecord = new HashMap<>();
        createdRecord.put("id", "inv-new");
        when(supabase.insert(eq("invoices"), any())).thenReturn(createdRecord);

        String invoiceId = invoiceService.createInvoice("wo-123");

        assertThat(invoiceId).isEqualTo("inv-new");

        // Verify the insert captured the right fields
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(supabase).insert(eq("invoices"), captor.capture());
        Map<String, Object> inserted = captor.getValue();

        assertThat(inserted.get("work_order_id")).isEqualTo("wo-123");
        assertThat(inserted.get("status")).isEqualTo("draft");
        assertThat(((Number) inserted.get("amount")).doubleValue()).isEqualTo(95.00);
    }

    @Test
    void createInvoice_withNoFlatRate_defaultsToZero() {
        Map<String, Object> wo = mockWorkOrder();
        wo.remove("flat_rate"); // quote on site
        when(supabase.findById("work_orders", "wo-123")).thenReturn(wo);

        Map<String, Object> created = Map.of("id", "inv-new");
        when(supabase.insert(eq("invoices"), any())).thenReturn(created);

        invoiceService.createInvoice("wo-123");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(supabase).insert(eq("invoices"), captor.capture());
        assertThat(((Number) captor.getValue().get("amount")).doubleValue()).isEqualTo(0.00);
    }

    @Test
    void createInvoice_linksInvoiceBackToWorkOrder() {
        when(supabase.findById("work_orders", "wo-123")).thenReturn(mockWorkOrder());
        when(supabase.insert(eq("invoices"), any())).thenReturn(Map.of("id", "inv-new"));

        invoiceService.createInvoice("wo-123");

        verify(supabase).update(eq("work_orders"), eq("wo-123"),
            argThat(m -> "inv-new".equals(m.get("invoice_id"))));
    }

    // ── markPaid ──────────────────────────────────────────────────────────

    @Test
    void markPaid_updatesSuapbaseWithCorrectStatus() {
        invoiceService.markPaid("inv-456");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(supabase).update(eq("invoices"), eq("inv-456"), captor.capture());

        assertThat(captor.getValue().get("status")).isEqualTo("paid");
        assertThat(captor.getValue().get("paid_at")).isNotNull();
    }

    // ── handleStripeWebhook ───────────────────────────────────────────────

    @Test
    void handleStripeWebhook_invalidSignature_throwsCustomException() {
        // Provide a syntactically real-looking but wrong signature
        String fakePayload = "{\"type\":\"payment_intent.succeeded\"}";
        String fakeSig     = "t=1234,v1=invalidsignature";

        assertThatThrownBy(() ->
            invoiceService.handleStripeWebhook(fakePayload, fakeSig))
            .isInstanceOf(InvoiceService.InvalidStripeSignatureException.class);
    }

    @Test
    void handleStripeWebhook_badSignature_doesNotMarkPaid() {
        try {
            invoiceService.handleStripeWebhook("{}", "bad-sig");
        } catch (InvoiceService.InvalidStripeSignatureException ignored) {}

        verify(supabase, never()).update(eq("invoices"), any(), any());
    }
}
