package com.aerotrace.recovery.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aerotrace.recovery.base.AbstractRecoveryTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocsTest extends AbstractRecoveryTest {

  @Autowired MockMvc mockMvc;

  @Test
  @DisplayName("AT-16 the definition is public and documents 401 and 403 on every operation")
  void everyOperationDocuments401And403() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.info.title").value("AeroTrace Recovery Service"))
        .andExpect(jsonPath("$.paths['/api/v1/recovery-cases'].post.responses['401']").exists())
        .andExpect(jsonPath("$.paths['/api/v1/recovery-cases'].post.responses['403']").exists())
        .andExpect(jsonPath("$.paths['/api/v1/recovery-cases'].get.responses['401']").exists())
        .andExpect(jsonPath("$.paths['/api/v1/recovery-cases'].get.responses['403']").exists())
        .andExpect(
            jsonPath("$.paths['/api/v1/recovery-cases/{id}/transitions'].patch.responses['401']")
                .exists())
        .andExpect(
            jsonPath("$.paths['/api/v1/recovery-cases/{id}/transitions'].patch.responses['403']")
                .exists())
        .andExpect(jsonPath("$.paths['/api/v1/recovery-policy'].get.responses['403']").exists())
        .andExpect(jsonPath("$.paths['/api/v1/recovery-policy'].put.responses['403']").exists());
  }

  @Test
  @DisplayName(
      "BR-05 the transition documents its success, validation, conflict and capacity answers")
  void transitionDocumentsItsAnswers() throws Exception {
    String transition = "$.paths['/api/v1/recovery-cases/{id}/transitions'].patch.responses";

    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(jsonPath(transition + "['200']").exists())
        .andExpect(jsonPath(transition + "['400']").exists())
        .andExpect(jsonPath(transition + "['404']").exists())
        .andExpect(jsonPath(transition + "['409']").exists())
        .andExpect(jsonPath(transition + "['503']").exists())
        .andExpect(jsonPath(transition + "['409'].content.*.examples.illegalTransition").exists());
  }

  @Test
  @DisplayName("BR-01 opening a case documents the duplicate answer with its example")
  void createDocumentsTheDuplicateAnswer() throws Exception {
    String create = "$.paths['/api/v1/recovery-cases'].post.responses";

    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(jsonPath(create + "['201']").exists())
        .andExpect(jsonPath(create + "['400']").exists())
        .andExpect(jsonPath(create + "['409']").exists())
        .andExpect(jsonPath("$.components.schemas.ApiError").exists())
        .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"));
  }
}
