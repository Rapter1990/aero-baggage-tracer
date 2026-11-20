package com.aerotrace.journey.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aerotrace.common.export.ExportApi;
import com.aerotrace.common.security.ServiceToken;
import com.aerotrace.journey.base.AbstractTestContainerConfiguration;
import com.aerotrace.journey.outbox.OutboxRelay;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Without a configured service token the export refuses every call (the test profile is not dev).
 */
@SpringBootTest(properties = "aerotrace.journey.export.service-token=")
@AutoConfigureMockMvc
class ExportTokenClosedTest extends AbstractTestContainerConfiguration {

  @Autowired MockMvc mockMvc;

  /**
   * This context starts in the middle of the suite, with events pending in the shared outbox: its
   * scheduled relay would try the unreachable test broker and hold the relay lock meanwhile.
   */
  @MockitoBean OutboxRelay relay;

  @Test
  @DisplayName("fail closed: with no service token configured the export answers 401 to everyone")
  void failsClosed() throws Exception {
    mockMvc.perform(get(ExportApi.BAGS)).andExpect(status().isUnauthorized());
    mockMvc
        .perform(get(ExportApi.BAGS).header(ServiceToken.HEADER, ""))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get(ExportApi.BAGS).header(ServiceToken.HEADER, BagExportApiTest.TOKEN))
        .andExpect(status().isUnauthorized());
  }
}
