package com.aerotrace.recovery.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aerotrace.common.export.ExportApi;
import com.aerotrace.common.security.ServiceToken;
import com.aerotrace.recovery.base.AbstractRecoveryTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Without a configured service token the export refuses every call (the test profile is not dev).
 */
@SpringBootTest(properties = "aerotrace.recovery.export.service-token=")
@AutoConfigureMockMvc
class ExportTokenClosedTest extends AbstractRecoveryTest {

  @Autowired MockMvc mockMvc;
  @MockitoBean KafkaTemplate<String, String> kafka;

  @Test
  @DisplayName("fail closed: with no service token configured the export answers 401 to everyone")
  void failsClosed() throws Exception {
    mockMvc.perform(get(ExportApi.CASES)).andExpect(status().isUnauthorized());
    mockMvc
        .perform(get(ExportApi.CASES).header(ServiceToken.HEADER, ""))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get(ExportApi.CASES).header(ServiceToken.HEADER, CaseExportApiTest.TOKEN))
        .andExpect(status().isUnauthorized());
  }
}
