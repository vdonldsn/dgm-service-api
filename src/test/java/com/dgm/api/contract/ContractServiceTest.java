package com.dgm.api.contract;

import com.dgm.api.config.SupabaseClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ContractServiceTest {

    @Mock
    private SupabaseClient supabase;

    @InjectMocks
    private ContractService contractService;

    private Map<String, Object> contract(String cadence, int interval) {
        Map<String, Object> c = new HashMap<>();
        c.put("cadence_type",           cadence);
        c.put("cadence_interval_value", interval);
        return c;
    }

    private final LocalDate BASE = LocalDate.of(2025, 6, 15);

    // ── Cadence: DAILY ────────────────────────────────────────────────────────
    @Test
    void daily_advancesByIntervalDays() {
        LocalDate next = contractService.computeNextDueDate(contract("DAILY", 3), BASE);
        assertThat(next).isEqualTo(BASE.plusDays(3));
    }

    // ── Cadence: WEEKLY ───────────────────────────────────────────────────────
    @Test
    void weekly_advancesByIntervalWeeks() {
        LocalDate next = contractService.computeNextDueDate(contract("WEEKLY", 2), BASE);
        assertThat(next).isEqualTo(BASE.plusWeeks(2));
    }

    // ── Cadence: MONTHLY ──────────────────────────────────────────────────────
    @Test
    void monthly_advancesByIntervalMonths() {
        LocalDate next = contractService.computeNextDueDate(contract("MONTHLY", 1), BASE);
        assertThat(next).isEqualTo(BASE.plusMonths(1));
    }

    // ── Cadence: QUARTERLY ────────────────────────────────────────────────────
    @Test
    void quarterly_advancesBy3Months() {
        LocalDate next = contractService.computeNextDueDate(contract("QUARTERLY", 1), BASE);
        assertThat(next).isEqualTo(BASE.plusMonths(3));
    }

    @Test
    void quarterly_ignoresIntervalValue() {
        // interval value should be ignored for QUARTERLY — always 3 months
        LocalDate next1 = contractService.computeNextDueDate(contract("QUARTERLY", 1), BASE);
        LocalDate next2 = contractService.computeNextDueDate(contract("QUARTERLY", 5), BASE);
        assertThat(next1).isEqualTo(next2);
    }

    // ── Cadence: ANNUAL ───────────────────────────────────────────────────────
    @Test
    void annual_advancesBy1Year() {
        LocalDate next = contractService.computeNextDueDate(contract("ANNUAL", 1), BASE);
        assertThat(next).isEqualTo(BASE.plusYears(1));
    }

    // ── Cadence: SEASONAL ────────────────────────────────────────────────────
    @Test
    void seasonal_fromJune_returnsJuly() {
        // June → next seasonal month is July (Jul 1)
        LocalDate from = LocalDate.of(2025, 6, 15);
        LocalDate next = contractService.computeNextDueDate(contract("SEASONAL", 1), from);
        assertThat(next).isEqualTo(LocalDate.of(2025, 7, 1));
    }

    @Test
    void seasonal_fromAugust_returnsOctober() {
        LocalDate from = LocalDate.of(2025, 8, 1);
        LocalDate next = contractService.computeNextDueDate(contract("SEASONAL", 1), from);
        assertThat(next).isEqualTo(LocalDate.of(2025, 10, 1));
    }

    @Test
    void seasonal_fromNovember_wrapsToJanuaryNextYear() {
        LocalDate from = LocalDate.of(2025, 11, 1);
        LocalDate next = contractService.computeNextDueDate(contract("SEASONAL", 1), from);
        assertThat(next).isEqualTo(LocalDate.of(2026, 1, 1));
    }

    // ── Cadence: TRIGGER ──────────────────────────────────────────────────────
    @Test
    void trigger_returnsNull() {
        LocalDate next = contractService.computeNextDueDate(contract("TRIGGER", 1), BASE);
        assertThat(next).isNull();
    }

    // ── Unknown cadence graceful handling ─────────────────────────────────────
    @Test
    void unknownCadence_defaultsToOneMonth() {
        LocalDate next = contractService.computeNextDueDate(contract("INVALID_TYPE", 1), BASE);
        assertThat(next).isEqualTo(BASE.plusMonths(1));
    }
}
