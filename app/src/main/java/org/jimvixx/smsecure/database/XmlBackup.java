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

package org.jimvixx.smsecure.database;

import android.text.TextUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class XmlBackup {

  private static final String PROTOCOL = "protocol";
  private static final String ADDRESS = "address";
  private static final String DATE = "date";
  private static final String TYPE = "type";
  private static final String SUBJECT = "subject";
  private static final String BODY = "body";
  private static final String SERVICE_CENTER = "service_center";
  private static final String READ = "read";
  private static final String STATUS = "status";
  private static final String TOA = "toa";
  private static final String SC_TOA = "sc_toa";
  private static final String LOCKED = "locked";

  private final XmlPullParser parser;

  public XmlBackup(String path) throws XmlPullParserException, FileNotFoundException {
    this.parser = XmlPullParserFactory.newInstance().newPullParser();
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
    parser.setInput(new FileInputStream(path), null);
  }

  /// SAF-friendly constructor (e.g., ContentResolver.openInputStream(uri)).
  /// Caller owns the stream lifetime.
  public XmlBackup(InputStream is) throws XmlPullParserException {
    this.parser = XmlPullParserFactory.newInstance().newPullParser();
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
    parser.setInput(is, null);
  }

  public XmlBackupItem getNext() throws IOException, XmlPullParserException {
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
      if (parser.getEventType() != XmlPullParser.START_TAG) {
        continue;
      }

      String name = parser.getName();

      if (!name.equalsIgnoreCase("sms")) {
        continue;
      }

      int attributeCount = parser.getAttributeCount();

      if (attributeCount <= 0) {
        continue;
      }

      XmlBackupItem item = new XmlBackupItem();

      for (int i = 0; i < attributeCount; i++) {
        String attributeName = parser.getAttributeName(i);

        switch (attributeName) {
          case PROTOCOL -> item.protocol = Integer.parseInt(parser.getAttributeValue(i));
          case ADDRESS -> item.address = parser.getAttributeValue(i);
          case DATE -> item.date = Long.parseLong(parser.getAttributeValue(i));
          case TYPE -> item.type = Integer.parseInt(parser.getAttributeValue(i));
          case SUBJECT -> item.subject = parser.getAttributeValue(i);
          case BODY -> item.body = parser.getAttributeValue(i);
          case SERVICE_CENTER -> item.serviceCenter = parser.getAttributeValue(i);
          case READ -> item.read = Integer.parseInt(parser.getAttributeValue(i));
          case STATUS -> item.status = Integer.parseInt(parser.getAttributeValue(i));
        }
      }

      return item;
    }

    return null;
  }

  public static class XmlBackupItem {
    private int protocol;
    private String address;
    private long date;
    private int type;
    private String subject;
    private String body;
    private String serviceCenter;
    private int read;
    private int status;

    public XmlBackupItem() {
    }

    public XmlBackupItem(int protocol, String address, long date, int type, String subject,
                         String body, String serviceCenter, int read, int status) {
      this.protocol = protocol;
      this.address = address;
      this.date = date;
      this.type = type;
      this.subject = subject;
      this.body = body;
      this.serviceCenter = serviceCenter;
      this.read = read;
      this.status = status;
    }

    public int getProtocol() {
      return protocol;
    }

    public String getAddress() {
      return address;
    }

    public long getDate() {
      return date;
    }

    public int getType() {
      return type;
    }

    public String getSubject() {
      return subject;
    }

    public String getBody() {
      return body;
    }

    public String getServiceCenter() {
      return serviceCenter;
    }

    public int getRead() {
      return read;
    }

    public int getStatus() {
      return status;
    }
  }

  public static class Writer {

    private static final String XML_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\" ?>";
    private static final String CREATED_BY = "<!-- File Created By SMSecure -->";
    private static final String OPEN_TAG_SMSES = "<smses count=\"%d\">";
    private static final String CLOSE_TAG_SMSES = "</smses>";
    private static final String OPEN_TAG_SMS = " <sms ";
    private static final String CLOSE_EMPTYTAG = "/>";
    private static final String OPEN_ATTRIBUTE = "=\"";
    private static final String CLOSE_ATTRIBUTE = "\" ";

    private static final Pattern PATTERN = Pattern.compile("[^ -\uD7FF]");

    private final BufferedWriter bufferedWriter;

    public Writer(String path, int count) throws IOException {
      bufferedWriter = new BufferedWriter(new FileWriter(path, false));

      bufferedWriter.write(XML_HEADER);
      bufferedWriter.newLine();
      bufferedWriter.write(CREATED_BY);
      bufferedWriter.newLine();
      bufferedWriter.write(String.format(Locale.ROOT, OPEN_TAG_SMSES, count));
    }

    /// SAF-friendly writer.
    public Writer(OutputStream os, int count) throws IOException {
      bufferedWriter = new BufferedWriter(
              new OutputStreamWriter(os, StandardCharsets.UTF_8)
      );

      bufferedWriter.write(XML_HEADER);
      bufferedWriter.newLine();
      bufferedWriter.write(CREATED_BY);
      bufferedWriter.newLine();
      bufferedWriter.write(String.format(Locale.ROOT, OPEN_TAG_SMSES, count));
    }


    public void writeItem(XmlBackupItem item) throws IOException {
      StringBuilder stringBuilder = new StringBuilder();

      stringBuilder.append(OPEN_TAG_SMS);
      appendAttribute(stringBuilder, PROTOCOL, item.getProtocol());
      appendAttribute(stringBuilder, ADDRESS, escapeXML(item.getAddress()));
      appendAttribute(stringBuilder, DATE, item.getDate());
      appendAttribute(stringBuilder, TYPE, item.getType());
      appendAttribute(stringBuilder, SUBJECT, escapeXML(item.getSubject()));
      appendAttribute(stringBuilder, BODY, escapeXML(item.getBody()));
      appendAttribute(stringBuilder, TOA, "null");
      appendAttribute(stringBuilder, SC_TOA, "null");
      appendAttribute(stringBuilder, SERVICE_CENTER, item.getServiceCenter());
      appendAttribute(stringBuilder, READ, item.getRead());
      appendAttribute(stringBuilder, STATUS, item.getStatus());
      appendAttribute(stringBuilder, LOCKED, 0);
      stringBuilder.append(CLOSE_EMPTYTAG);

      bufferedWriter.newLine();
      bufferedWriter.write(stringBuilder.toString());
    }

    private <T> void appendAttribute(StringBuilder stringBuilder, String name, T value) {
      stringBuilder.append(name).append(OPEN_ATTRIBUTE).append(value).append(CLOSE_ATTRIBUTE);
    }

    public void close() throws IOException {
      bufferedWriter.newLine();
      bufferedWriter.write(CLOSE_TAG_SMSES);
      bufferedWriter.flush();
      bufferedWriter.close();
    }

    private String escapeXML(String s) {
      if (TextUtils.isEmpty(s)) return s;

      Matcher matcher = PATTERN.matcher(s.replace("&", "&amp;")
              .replace("<", "&lt;")
              .replace(">", "&gt;")
              .replace("\"", "&quot;")
              .replace("'", "&apos;"));
      StringBuffer st = new StringBuffer();

      while (matcher.find()) {
        String group = matcher.group(0);
        if (group == null) {
          continue;
        }

        StringBuilder escaped = new StringBuilder(group.length() * 6);
        for (int i = 0; i < group.length(); i++) {
          char ch = group.charAt(i);
          escaped.append("&#").append((int) ch).append(";");
        }

        matcher.appendReplacement(st, Matcher.quoteReplacement(escaped.toString()));
      }
      matcher.appendTail(st);
      return st.toString();
    }
  }
}
