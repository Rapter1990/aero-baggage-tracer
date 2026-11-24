package com.aerotrace.notification.service;

import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.notification.constant.ErrorMessages;
import com.aerotrace.notification.model.dto.response.NotificationResponse;
import com.aerotrace.notification.model.dto.response.PageResponse;
import com.aerotrace.notification.repository.NotificationRepository;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reads of the API (ADR 0005, decision 13). Ownership is part of every query: a user only ever
 * sees rows whose recipient is their token subject, and someone else's id is not found.
 */
@Service
public class NotificationQueryService {

  /** Newest first, the id as tie-breaker, matching the listing index. */
  private static final Sort NEWEST_FIRST =
      Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

  private final NotificationRepository notifications;

  public NotificationQueryService(NotificationRepository notifications) {
    this.notifications = notifications;
  }

  @Transactional(readOnly = true)
  public PageResponse<NotificationResponse> list(String recipient, int page, int size) {
    return PageResponse.of(
        notifications.findByRecipient(recipient, PageRequest.of(page, size, NEWEST_FIRST)),
        NotificationResponse::of);
  }

  @Transactional(readOnly = true)
  public NotificationResponse get(UUID id, String recipient) {
    return notifications
        .findByIdAndRecipient(id, recipient)
        .map(NotificationResponse::of)
        .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, ErrorMessages.NOT_FOUND));
  }
}
