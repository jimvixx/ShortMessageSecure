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

package org.jimvixx.smsecure.util;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import org.jimvixx.smsecure.BuildConfig;
import org.jimvixx.smsecure.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds HTML for the About screen from the localized Distribution_long_description string.
 * All URLs are injected from BuildConfig and rendered as clickable links.
 */
public final class AboutHtml {

  private AboutHtml() {
  }

  @NonNull
  public static String build(@NonNull Context context) {
    String issuesLink = link(BuildConfig.ISSUES_REQUESTS_URL, BuildConfig.ISSUES_REQUESTS_URL);
    String sourceLink = link(BuildConfig.SOURCE_CODE_URL, BuildConfig.SOURCE_CODE_URL);
    String detailsLink = link(BuildConfig.MORE_DETAILS_URL, BuildConfig.MORE_DETAILS_URL);
    String privacyLink = link(BuildConfig.PRIVACY_POLICY_URL, BuildConfig.PRIVACY_POLICY_URL);

    String raw = context.getString(R.string.Distribution_long_description,
            issuesLink, sourceLink, detailsLink, privacyLink);

    return wrapDocument(formatBlocks(raw));
  }

  @NonNull
  private static String link(@NonNull String url, @NonNull String label) {
    if (TextUtils.isEmpty(url)) return escape(label);
    return "<a href=\"" + escapeAttr(url) + "\">" + escape(">>>") + "</a>";
  }

  @NonNull
  private static String wrapDocument(@NonNull String bodyHtml) {
    return "<!doctype html><html><head><meta charset=\"utf-8\"/>" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>" +
            "<style>" +
            "html,body{" +
            "font-family:sans-serif;" +
            "line-height:1.35;" +
            "padding:16px;" +
            "-webkit-text-size-adjust:100%;" +
            "text-size-adjust:100%;" +
            "}" +
            "p{margin:0 0 12px 0;}" +
            "ul{margin:0 0 12px 18px;padding:0;}" +
            "li{margin:0 0 6px 0;}" +
            "a{text-decoration:none;}" +
            "</style>" +
            bodyHtml +
            "</body></html>";
  }

  /*
   * Converts plain text with blank lines + "* " bullets into HTML blocks.
   * Assumes the input may already contain <a> tags (from injected links).
   */
  @NonNull
  private static String formatBlocks(@NonNull String text) {
    String normalized = text.replace("\r\n", "\n").replace('\r', '\n');

    List<String> lines = splitLines(normalized);

    StringBuilder out = new StringBuilder();
    StringBuilder paragraph = new StringBuilder();
    boolean inList = false;

    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      String trimmed = line.trim();

      boolean blank = trimmed.isEmpty();
      boolean bullet = trimmed.startsWith("* ");

      if (blank) {
        flushParagraph(out, paragraph);
        if (inList) {
          out.append("</ul>");
          inList = false;
        }
        continue;
      }

      if (bullet) {
        flushParagraph(out, paragraph);
        if (!inList) {
          out.append("<ul>");
          inList = true;
        }
        String item = trimmed.substring(2).trim();
        out.append("<li>").append(escapePreservingLinks(item)).append("</li>");
        continue;
      }

      if (inList) {
        out.append("</ul>");
        inList = false;
      }

      if (paragraph.length() > 0) paragraph.append("<br/>");
      paragraph.append(escapePreservingLinks(trimmed));
    }

    flushParagraph(out, paragraph);
    if (inList) out.append("</ul>");

    return out.toString();
  }

  private static void flushParagraph(@NonNull StringBuilder out, @NonNull StringBuilder paragraph) {
    if (paragraph.length() == 0) return;
    out.append("<p>").append(paragraph).append("</p>");
    paragraph.setLength(0);
  }

  @NonNull
  private static List<String> splitLines(@NonNull String s) {
    List<String> lines = new ArrayList<>();
    int start = 0;
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) == '\n') {
        lines.add(s.substring(start, i));
        start = i + 1;
      }
    }
    lines.add(s.substring(start));
    return lines;
  }

  /*
   * Escapes text for HTML but keeps already-inserted <a ...>...</a> intact.
   * This is intentionally minimal: it assumes only <a> tags exist in the source.
   */
  @NonNull
  private static String escapePreservingLinks(@NonNull String s) {
    if (!s.contains("<a") && !s.contains("</a>")) return escape(s);

    StringBuilder out = new StringBuilder();
    int idx = 0;
    while (idx < s.length()) {
      int aStart = s.indexOf("<a", idx);
      if (aStart < 0) {
        out.append(escape(s.substring(idx)));
        break;
      }
      out.append(escape(s.substring(idx, aStart)));

      int aEnd = s.indexOf("</a>", aStart);
      if (aEnd < 0) {
        out.append(escape(s.substring(aStart)));
        break;
      }
      aEnd += "</a>".length();
      out.append(s, aStart, aEnd);
      idx = aEnd;
    }
    return out.toString();
  }

  @NonNull
  private static String escape(@NonNull String s) {
    return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
  }

  @NonNull
  private static String escapeAttr(@NonNull String s) {
    return escape(s);
  }
}
