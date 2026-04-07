package org.whispersystems.jobqueue.logging;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class Log {

  public static final int VERBOSE = android.util.Log.VERBOSE;
  public static final int DEBUG   = android.util.Log.DEBUG;
  public static final int INFO    = android.util.Log.INFO;
  public static final int WARN    = android.util.Log.WARN;
  public static final int ERROR   = android.util.Log.ERROR;
  public static final int ASSERT  = android.util.Log.ASSERT;

  private static final String DEFAULT_TAG = "jobqueue";
  private static final String PREFIX = "[jobqueue] ";

  private Log() {}

  @NonNull
  private static JobQueueLogger logger() {
    return JobQueueLogProvider.getLogger();
  }

  @NonNull
  private static String safeTag(@Nullable String tag) {
    if (tag == null || tag.trim().isEmpty()) {
      return DEFAULT_TAG;
    }
    return "jobqueue/" + tag;
  }

  @NonNull
  private static String safeMessage(@Nullable String message) {
    return PREFIX + (message == null ? "" : message);
  }

  public static int v(@NonNull String tag, @NonNull String msg) {
    return logger().log(VERBOSE, safeTag(tag), safeMessage(msg), null);
  }

  public static int v(@NonNull String tag, @NonNull String msg, @Nullable Throwable tr) {
    return logger().log(VERBOSE, safeTag(tag), safeMessage(msg), tr);
  }

  public static int d(@NonNull String tag, @NonNull String msg) {
    return logger().log(DEBUG, safeTag(tag), safeMessage(msg), null);
  }

  public static int d(@NonNull String tag, @NonNull String msg, @Nullable Throwable tr) {
    return logger().log(DEBUG, safeTag(tag), safeMessage(msg), tr);
  }

  public static int i(@NonNull String tag, @NonNull String msg) {
    return logger().log(INFO, safeTag(tag), safeMessage(msg), null);
  }

  public static int i(@NonNull String tag, @NonNull String msg, @Nullable Throwable tr) {
    return logger().log(INFO, safeTag(tag), safeMessage(msg), tr);
  }

  public static int w(@NonNull String tag, @NonNull String msg) {
    return logger().log(WARN, safeTag(tag), safeMessage(msg), null);
  }

  public static int w(@NonNull String tag, @Nullable Throwable tr) {
    return logger().log(WARN, safeTag(tag), safeMessage(""), tr);
  }

  public static int w(@NonNull String tag, @NonNull String msg, @Nullable Throwable tr) {
    return logger().log(WARN, safeTag(tag), safeMessage(msg), tr);
  }

  public static int e(@NonNull String tag, @NonNull String msg) {
    return logger().log(ERROR, safeTag(tag), safeMessage(msg), null);
  }

  public static int e(@NonNull String tag, @NonNull String msg, @Nullable Throwable tr) {
    return logger().log(ERROR, safeTag(tag), safeMessage(msg), tr);
  }

  public static int println(int priority, @NonNull String tag, @NonNull String msg) {
    return logger().log(priority, safeTag(tag), safeMessage(msg), null);
  }

  public static boolean isLoggable(@NonNull String tag, int priority) {
    return logger().isLoggable(safeTag(tag), priority);
  }

  @NonNull
  public static String getStackTraceString(@Nullable Throwable tr) {
    return android.util.Log.getStackTraceString(tr);
  }
}
