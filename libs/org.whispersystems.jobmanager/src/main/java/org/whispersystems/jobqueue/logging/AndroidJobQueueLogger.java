package org.whispersystems.jobqueue.logging;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class AndroidJobQueueLogger implements JobQueueLogger {

  @Override
  public int log(int priority, @NonNull String tag, @NonNull String message, @Nullable Throwable throwable) {
    if (throwable != null) {
      return android.util.Log.println(priority, tag, message + '\n' + android.util.Log.getStackTraceString(throwable));
    } else {
      return android.util.Log.println(priority, tag, message);
    }
  }

  @Override
  public boolean isLoggable(@NonNull String tag, int priority) {
    return android.util.Log.isLoggable(tag, priority);
  }
}
