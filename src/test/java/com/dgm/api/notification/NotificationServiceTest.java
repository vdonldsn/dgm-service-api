package com.dgm.api.notification;

import com.dgm.api.config.BusinessProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private BusinessProperties business;

    @Spy
    @InjectMocks
    private NotificationService notificationService;

    @BeforeEach
    void setup() {
        when(business.getShortName()).thenReturn("DGM");
        when(business.getTagline()).thenReturn("Here when you need us most");
        // Prevent real Twilio calls in tests
        doNothing().when(notificationService).sendSms(anyString(), anyString());
    }

    @Test
    void bookingConfirmed_containsBusinessNameAndGuestName() {
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);

        notificationService.sendBookingConfirmed("+16155550000", "John");

        verify(notificationService).sendSms(eq("+16155550000"), msgCaptor.capture());
        assertThat(msgCaptor.getValue())
            .contains("DGM")
            .contains("John")
            .contains("confirmed");
    }

    @Test
    void techAssigned_includesTechNameAndDateTime() {
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);

        notificationService.sendTechAssigned(
            "+16155550000", "Sarah", "Mike", "Friday Aug 1", "10:00 AM");

        verify(notificationService).sendSms(eq("+16155550000"), msgCaptor.capture());
        assertThat(msgCaptor.getValue())
            .contains("Mike")
            .contains("Friday Aug 1")
            .contains("10:00 AM");
    }

    @Test
    void onMyWay_withZeroEta_stillSendsSafely() {
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);

        notificationService.sendOnMyWay("+16155550000", "Tom", "Carlos", 0);

        verify(notificationService).sendSms(eq("+16155550000"), msgCaptor.capture());
        assertThat(msgCaptor.getValue()).contains("shortly");
    }

    @Test
    void onMyWay_withPositiveEta_includesMinutes() {
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);

        notificationService.sendOnMyWay("+16155550000", "Tom", "Carlos", 20);

        verify(notificationService).sendSms(eq("+16155550000"), msgCaptor.capture());
        assertThat(msgCaptor.getValue()).contains("20 minutes");
    }

    @Test
    void invoiceSent_includesAmountAndLink() {
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);

        notificationService.sendInvoiceSent(
            "+16155550000", "Amy", "125.00", "https://pay.stripe.com/abc123");

        verify(notificationService).sendSms(eq("+16155550000"), msgCaptor.capture());
        assertThat(msgCaptor.getValue())
            .contains("125.00")
            .contains("https://pay.stripe.com/abc123");
    }

    @Test
    void scopeFlag_sentToOwnerPhone() {
        ArgumentCaptor<String> phoneCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> msgCaptor   = ArgumentCaptor.forClass(String.class);

        notificationService.sendScopeFlag(
            "+16155550001", "WO-001", "123 Main St", "Needs licensed electrician");

        verify(notificationService).sendSms(phoneCaptor.capture(), msgCaptor.capture());
        assertThat(phoneCaptor.getValue()).isEqualTo("+16155550001");
        assertThat(msgCaptor.getValue())
            .contains("WO-001")
            .contains("Needs licensed electrician");
    }

    @Test
    void twilioFailure_doesNotThrowException() {
        // Override spy to simulate Twilio throwing
        doThrow(new RuntimeException("Twilio connection refused"))
            .when(notificationService).sendSms(anyString(), anyString());

        // Must not propagate — fire and forget
        assertThatNoException().isThrownBy(() ->
            notificationService.sendBookingConfirmed("+16155550000", "Dave"));
    }
}
