/*
 * Copyright (C) 2026 Jimvixx
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

import org.jimvixx.smsecure.logging.Log;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class LogMessageSubmissionService {

  private static final String TAG = LogMessageSubmissionService.class.getSimpleName();

  private static final int CONNECT_TIMEOUT_MS = 15_000;
  private static final int READ_TIMEOUT_MS = 30_000;
  private static final String USER_AGENT = "SMSecure Log Submitter";
  private static final String CLIENT_NAME = "SMSecure";
  private static final String ENDPOINT =
          "https://log-upload-worker.jimvixx-log-upload.workers.dev/messages";

  private LogMessageSubmissionService() {
  }

  @NonNull
  public static Result submit(@NonNull String reportId,
                              @NonNull String objectKey,
                              @NonNull String token,
                              @NonNull String message) {
    try {
      byte[] body = requestBody(reportId, objectKey, token, message)
              .getBytes(StandardCharsets.UTF_8);
      HttpResponse response = executePost(body);

      if (response.code >= 200 && response.code < 300) {
        return Result.success();
      }

      String error = formatHttpError(response);
      Log.w(TAG, error);
      return Result.error(error, true);
    } catch (Exception e) {
      Log.w(TAG, e);
      return Result.error(formatException(e), false);
    }
  }

  @NonNull
  static String requestBody(@NonNull String reportId,
                            @NonNull String objectKey,
                            @NonNull String token,
                            @NonNull String message) throws Exception {
    return new JSONObject()
            .put("reportId", reportId)
            .put("objectKey", objectKey)
            .put("token", token)
            .put("message", message)
            .toString();
  }

  @NonNull
  private static HttpResponse executePost(@NonNull byte[] body) throws IOException {
    HttpURLConnection conn = null;

    try {
      Log.d(TAG, "HTTP POST /messages, payloadBytes=" + body.length);

      conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
      conn.setRequestMethod("POST");
      conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
      conn.setReadTimeout(READ_TIMEOUT_MS);
      conn.setUseCaches(false);
      conn.setDoOutput(true);
      conn.setFixedLengthStreamingMode(body.length);
      conn.setRequestProperty("User-Agent", USER_AGENT);
      conn.setRequestProperty("Accept", "application/json");
      conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
      conn.setRequestProperty("X-Client", CLIENT_NAME);

      try (OutputStream os = conn.getOutputStream()) {
        os.write(body);
      }

      int code = conn.getResponseCode();
      return new HttpResponse(code);
    } finally {
      if (conn != null) {
        conn.disconnect();
      }
    }
  }

  @NonNull
  private static String formatHttpError(@NonNull HttpResponse response) {
    return "HTTP " + response.code;
  }

  @NonNull
  private static String formatException(@NonNull Exception e) {
    String msg = e.getMessage();
    return e.getClass().getSimpleName()
            + ((msg == null || msg.trim().isEmpty()) ? "" : ": " + msg);
  }

  public static final class Result {
    public final boolean success;
    public final boolean serverError;

    @Nullable
    public final String error;

    private Result(boolean success, boolean serverError, @Nullable String error) {
      this.success = success;
      this.serverError = serverError;
      this.error = error;
    }

    @NonNull
    static Result success() {
      return new Result(true, false, null);
    }

    @NonNull
    static Result error(@NonNull String error, boolean serverError) {
      return new Result(false, serverError, error);
    }
  }

  private static final class HttpResponse {
    final int code;

    HttpResponse(int code) {
      this.code = code;
    }
  }
}
