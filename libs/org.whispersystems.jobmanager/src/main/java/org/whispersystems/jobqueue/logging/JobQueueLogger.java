package org.whispersystems.jobqueue.logging;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public interface JobQueueLogger {
  int log(int priority, @NonNull String tag, @NonNull String message, @Nullable Throwable throwable);
  boolean isLoggable(@NonNull String tag, int priority);
}
