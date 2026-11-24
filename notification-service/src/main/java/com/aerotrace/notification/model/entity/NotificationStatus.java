package com.aerotrace.notification.model.entity;

/** Delivery state of a notification. */
public enum NotificationStatus {
  /** Not sent yet, also while it is retried or claimed by a replica. */
  PENDING,
  /** The provider accepted it. */
  SENT,
  /** Every attempt was used; it is dead-lettered once Kafka acknowledges. */
  FAILED
}
