package com.aerotrace.riskstreams;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.spi.FilterReply;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StateDirectoryPermissionNoiseFilterTest {

  private static final String STATE_DIRECTORY = StateDirectoryPermissionNoiseFilter.LOGGER_NAME;
  private static final String NOISE = "Failed to change permissions for the directory {}";

  private final LoggerContext context = new LoggerContext();

  private FilterReply decide(
      StateDirectoryPermissionNoiseFilter filter, String loggerName, String format) {
    Logger logger = context.getLogger(loggerName);
    return filter.decide(null, logger, Level.ERROR, format, new Object[] {"C:\\Temp\\x"}, null);
  }

  @Test
  @DisplayName(
      "on a file system without POSIX permissions (Windows) the one noisy message is dropped")
  void dropsTheNoiseOnWindows() {
    var windows = new StateDirectoryPermissionNoiseFilter(false);

    assertThat(decide(windows, STATE_DIRECTORY, NOISE)).isEqualTo(FilterReply.DENY);
  }

  @Test
  @DisplayName("on a POSIX file system the same message is a real error and is kept")
  void keepsItOnLinux() {
    var linux = new StateDirectoryPermissionNoiseFilter(true);

    assertThat(decide(linux, STATE_DIRECTORY, NOISE)).isEqualTo(FilterReply.NEUTRAL);
  }

  @Test
  @DisplayName(
      "other messages of that logger are never hidden, nor the same text from another logger")
  void hidesNothingElse() {
    var windows = new StateDirectoryPermissionNoiseFilter(false);

    assertThat(
            decide(
                windows, STATE_DIRECTORY, "Error changing permissions for the state directory {}"))
        .isEqualTo(FilterReply.NEUTRAL);
    assertThat(
            decide(
                windows,
                STATE_DIRECTORY,
                "Unable to obtain lock as state directory is already locked"))
        .isEqualTo(FilterReply.NEUTRAL);
    assertThat(decide(windows, "com.aerotrace.riskstreams.Other", NOISE))
        .isEqualTo(FilterReply.NEUTRAL);
    assertThat(decide(windows, STATE_DIRECTORY, null)).isEqualTo(FilterReply.NEUTRAL);
  }

  @Test
  @DisplayName("through Logback itself: the noise never reaches an appender, other lines do")
  void worksInsideLogback() {
    context.addTurboFilter(new StateDirectoryPermissionNoiseFilter(false));
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.setContext(context);
    appender.start();
    Logger kafka = context.getLogger(STATE_DIRECTORY);
    kafka.addAppender(appender);

    kafka.error(NOISE, "C:\\Temp\\risk-streams");
    kafka.error("Error changing permissions for the state directory {}", "C:\\Temp\\risk-streams");

    assertThat(appender.list).hasSize(1);
    assertThat(appender.list.getFirst().getMessage()).startsWith("Error changing permissions");
  }
}
