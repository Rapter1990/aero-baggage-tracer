package com.aerotrace.riskstreams;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import java.nio.file.FileSystems;
import org.slf4j.Marker;

/**
 * Drops one log line that is noise on Windows: Kafka Streams' "Failed to change permissions for the
 * directory ..." (logged at ERROR by {@code StateDirectory}).
 *
 * <p>Kafka Streams restricts its state directory to its owner. On a POSIX file system it does so
 * with POSIX permissions. Elsewhere (Windows) it falls back to {@code File.setReadable}, {@code
 * setWritable} and {@code setExecutable} with "owner only", and on Windows the owner-only write
 * cannot be applied, so the call returns false and the line is logged. The directory is created and
 * fully usable; nothing is wrong and nothing can be fixed from our side.
 *
 * <p>The filter is deliberately narrow, so a real problem is never hidden:
 *
 * <ul>
 *   <li>only that logger and only messages that start with that text;
 *   <li>only on a file system without POSIX permissions: on Linux, where the POSIX branch is used,
 *       a permission failure is a real error and is logged as usual (with a different message that
 *       this filter does not touch).
 * </ul>
 *
 * <p>Registered in {@code logback-spring.xml} (the service) and {@code logback-test.xml} (tests).
 */
public class StateDirectoryPermissionNoiseFilter extends TurboFilter {

  static final String LOGGER_NAME = "org.apache.kafka.streams.processor.internals.StateDirectory";
  static final String MESSAGE_START = "Failed to change permissions for the directory";

  private final boolean posixFileSystem;

  /** Used by Logback: detects the file system of this machine. */
  public StateDirectoryPermissionNoiseFilter() {
    this(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
  }

  StateDirectoryPermissionNoiseFilter(boolean posixFileSystem) {
    this.posixFileSystem = posixFileSystem;
  }

  @Override
  public FilterReply decide(
      Marker marker, Logger logger, Level level, String format, Object[] params, Throwable t) {
    if (!posixFileSystem
        && format != null
        && format.startsWith(MESSAGE_START)
        && LOGGER_NAME.equals(logger.getName())) {
      return FilterReply.DENY;
    }
    return FilterReply.NEUTRAL;
  }
}
