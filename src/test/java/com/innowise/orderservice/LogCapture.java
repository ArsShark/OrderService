package com.innowise.orderservice;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

/**
 * Collects the text of every log event while it is attached: the message and the exception, if
 * there is one.
 *
 * <p>It is attached to the logger configuration that serves the application, which here is the
 * root one, so it sees the events of every library too. Use it in a try block and call
 * {@link #close()} in the {@code finally} part.
 */
public final class LogCapture implements AutoCloseable {

  private static final String NAME = "log-capture-of-a-test";

  private final Appender appender;
  private final LoggerContext context;
  private final LoggerConfig target;

  private LogCapture(Appender appender, LoggerContext context, LoggerConfig target) {
    this.appender = appender;
    this.context = context;
    this.target = target;
  }

  /**
   * Starts to collect log events.
   *
   * @return the running capture
   */
  public static LogCapture attach() {
    LoggerContext context = (LoggerContext) LogManager.getContext(false);
    LoggerConfig target =
        context.getConfiguration().getLoggerConfig("com.innowise.orderservice");
    Appender appender = new Appender();
    appender.start();
    target.addAppender(appender, Level.ALL, null);
    context.updateLoggers();
    return new LogCapture(appender, context, target);
  }

  /**
   * Returns what has been logged since {@link #attach()}.
   *
   * @return one entry per event
   */
  public List<String> lines() {
    return List.copyOf(appender.lines);
  }

  /**
   * Tells whether any collected event contains the text.
   *
   * @param text a part of a log line
   * @return {@code true} if one does
   */
  public boolean contains(String text) {
    return appender.lines.stream().anyMatch(line -> line.contains(text));
  }

  /** Stops the collection and removes the appender from the logger. */
  @Override
  public void close() {
    target.removeAppender(NAME);
    context.updateLoggers();
    appender.stop();
  }

  private static final class Appender extends AbstractAppender {

    private final List<String> lines = new CopyOnWriteArrayList<>();

    private Appender() {
      super(NAME, null, null, true, Property.EMPTY_ARRAY);
    }

    @Override
    public void append(LogEvent event) {
      Throwable thrown = event.getThrown();
      lines.add(String.format("%s %s", event.getMessage().getFormattedMessage(),
          thrown == null ? "" : thrown));
    }
  }
}
