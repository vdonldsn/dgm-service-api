package com.dgm.api.scheduler;

import com.dgm.api.config.BusinessProperties;
import com.dgm.api.config.SupabaseClient;
import com.dgm.api.contract.ContractService;
import com.dgm.api.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContractSchedulerTest {

    @Mock private ContractService     contractService;
    @Mock private SupabaseClient      supabase;
    @Mock private NotificationService notificationService;
    @Mock private BusinessProperties  business;

    @Spy
    @InjectMocks
    private ContractScheduler scheduler;

    @BeforeEach
    void setup() {
        org.springframework.test.util.ReflectionTestUtils
            .setField(scheduler, "timezone", "America/Chicago");
        when(business.getShortName()).thenReturn("DGM");
        when(business.getOwnerPhone()).thenReturn("+16155550001");
    }

    private Map<String, Object> contractRow(String id, String lastGenDate) {
        Map<String, Object> c = new HashMap<>();
        c.put("id",                    id);
        c.put("property_id",           "prop-1");
        c.put("task_template_id",      null);
        c.put("task_name",             "HVAC filter replacement");
        c.put("service_category",      "PREVENTIVE_MAINTENANCE");
        c.put("estimated_duration_mins", 30);
        c.put("flat_rate",             65.00);
        c.put("auto_invoice_on_close", false);
        c.put("requires_photo_on_close", true);
        c.put("last_generated_date",   lastGenDate);
        return c;
    }

    @Test
    void noDueContracts_doesNothing() {
        when(contractService.getDueContracts(any())).thenReturn(List.of());

        scheduler.runNightlyContractCheck();

        verify(supabase, never()).insert(any(), any());
        verify(notificationService, never()).sendSms(any(), any());
    }

    @Test
    void dueContract_generatesWorkOrder() {
        Map<String, Object> contract = contractRow("c-1", "2025-05-01");
        when(contractService.getDueContracts(any())).thenReturn(List.of(contract));
        when(supabase.insert(eq("work_orders"), any()))
            .thenReturn(Map.of("id", "wo-new"));

        scheduler.runNightlyContractCheck();

        verify(supabase, times(1)).insert(eq("work_orders"), any());
        verify(contractService, times(1)).advanceNextDueDate("c-1");
    }

    @Test
    void alreadyGeneratedToday_skipsContract() {
        String today = LocalDate.now().toString();
        Map<String, Object> contract = contractRow("c-1", today);
        when(contractService.getDueContracts(any())).thenReturn(List.of(contract));

        scheduler.runNightlyContractCheck();

        verify(supabase, never()).insert(any(), any());
        verify(contractService, never()).advanceNextDueDate(any());
    }

    @Test
    void ownerNotifiedAfterSuccessfulGeneration() {
        Map<String, Object> contract = contractRow("c-1", "2025-05-01");
        when(contractService.getDueContracts(any())).thenReturn(List.of(contract));
        when(supabase.insert(eq("work_orders"), any()))
            .thenReturn(Map.of("id", "wo-new"));

        scheduler.runNightlyContractCheck();

        verify(notificationService, times(1)).sendSms(
            eq("+16155550001"), anyString());
    }

    @Test
    void contractProcessingError_doesNotStopOtherContracts() {
        Map<String, Object> bad  = contractRow("c-bad", "2025-05-01");
        Map<String, Object> good = contractRow("c-good", "2025-05-01");

        when(contractService.getDueContracts(any())).thenReturn(List.of(bad, good));

        // First contract throws on WO insert
        when(supabase.insert(eq("work_orders"), any()))
            .thenThrow(new RuntimeException("Supabase error"))
            .thenReturn(Map.of("id", "wo-good"));

        assertThatNoException().isThrownBy(() -> scheduler.runNightlyContractCheck());

        // Second contract still processed
        verify(supabase, times(2)).insert(eq("work_orders"), any());
    }

    @Test
    void workOrderBuiltWithCorrectContractFields() {
        Map<String, Object> contract = contractRow("c-1", "2025-05-01");
        when(contractService.getDueContracts(any())).thenReturn(List.of(contract));

        ArgumentCaptor<Map<String, Object>> woCaptor =
            ArgumentCaptor.forClass(Map.class);
        when(supabase.insert(eq("work_orders"), woCaptor.capture()))
            .thenReturn(Map.of("id", "wo-1"));

        scheduler.runNightlyContractCheck();

        Map<String, Object> wo = woCaptor.getValue();
        assertThat(wo.get("task_name")).isEqualTo("HVAC filter replacement");
        assertThat(wo.get("status")).isEqualTo("new");
        assertThat(wo.get("contract_id")).isEqualTo("c-1");
        assertThat(wo.get("property_id")).isEqualTo("prop-1");
    }
}
