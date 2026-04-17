package com.dgm.api.notification;

import com.dgm.api.config.BusinessProperties;
import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final BusinessProperties business;

    @Value("${twilio.account-sid}")
    private String accountSid;

    @Value("${twilio.auth-token}")
    private String authToken;

    @Value("${twilio.from-number}")
    private String fromNumber;

    @PostConstruct
    public void initTwilio() {
        Twilio.init(accountSid, authToken);
        log.info("Twilio initialized for number {}", fromNumber);
    }

    // ── Public notification methods ──────────────────────────────────────────

    public void sendBookingConfirmed(String toPhone, String guestName) {
        String msg = String.format(
            "%s | Hi %s, your booking is confirmed! We will reach out shortly " +
            "to confirm your tech and time. %s — we've got you covered.",
            business.getShortName(), guestName, business.getTagline());
        sendSms(toPhone, msg);
    }

    public void sendTechAssigned(String toPhone, String guestName,
                                  String techName, String date, String time) {
        String msg = String.format(
            "%s | Hi %s, %s has been assigned to your job. " +
            "We'll see you on %s around %s.",
            business.getShortName(), guestName, techName, date, time);
        sendSms(toPhone, msg);
    }

    public void sendOnMyWay(String toPhone, String guestName,
                             String techName, int etaMins) {
        String eta = etaMins > 0 ? "about " + etaMins + " minutes" : "shortly";
        String msg = String.format(
            "%s | Hi %s, %s is on the way — should be there in %s. " +
            "Nashville's own, on the move.",
            business.getShortName(), guestName, techName, eta);
        sendSms(toPhone, msg);
    }

    public void sendJobComplete(String toPhone, String guestName, String techName) {
        String msg = String.format(
            "%s | Hi %s, your job is complete! %s has wrapped up. " +
            "Your invoice is coming shortly. Thanks for trusting us.",
            business.getShortName(), guestName, techName);
        sendSms(toPhone, msg);
    }

    public void sendInvoiceSent(String toPhone, String guestName,
                                 String amount, String paymentLink) {
        String msg = String.format(
            "%s | Hi %s, your invoice for $%s is ready. " +
            "Pay securely here: %s  — Questions? Just reply to this message.",
            business.getShortName(), guestName, amount, paymentLink);
        sendSms(toPhone, msg);
    }

    public void sendScopeFlag(String ownerPhone, String woId, String address,
                               String flagType) {
        String msg = String.format(
            "%s Alert | Tech flagged a scope issue on job #%s at %s. " +
            "Issue: %s. Log in to review.",
            business.getShortName(), woId, address, flagType);
        sendSms(ownerPhone, msg);
    }

    public void sendUrgentJob(String ownerPhone, String woId,
                               String address, String taskName) {
        String msg = String.format(
            "%s Alert | Urgent job submitted: %s at %s (Job #%s). " +
            "Log in to assign a tech.",
            business.getShortName(), taskName, address, woId);
        sendSms(ownerPhone, msg);
    }

    // ── Core SMS sender ──────────────────────────────────────────────────────

    /**
     * Fire-and-forget SMS. Logs failures but never throws —
     * a Twilio failure must not block the booking or job flow.
     */
    public void sendSms(String toPhone, String message) {
        try {
            Message sent = Message.creator(
                    new PhoneNumber(toPhone),
                    new PhoneNumber(fromNumber),
                    message
            ).create();
            log.info("SMS sent to {} — SID {}", toPhone, sent.getSid());
        } catch (Exception e) {
            log.error("SMS failed to {}: {}", toPhone, e.getMessage());
            // Intentionally not re-throwing
        }
    }
}
