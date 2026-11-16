package com.aerotrace.journey.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aerotrace.journey.base.AbstractApiTest;
import org.junit.jupiter.api.Test;

class OpenApiDocsTest extends AbstractApiTest {

  @Test
  void theDefinitionIsPublicAndDocuments401And403OnEveryProtectedOperation() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.info.title").value("AeroTrace Journey Service"))
        .andExpect(jsonPath("$.paths['/api/v1/bags'].post.responses['401']").exists())
        .andExpect(jsonPath("$.paths['/api/v1/bags'].post.responses['403']").exists())
        .andExpect(jsonPath("$.paths['/api/v1/bags'].post.responses['409']").exists())
        .andExpect(
            jsonPath("$.paths['/api/v1/bags/{bagTag}/journey'].get.responses['401']").exists())
        .andExpect(
            jsonPath("$.paths['/api/v1/bags/{bagTag}/journey'].get.responses['403']").exists())
        .andExpect(jsonPath("$.paths['/api/v1/scans'].post.responses['202']").exists())
        .andExpect(jsonPath("$.paths['/api/v1/scans'].post.responses['401']").exists())
        .andExpect(jsonPath("$.paths['/api/v1/scans'].post.responses['403']").exists());
  }
}
