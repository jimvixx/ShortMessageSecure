/*
 * Copyright (C) 2015 Open Whisper Systems
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

package org.jimvixx.smsecure.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal RFC5724-like parser for sms:/smsto: URIs used by Android intents.
 * <p>
 * This implementation is pure Java (no android.net.Uri) so it can be tested with local JVM unit
 * tests (testDebugUnitTest) without Robolectric.
 * <p>
 * Behavior notes (kept for legacy compatibility with existing tests):
 * - getSchema() returns the scheme with original case preserved (e.g. "sMs").
 * - getPath() returns the encoded path (e.g. "%2B1555" stays "%2B1555").
 * - getQueryParams() returns decoded values; "a=" maps to "" and missing keys are absent.
 * - isValid() requires supported scheme and a non-empty path (query-only URIs are invalid).
 */
public final class Rfc5724Uri {

  private final String raw;
  private final String schema;
  private final String pathEncoded;
  private final Map<String, String> queryParams;

  public Rfc5724Uri(@NonNull String raw) {
    this.raw = raw;

    Parsed parsed = parse(raw);
    this.schema = parsed.schema;
    this.pathEncoded = parsed.pathEncoded;
    this.queryParams = parsed.queryParams;
  }

  private static Parsed parse(@NonNull String raw) {
    // Expected format: <scheme>:<ssp>
    // ssp may contain: <path>[?<query>]
    int colon = raw.indexOf(':');
    if (colon <= 0 || colon == raw.length() - 1) {
      // No scheme or empty scheme-specific-part.
      return new Parsed(null, null, Collections.emptyMap());
    }

    String schema = raw.substring(0, colon);
    String ssp = raw.substring(colon + 1);

    String pathEncoded;
    String query;

    int q = ssp.indexOf('?');
    if (q >= 0) {
      pathEncoded = ssp.substring(0, q);
      query = (q < ssp.length() - 1) ? ssp.substring(q + 1) : "";
    } else {
      pathEncoded = ssp;
      query = "";
    }

    Map<String, String> params = parseQueryParams(query);

    // Treat query-only URI as invalid by forcing path to empty when it is empty.
    // (e.g. "sms:?goto=fail" should be invalid per existing tests)
    if (pathEncoded.isEmpty()) {
      return new Parsed(schema, "", params);
    }

    return new Parsed(schema, pathEncoded, params);
  }

  private static Map<String, String> parseQueryParams(@Nullable String query) {
    if (query == null || query.isEmpty()) return Collections.emptyMap();

    Map<String, String> out = new HashMap<>();

    String[] pairs = query.split("&");
    for (String pair : pairs) {
      if (pair.isEmpty()) continue;

      String[] parts = pair.split("=", 2);
      String key = parts[0];

      if (key.isEmpty()) continue;

      if (parts.length == 1) {
        // Key present without "=" means an empty value.
        out.put(key, "");
      } else {
        out.put(key, urlDecodeUtf8(parts[1]));
      }
    }

    return out;
  }

  private static String urlDecodeUtf8(@NonNull String s) {
    // RFC 3986 query decoding: '+' is often used for space in application/x-www-form-urlencoded.
    // Keep legacy behavior: treat '+' as space.
    String plusAsSpace = s.replace("+", " ");

    // Percent-decode manually to avoid deprecated URLDecoder.decode(String).
    byte[] bytes = new byte[plusAsSpace.length()];
    int outLen = 0;

    for (int i = 0; i < plusAsSpace.length(); i++) {
      char c = plusAsSpace.charAt(i);
      if (c == '%' && i + 2 < plusAsSpace.length()) {
        int hi = hex(plusAsSpace.charAt(i + 1));
        int lo = hex(plusAsSpace.charAt(i + 2));
        if (hi >= 0 && lo >= 0) {
          bytes[outLen++] = (byte) ((hi << 4) + lo);
          i += 2;
          continue;
        }
      }
      // Encode as UTF-8 bytes for non-percent characters.
      byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
      for (byte bb : b) {
        bytes[outLen++] = bb;
      }
    }

    return new String(bytes, 0, outLen, StandardCharsets.UTF_8);
  }

  private static int hex(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return 10 + (c - 'a');
    if (c >= 'A' && c <= 'F') return 10 + (c - 'A');
    return -1;
  }

  /**
   * Legacy API compatibility: older code/tests call this "schema".
   */
  public @Nullable String getSchema() {
    return schema;
  }

  /**
   * Returns the encoded recipient part (phone numbers) without query.
   */
  public @NonNull String getPath() {
    return pathEncoded != null ? pathEncoded : "";
  }

  /**
   * Returns decoded query parameters. Missing key → not present. Key with empty value → "".
   */
  public @NonNull Map<String, String> getQueryParams() {
    return queryParams;
  }

  /**
   * Validates whether the URI looks like a supported RFC5724-style sms URI for this app.
   * <p>
   * Rules (aligned with legacy tests):
   * - Scheme must be one of: sms, smsto, mms, mmsto (case-insensitive).
   * - Path must be non-empty.
   * - Query-only URIs like "sms:?a=b" are considered invalid.
   */
  public boolean isValid() {
    if (schema == null || schema.isEmpty()) return false;

    String lower = schema.toLowerCase(Locale.ROOT);
    if (!("sms".equals(lower) ||
            "smsto".equals(lower) ||
            "mms".equals(lower) ||
            "mmsto".equals(lower))) {
      return false;
    }

    return pathEncoded != null && !pathEncoded.isEmpty();
  }

  public @NonNull String getRaw() {
    return raw;
  }

  private static final class Parsed {
    final String schema;
    final String pathEncoded;
    final Map<String, String> queryParams;

    Parsed(String schema, String pathEncoded, Map<String, String> queryParams) {
      this.schema = schema;
      this.pathEncoded = pathEncoded;
      this.queryParams = queryParams;
    }
  }
}
