package com.dgm.api.notification;

import com.dgm.api.config.BusinessProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceTest {

    @Mock
    private BusinessProperties business;

    @InjectMocks
    private NotificationService notificationService;

    @BeforeEach
    void setup() {
        when(business.getShortName()).thenReturn("DGM");
        when(business.getTagline()).thenReturn("Here when you need us most");
        // Spy on sendSms so we can verify calls without hitting real Twilio
        // We mock it at the method level in each test using doNothing/doThrow
    }

    // ── Helper: capture the SMS message sent ─────────────────────────────────

    private String captureMessage(String phone) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        // Suppress actual Twilio call — sendSms will throw NPE without init
        // We verify the string content via the captor on a spy
        return captor.getValue();
    }

    @Test
    void bookingConfirmed_containsBusinessNameAndGuestName() {
        NotificationService spy = spy(notificationService);
        doNothing().when(spy).sendSms(anyString(), anyString());

        spy.sendBookingConfirmed("+16155550000", "John");

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(spy).sendSms(eq("+16155550000"), msgCaptor.capture());
        assertThat(msgCaptor.getValue()).contains("DGM").contains("John").contains("confirmed");
    }

    @Test
    void techAssigned_includesTechNameAndDateTime() {
        NotificationService spy = spy(notificationService);
        doNothing().when(spy).sendSms(anyString(), anyString());

        spy.sendTechAssigned("+16155550000", "Sarah", "Mike", "Friday Aug 1", "10:00 AM");

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(spy).sendSms(eq("+16155550000"), msgCaptor.capture());
        assertThat(msgCaptor.getValue())
            .contains("Mike")
            .contains("Friday Aug 1")
            .contains("10:00 AM");
    }

    @Test
    void onMyWay_withZeroEta_stillSendsSafely() {
        NotificationService spy = spy(notificationService);
        doNothing().when(spy).sendSms(anyString(), anyString());

        spy.sendOnMyWay("+16155550000", "Tom", "Carlos", 0);

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(spy).sendSms(eq("+16155550000"), msgCaptor.capture());
        assertThat(msgCaptor.getValue()).contains("shortly");
    }

    @Test
    void onMyWay_withPositiveEta_includesMinutes() {
        NotificationService spy = spy(notificationService);
        doNothing().when(spy).sendSms(anyString(), anyString());

        spy.sendOnMyWay("+16155550000", "Tom", "Carlos", 20);

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(spy).sendSms(eq("+16155550000"), msgCaptor.capture());
        assertThat(msgCaptor.getValue()).contains("20 minutes");
    }

    @Test
    void invoiceSent_includesAmountAndLink() {
        NotificationService spy = spy(notificationService);
        doNothing().when(spy).sendSms(anyString(), anyString());

        spy.sendInvoiceSent("+16155550000", "Amy", "125.00",
            "https://pay.stripe.com/abc123");

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(spy).sendSms(eq("+16155550000"), msgCaptor.capture());
        assertThat(msgCaptor.getValue())
            .contains("125.00")
            .contains("https://pay.stripe.com/abc123");
    }

    @Test
    void scopeFlag_sentToOwnerPhone() {
        NotificationService spy = spy(notificationService);
        doNothing().when(spy).sendSms(anyString(), anyString());

        spy.sendScopeFlag("+16155550001", "WO-001",
            "123 Main St", "Needs licensed electrician");

        ArgumentCaptor<String> phoneCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> msgCaptor   = ArgumentCaptor.forClass(String.class);
        verify(spy).sendSms(phoneCaptor.capture(), msgCaptor.capture());
        assertThat(phoneCaptor.getValue()).isEqualTo("+16155550001");
        assertThat(msgCaptor.getValue())
            .contains("WO-001")
            .contains("Needs licensed electrician");
    }

    @Test
    void sendSms_withUninitializedTwilio_doesNotThrow() {
        // sendSms wraps Twilio in a try/catch and must never propagate.
        // Calling it without Twilio initialized causes an internal exception —
        // assertThatNoException confirms the try/catch swallows it correctly.
        assertThatNoException().isThrownBy(() ->
                notificationService.sendSms("+16155550000", "test message"));
    }
}
