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

package org.jimvixx.smsecure.logsubmit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class LogUploadResult {

  public final boolean success;

  @Nullable
  public final String reportId;

  @Nullable
  public final String objectKey;

  public final long size;

  @Nullable
  public final String service;

  @Nullable
  public final String error;

  @NonNull
  public final String message;

  private LogUploadResult(boolean success,
                          @Nullable String reportId,
                          @Nullable String objectKey,
                          long size,
                          @Nullable String service,
                          @Nullable String error,
                          @NonNull String message) {
    this.success = success;
    this.reportId = reportId;
    this.objectKey = objectKey;
    this.size = size;
    this.service = service;
    this.error = error;
    this.message = message;
  }

  @NonNull
  public static LogUploadResult success(@NonNull String service,
                                        @NonNull String reportId,
                                        @NonNull String objectKey,
                                        long size) {
    String cleanService = service.trim();
    String cleanReportId = reportId.trim();
    String cleanObjectKey = objectKey.trim();

    String message = "Uploaded via: " + cleanService + "\n\n"
            + "Report ID:\n" + cleanReportId + "\n\n"
            + "Object key:\n" + cleanObjectKey + "\n\n"
            + "Size:\n" + size + " bytes";

    return new LogUploadResult(true, cleanReportId, cleanObjectKey, size, cleanService, null, message);
  }

  @NonNull
  public static LogUploadResult error(@NonNull String error) {
    return new LogUploadResult(false, null, null, 0, null, error, error);
  }
}