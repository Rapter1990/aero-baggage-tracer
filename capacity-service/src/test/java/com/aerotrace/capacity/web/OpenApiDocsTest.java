package com.aerotrace.capacity.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aerotrace.capacity.base.AbstractCapacityTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocsTest extends AbstractCapacityTest {

  @Autowired MockMvc mockMvc;
  @MockitoBean KafkaTemplate<String, String> kafka;

  @Test
  @DisplayName("AT-16 the definition is public and documents 401 and 403 on every operation")
  void everyOperationDocuments401And403() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.info.title").value("AeroTrace Capacity Service"))
        .andExpect(
            jsonPath("$.paths['/api/v1/capacity/reservations'].post.responses['401']").exists())
        .andExpect(
            jsonPath("$.paths['/api/v1/capacity/reservations'].post.responses['403']").exists())
        .andExpect(
            jsonPath("$.paths['/api/v1/capacity/reservations/{id}'].delete.responses['401']")
                .exists())
        .andExpect(
            jsonPath("$.paths['/api/v1/capacity/reservations/{id}'].delete.responses['403']")
                .exists())
        .andExpect(
            jsonPath("$.paths['/api/v1/capacity/resources/{id}'].put.responses['403']").exists())
        .andExpect(jsonPath("$.paths['/api/v1/capacity/resources'].get.responses['403']").exists());
  }

  @Test
  @DisplayName("BR-04 the 409 conflict contract is documented in the OpenAPI definition")
  void conflictContractIsDocumented() throws Exception {
    String reserve = "$.paths['/api/v1/capacity/reservations'].post.responses";

    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(jsonPath(reserve + "['201']").exists())
        .andExpect(jsonPath(reserve + "['400']").exists())
        .andExpect(jsonPath(reserve + "['409']").exists())
        .andExpect(jsonPath(reserve + "['409'].content.*.examples.capacityExceeded").exists())
        .andExpect(
            jsonPath("$.paths['/api/v1/capacity/reservations/{id}'].delete.responses['204']")
                .exists())
        .andExpect(
            jsonPath("$.paths['/api/v1/capacity/reservations/{id}'].delete.responses['404']")
                .exists());
  }
}
