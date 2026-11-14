package com.aerotrace.journey.web;

import com.aerotrace.common.api.ApiResponse;
import com.aerotrace.journey.constant.ApiMessages;
import com.aerotrace.journey.constant.ApiPaths;
import com.aerotrace.journey.constant.DocsConstants;
import com.aerotrace.journey.constant.JourneyRules;
import com.aerotrace.journey.docs.ApiExamples;
import com.aerotrace.journey.model.dto.request.RegisterBagRequest;
import com.aerotrace.journey.model.dto.response.JourneyResponse;
import com.aerotrace.journey.model.dto.response.RegisteredBagResponse;
import com.aerotrace.journey.service.BaggageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = DocsConstants.TAG_BAGS)
public class BaggageController {

  private final BaggageService service;

  public BaggageController(BaggageService service) {
    this.service = service;
  }

  @Operation(
      summary = "Register a bag with its itinerary",
      description =
          "Check-in agents and admins. The bag tag is a 10-digit IATA tag and can be registered"
              + " once. Legs must be chained, in order and respect the minimum connection time.",
      security = @SecurityRequirement(name = DocsConstants.BEARER_AUTH))
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "201",
        description = "Registered"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "A field or the itinerary breaks a rule"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        description = "The bag tag is already registered (BAG_TAG_DUPLICATE)",
        content =
            @Content(
                schema = @Schema(ref = DocsConstants.API_ERROR_REF),
                examples = @ExampleObject(value = ApiExamples.BAG_TAG_DUPLICATE)))
  })
  @PostMapping(ApiPaths.BAGS)
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<RegisteredBagResponse> register(@Valid @RequestBody RegisterBagRequest body) {
    return ApiResponse.success(service.register(body));
  }

  @Operation(
      summary = "The journey of a bag",
      description =
          "The latest accepted state and the immutable scan history. A passenger sees only their"
              + " own bag; any other bag answers 404 like an unknown one.",
      security = @SecurityRequirement(name = DocsConstants.BEARER_AUTH))
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "The journey"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "No such bag, or not the caller's bag",
        content =
            @Content(
                schema = @Schema(ref = DocsConstants.API_ERROR_REF),
                examples = @ExampleObject(value = ApiExamples.BAG_NOT_FOUND)))
  })
  @GetMapping(ApiPaths.JOURNEY)
  public ApiResponse<JourneyResponse> journey(
      @PathVariable("bagTag")
          @Pattern(regexp = JourneyRules.BAG_TAG_PATTERN, message = ApiMessages.BAG_TAG_RULE)
          String bagTag) {
    return ApiResponse.success(service.journey(bagTag));
  }
}
