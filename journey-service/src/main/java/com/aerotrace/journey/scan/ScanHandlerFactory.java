package com.aerotrace.journey.scan;

import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.journey.constant.ApiMessages;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Factory Method for the handler of a protocol and format version. The handlers register themselves
 * (every {@link ScanHandler} bean), so supporting a new device or a new version means adding a
 * class and nothing here.
 */
@Component
public class ScanHandlerFactory {

  private record Key(ScanProtocol protocol, int version) {}

  private final Map<Key, ScanHandler> handlers;

  public ScanHandlerFactory(List<ScanHandler> handlers) {
    this.handlers =
        handlers.stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    h -> new Key(h.protocol(), h.version()), Function.identity()));
  }

  /** The handler for this protocol and version, or a 400 when there is none. */
  public ScanHandler create(ScanProtocol protocol, int version) {
    ScanHandler handler = handlers.get(new Key(protocol, version));
    if (handler == null) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, ApiMessages.SCAN_VERSION_UNSUPPORTED);
    }
    return handler;
  }
}
