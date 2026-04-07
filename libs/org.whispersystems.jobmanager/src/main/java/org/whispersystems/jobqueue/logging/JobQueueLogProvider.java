package org.whispersystems.jobqueue.logging;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class JobQueueLogProvider {

  @NonNull
  private static volatile JobQueueLogger logger = new AndroidJobQueueLogger();

  private JobQueueLogProvider() {}

  public static void setLogger(@Nullable JobQueueLogger customLogger) {
    logger = customLogger != null ? customLogger : new AndroidJobQueueLogger();
  }

  @NonNull
  public static JobQueueLogger getLogger() {
    return logger;
  }
}
