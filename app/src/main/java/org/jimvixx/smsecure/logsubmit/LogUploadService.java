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

import org.jimvixx.smsecure.logging.Log;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class LogUploadService {

  private static final String TAG = LogUploadService.class.getSimpleName();

  private static final int CONNECT_TIMEOUT_MS = 15_000;
  private static final int READ_TIMEOUT_MS = 30_000;
  private static final int MAX_ERROR_BODY_LENGTH = 400;

  private static final String USER_AGENT = "SMSecure Log Submitter";
  private static final String CLIENT_NAME = "SMSecure";
  private static final String SERVICE_NAME = "Cloudflare R2";

  private static final String ENDPOINT =
          "https://log-upload-worker.jimvixx-log-upload.workers.dev";

  private LogUploadService() {
  }

  @NonNull
  public static LogUploadResult upload(@NonNull String text) {
    try {
      byte[] zipPayload = zipLogs(text);

      HttpResponse response = executePost(
              ENDPOINT,
              "application/zip",
              "application/json",
              zipPayload
      );

      if (response.code >= 200 && response.code < 300) {
        return parseSuccessResponse(response.body);
      }

      return LogUploadResult.error(formatHttpError(response));

    } catch (Exception e) {
      return LogUploadResult.error(formatException(e));
    }
  }

  @NonNull
  private static LogUploadResult parseSuccessResponse(@NonNull String body) throws Exception {
    String cleanBody = body.trim();

    if (cleanBody.isEmpty()) {
      return LogUploadResult.error("Cloudflare upload succeeded but returned an empty response");
    }

    JSONObject json = new JSONObject(cleanBody);

    String id = json.optString("id", "").trim();
    String key = json.optString("key", "").trim();
    long size = json.optLong("size", 0);

    if (id.isEmpty()) {
      return LogUploadResult.error("Cloudflare upload response is missing report id");
    }

    if (key.isEmpty()) {
      return LogUploadResult.error("Cloudflare upload response is missing object key");
    }

    return LogUploadResult.success(SERVICE_NAME, id, key, size);
  }

  @NonNull
  private static byte[] zipLogs(@NonNull String text) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();

    try (ZipOutputStream zos = new ZipOutputStream(bos)) {
      ZipEntry entry = new ZipEntry("smsecure-logcat.txt");
      zos.putNextEntry(entry);
      zos.write(text.getBytes(StandardCharsets.UTF_8));
      zos.closeEntry();
    }

    return bos.toByteArray();
  }

  @NonNull
  private static HttpResponse executePost(@NonNull String endpoint,
                                          @NonNull String contentType,
                                          @NonNull String accept,
                                          @NonNull byte[] body) throws IOException {
    HttpURLConnection conn = null;

    try {
      Log.d(TAG, "HTTP POST " + endpoint + ", payloadBytes=" + body.length);

      conn = (HttpURLConnection) new URL(endpoint).openConnection();
      conn.setRequestMethod("POST");
      conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
      conn.setReadTimeout(READ_TIMEOUT_MS);
      conn.setUseCaches(false);
      conn.setDoOutput(true);
      conn.setFixedLengthStreamingMode(body.length);

      conn.setRequestProperty("User-Agent", USER_AGENT);
      conn.setRequestProperty("Accept", accept);
      conn.setRequestProperty("Content-Type", contentType);
      conn.setRequestProperty("X-Client", CLIENT_NAME);

      try (OutputStream os = conn.getOutputStream()) {
        os.write(body);
      }

      int code = conn.getResponseCode();
      String responseBody = readBody(conn, code);

      Log.d(TAG, "Response code=" + code + ", bodyLength=" + responseBody.length());

      return new HttpResponse(code, endpoint, responseBody);

    } finally {
      if (conn != null) {
        conn.disconnect();
      }
    }
  }

  @NonNull
  private static String readBody(@NonNull HttpURLConnection conn, int code) throws IOException {
    InputStream is = (code >= 200 && code < 300)
            ? conn.getInputStream()
            : conn.getErrorStream();

    if (is == null) return "";

    try (InputStream in = is; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
      byte[] buf = new byte[4096];
      int r;

      while ((r = in.read(buf)) != -1) {
        bos.write(buf, 0, r);
      }

      return bos.toString(StandardCharsets.UTF_8.name());
    }
  }

  @NonNull
  private static String formatHttpError(@NonNull HttpResponse response) {
    String body = response.body.trim();
    if (body.length() > MAX_ERROR_BODY_LENGTH) {
      body = body.substring(0, MAX_ERROR_BODY_LENGTH) + "...";
    }

    return "HTTP " + response.code + (body.isEmpty() ? "" : ": " + body);
  }

  @NonNull
  private static String formatException(@NonNull Exception e) {
    String msg = e.getMessage();
    return e.getClass().getSimpleName()
            + ((msg == null || msg.trim().isEmpty()) ? "" : ": " + msg);
  }

  private static final class HttpResponse {
    final int code;

    @NonNull
    final String finalUrl;

    @NonNull
    final String body;

    HttpResponse(int code, @NonNull String finalUrl, @Nullable String body) {
      this.code = code;
      this.finalUrl = finalUrl;
      this.body = body == null ? "" : body;
    }
  }
}