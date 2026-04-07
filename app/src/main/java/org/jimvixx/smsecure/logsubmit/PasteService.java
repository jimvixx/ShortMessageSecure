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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Uploads logs to paste services with fallbacks.
 *
 * Fallback chain:
 *  1) paste.rs
 *  2) dpaste
 *  3) paste.c-net.org
 *  4) transfer.sh
 *  5) 0x0.st
 */
public final class PasteService {

  private static final String TAG = PasteService.class.getSimpleName();

  private static final int CONNECT_TIMEOUT_MS = 15_000;
  private static final int READ_TIMEOUT_MS = 20_000;
  private static final int MAX_REDIRECTS = 5;
  private static final int MAX_ERROR_BODY_LENGTH = 400;

  private static final String USER_AGENT = "SMSecure Log Submitter";

  private static final String ENDPOINT_PASTE_RS = "https://paste.rs";
  private static final String ENDPOINT_DPASTE = "https://dpaste.org/api/";
  private static final String ENDPOINT_PASTE_CNET = "https://paste.c-net.org/";
  private static final String ENDPOINT_TRANSFER_SH = "https://transfer.sh/smsecure-logcat.txt";
  private static final String ENDPOINT_0X0 = "https://0x0.st";

  private PasteService() {}

  @NonNull
  public static PasteResult upload(@NonNull String text) {
    final String filename = "smsecure-logcat.txt";

    PasteResult pasteRsResult;
    PasteResult dpasteResult;
    PasteResult pasteCnetResult;
    PasteResult transferShResult;
    PasteResult zeroX0Result;

    Log.d(TAG, "Trying paste.rs...");
    pasteRsResult = uploadPasteRs(text);
    if (pasteRsResult.success) return pasteRsResult;
    Log.w(TAG, "paste.rs failed: " + safe(pasteRsResult.error));

    Log.d(TAG, "Trying dpaste...");
    dpasteResult = uploadDpaste(text);
    if (dpasteResult.success) return dpasteResult;
    Log.w(TAG, "dpaste failed: " + safe(dpasteResult.error));

    Log.d(TAG, "Trying paste.c-net.org...");
    pasteCnetResult = uploadPasteCNet(text, filename);
    if (pasteCnetResult.success) return pasteCnetResult;
    Log.w(TAG, "paste.c-net.org failed: " + safe(pasteCnetResult.error));

    Log.d(TAG, "Trying transfer.sh...");
    transferShResult = uploadTransferSh(text);
    if (transferShResult.success) return transferShResult;
    Log.w(TAG, "transfer.sh failed: " + safe(transferShResult.error));

    Log.d(TAG, "Trying 0x0.st...");
    zeroX0Result = upload0x0AsFile(text, filename);
    if (zeroX0Result.success) return zeroX0Result;
    Log.w(TAG, "0x0.st failed: " + safe(zeroX0Result.error));

    String err = "paste.rs failed: " + safe(pasteRsResult.error) + "\n"
            + "dpaste failed: " + safe(dpasteResult.error) + "\n"
            + "paste.c-net.org failed: " + safe(pasteCnetResult.error) + "\n"
            + "transfer.sh failed: " + safe(transferShResult.error) + "\n"
            + "0x0.st failed: " + safe(zeroX0Result.error);

    return PasteResult.error(err);
  }

  @NonNull
  private static PasteResult uploadPasteRs(@NonNull String text) {
    try {
      byte[] payload = text.getBytes(StandardCharsets.UTF_8);

      HttpResponse response = executeWithRedirects(
              ENDPOINT_PASTE_RS,
              "POST",
              "text/plain; charset=utf-8",
              "text/plain",
              payload
      );

      String body = response.body.trim();

      if (response.code == HttpURLConnection.HTTP_OK
              || response.code == HttpURLConnection.HTTP_CREATED) {
        String url = extractUrl(body, response.finalUrl);
        if (url.isEmpty()) {
          return PasteResult.error("Empty response URL");
        }
        return PasteResult.success("paste.rs", url);
      }

      return PasteResult.error(formatHttpError(response));

    } catch (Exception e) {
      return PasteResult.error(formatException(e));
    }
  }

  @NonNull
  private static PasteResult uploadDpaste(@NonNull String text) {
    try {
      String encoded = "content=" + URLEncoder.encode(text, StandardCharsets.UTF_8.name());
      byte[] payload = encoded.getBytes(StandardCharsets.UTF_8);

      HttpResponse response = executeWithRedirects(
              ENDPOINT_DPASTE,
              "POST",
              "application/x-www-form-urlencoded; charset=utf-8",
              "text/plain",
              payload
      );

      String body = response.body.trim();

      if (response.code == HttpURLConnection.HTTP_OK
              || response.code == HttpURLConnection.HTTP_CREATED) {
        String url = extractUrl(body, response.finalUrl);
        if (url.isEmpty()) {
          return PasteResult.error("Empty response URL");
        }
        return PasteResult.success("dpaste", url);
      }

      return PasteResult.error(formatHttpError(response));

    } catch (Exception e) {
      return PasteResult.error(formatException(e));
    }
  }

  @NonNull
  private static PasteResult uploadPasteCNet(@NonNull String text, @NonNull String filename) {
    try {
      byte[] fileBytes = text.getBytes(StandardCharsets.UTF_8);
      MultipartPayload multipart = buildMultipartPayload(
              "file",
              filename,
              "text/plain; charset=utf-8",
              fileBytes
      );

      HttpResponse response = executeWithRedirects(
              ENDPOINT_PASTE_CNET,
              "POST",
              "multipart/form-data; boundary=" + multipart.boundary,
              "text/plain",
              multipart.payload
      );

      String body = response.body.trim();

      if (response.code >= 200 && response.code < 300) {
        String url = extractUrl(body, response.finalUrl);
        if (url.isEmpty()) {
          return PasteResult.error("Empty response URL");
        }
        return PasteResult.success("paste.c-net.org", url);
      }

      return PasteResult.error(formatHttpError(response));

    } catch (Exception e) {
      return PasteResult.error(formatException(e));
    }
  }

  @NonNull
  private static PasteResult uploadTransferSh(@NonNull String text) {
    try {
      byte[] payload = text.getBytes(StandardCharsets.UTF_8);

      HttpResponse response = executeWithRedirects(
              ENDPOINT_TRANSFER_SH,
              "PUT",
              "text/plain; charset=utf-8",
              "text/plain",
              payload
      );

      String body = response.body.trim();

      if (response.code >= 200 && response.code < 300) {
        String url = extractUrl(body, response.finalUrl);
        if (url.isEmpty()) {
          return PasteResult.error("Empty response URL");
        }
        return PasteResult.success("transfer.sh", url);
      }

      return PasteResult.error(formatHttpError(response));

    } catch (Exception e) {
      return PasteResult.error(formatException(e));
    }
  }

  @NonNull
  private static PasteResult upload0x0AsFile(@NonNull String text, @NonNull String filename) {
    try {
      byte[] fileBytes = text.getBytes(StandardCharsets.UTF_8);
      MultipartPayload multipart = buildMultipartPayload(
              "file",
              filename,
              "text/plain; charset=utf-8",
              fileBytes
      );

      HttpResponse response = executeWithRedirects(
              ENDPOINT_0X0,
              "POST",
              "multipart/form-data; boundary=" + multipart.boundary,
              "text/plain",
              multipart.payload
      );

      String body = response.body.trim();

      if (response.code >= 200 && response.code < 300) {
        String url = extractUrl(body, response.finalUrl);
        if (url.isEmpty()) {
          return PasteResult.error("Empty response URL");
        }
        return PasteResult.success("0x0.st", url);
      }

      return PasteResult.error(formatHttpError(response));

    } catch (Exception e) {
      return PasteResult.error(formatException(e));
    }
  }

  @NonNull
  private static HttpResponse executeWithRedirects(@NonNull String initialUrl,
                                                   @NonNull String method,
                                                   @Nullable String contentType,
                                                   @Nullable String accept,
                                                   @Nullable byte[] body) throws IOException {
    String currentUrl = initialUrl;
    int redirects = 0;

    while (true) {
      HttpURLConnection conn = null;

      try {
        Log.d(TAG, "HTTP " + method + " " + currentUrl);

        conn = (HttpURLConnection) new URL(currentUrl).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod(method);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setUseCaches(false);

        conn.setRequestProperty("User-Agent", USER_AGENT);

        if (accept != null) {
          conn.setRequestProperty("Accept", accept);
        }

        if (contentType != null) {
          conn.setRequestProperty("Content-Type", contentType);
        }

        if (body != null) {
          conn.setDoOutput(true);
          conn.setFixedLengthStreamingMode(body.length);

          try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
          }
        }

        int code = conn.getResponseCode();

        if (isRedirect(code)) {
          String location = conn.getHeaderField("Location");
          String bodyText = readBody(conn, code).trim();

          Log.d(TAG, "Redirect " + code + ", location=" + location);

          if (location == null || location.trim().isEmpty()) {
            return new HttpResponse(code, currentUrl, bodyText);
          }

          URL resolved = new URL(new URL(currentUrl), location);
          currentUrl = resolved.toExternalForm();
          redirects++;

          if (redirects > MAX_REDIRECTS) {
            return new HttpResponse(code, currentUrl, "Too many redirects");
          }

          continue;
        }

        String responseBody = readBody(conn, code);
        Log.d(TAG, "Response code=" + code + ", bodyLength=" + responseBody.length());

        return new HttpResponse(code, currentUrl, responseBody);

      } finally {
        if (conn != null) {
          conn.disconnect();
        }
      }
    }
  }

  @NonNull
  private static MultipartPayload buildMultipartPayload(@NonNull String fieldName,
                                                        @NonNull String filename,
                                                        @NonNull String mimeType,
                                                        @NonNull byte[] fileBytes) {
    String boundary = "----SMSecureBoundary" + System.currentTimeMillis();
    String crlf = "\r\n";

    String partHeader =
            "--" + boundary + crlf +
                    "Content-Disposition: form-data; name=\"" + escapeQuotes(fieldName) + "\"; filename=\"" + escapeQuotes(filename) + "\"" + crlf +
                    "Content-Type: " + mimeType + crlf +
                    "Content-Transfer-Encoding: binary" + crlf +
                    crlf;

    String partFooter =
            crlf +
                    "--" + boundary + "--" + crlf;

    byte[] headerBytes = partHeader.getBytes(StandardCharsets.UTF_8);
    byte[] footerBytes = partFooter.getBytes(StandardCharsets.UTF_8);

    byte[] payload = new byte[headerBytes.length + fileBytes.length + footerBytes.length];

    System.arraycopy(headerBytes, 0, payload, 0, headerBytes.length);
    System.arraycopy(fileBytes, 0, payload, headerBytes.length, fileBytes.length);
    System.arraycopy(footerBytes, 0, payload, headerBytes.length + fileBytes.length, footerBytes.length);

    return new MultipartPayload(boundary, payload);
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

  private static boolean isRedirect(int code) {
    return code == HttpURLConnection.HTTP_MOVED_PERM
            || code == HttpURLConnection.HTTP_MOVED_TEMP
            || code == HttpURLConnection.HTTP_SEE_OTHER
            || code == 307
            || code == 308;
  }

  @NonNull
  private static String extractUrl(@Nullable String body, @Nullable String fallback) {
    String value = firstNonBlank(body, fallback);
    return value == null ? "" : value.trim();
  }

  @Nullable
  private static String firstNonBlank(@Nullable String a, @Nullable String b) {
    if (a != null && !a.trim().isEmpty()) return a.trim();
    if (b != null && !b.trim().isEmpty()) return b.trim();
    return null;
  }

  @NonNull
  private static String formatHttpError(@NonNull HttpResponse response) {
    String body = response.body.trim();
    if (body.length() > MAX_ERROR_BODY_LENGTH) {
      body = body.substring(0, MAX_ERROR_BODY_LENGTH) + "...";
    }

    String finalUrlPart = response.finalUrl.isEmpty()
            ? ""
            : " [finalUrl=" + response.finalUrl + "]";

    return "HTTP " + response.code + finalUrlPart + (body.isEmpty() ? "" : ": " + body);
  }

  @NonNull
  private static String formatException(@NonNull Exception e) {
    String msg = e.getMessage();
    return e.getClass().getSimpleName()
            + ((msg == null || msg.trim().isEmpty()) ? "" : ": " + msg);
  }

  @NonNull
  private static String escapeQuotes(@NonNull String s) {
    return s.replace("\"", "'");
  }

  @NonNull
  private static String safe(@Nullable String s) {
    return s == null ? "(no details)" : s;
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

  private static final class MultipartPayload {
    @NonNull
    final String boundary;

    @NonNull
    final byte[] payload;

    MultipartPayload(@NonNull String boundary, @NonNull byte[] payload) {
      this.boundary = boundary;
      this.payload = payload;
    }
  }
}