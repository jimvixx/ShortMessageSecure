/*
 * Copyright (C) 2025 Jimvixx
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.jimvixx.smsecure.logging;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.whispersystems.jobqueue.logging.JobQueueLogger;

public final class JobQueueLoggerBridge implements JobQueueLogger {

  @Override
  public int log(int priority,
                 @NonNull String tag,
                 @NonNull String message,
                 @Nullable Throwable throwable) {
    return switch (priority) {
      case org.jimvixx.smsecure.logging.Log.VERBOSE ->
              throwable != null
                      ? org.jimvixx.smsecure.logging.Log.v(tag, message, throwable)
                      : org.jimvixx.smsecure.logging.Log.v(tag, message);

      case org.jimvixx.smsecure.logging.Log.DEBUG ->
              throwable != null
                      ? org.jimvixx.smsecure.logging.Log.d(tag, message, throwable)
                      : org.jimvixx.smsecure.logging.Log.d(tag, message);

      case org.jimvixx.smsecure.logging.Log.INFO ->
              throwable != null
                      ? org.jimvixx.smsecure.logging.Log.i(tag, message, throwable)
                      : org.jimvixx.smsecure.logging.Log.i(tag, message);

      case org.jimvixx.smsecure.logging.Log.ERROR,
           org.jimvixx.smsecure.logging.Log.ASSERT ->
              throwable != null
                      ? org.jimvixx.smsecure.logging.Log.e(tag, message, throwable)
                      : org.jimvixx.smsecure.logging.Log.e(tag, message);

      case org.jimvixx.smsecure.logging.Log.WARN ->
              throwable != null
                      ? org.jimvixx.smsecure.logging.Log.w(tag, message, throwable)
                      : org.jimvixx.smsecure.logging.Log.w(tag, message);

      default ->
              throwable != null
                      ? org.jimvixx.smsecure.logging.Log.w(tag, message, throwable)
                      : org.jimvixx.smsecure.logging.Log.println(priority, tag, message);
    };
  }

  @Override
  public boolean isLoggable(@NonNull String tag, int priority) {
    return org.jimvixx.smsecure.logging.Log.isLoggable(tag, priority);
  }
}
