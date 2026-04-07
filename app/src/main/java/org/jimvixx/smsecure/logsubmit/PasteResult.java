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

public final class PasteResult {

  public final boolean success;

  @Nullable
  public final String url;

  @Nullable
  public final String service;

  @Nullable
  public final String error;

  @NonNull
  public final String message;

  private PasteResult(boolean success,
                      @Nullable String url,
                      @Nullable String service,
                      @Nullable String error,
                      @NonNull String message) {
    this.success = success;
    this.url = url;
    this.service = service;
    this.error = error;
    this.message = message;
  }

  @NonNull
  public static PasteResult success(@NonNull String service, @NonNull String url) {
    String cleanService = service.trim();
    String cleanUrl = url.trim();

    String message = "Uploaded via: " + cleanService + "\n"
            + "URL: " + cleanUrl;

    return new PasteResult(true, cleanUrl, cleanService, null, message);
  }

  @NonNull
  public static PasteResult error(@NonNull String error) {
    return new PasteResult(false, null, null, error, error);
  }
}