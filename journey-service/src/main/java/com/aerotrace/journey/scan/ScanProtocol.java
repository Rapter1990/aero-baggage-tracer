package com.aerotrace.journey.scan;

/** The kinds of device that report scans. Each has its own payload format and handler. */
public enum ScanProtocol {
  BARCODE,
  RFID,
  CONVEYOR
}
