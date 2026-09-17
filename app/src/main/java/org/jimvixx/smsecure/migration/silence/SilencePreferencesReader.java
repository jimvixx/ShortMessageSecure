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

package org.jimvixx.smsecure.migration.silence;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.ext.DefaultHandler2;

/** Parses snapshot XML without accessing Android SharedPreferences or logging secrets. */
final class SilencePreferencesReader {
  static Map<String, String> read(File file) throws IOException {
    if (file.length() > 1024 * 1024) throw new IOException("Preferences size limit exceeded");
    Map<String, String> values = new HashMap<>();
    try {
      SAXParserFactory factory = SAXParserFactory.newInstance();
      factory.setNamespaceAware(false);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      org.xml.sax.XMLReader reader = factory.newSAXParser().getXMLReader();
      DefaultHandler2 handler = new DefaultHandler2() {
        int depth;
        String key;
        String type;
        StringBuilder text;
        @Override public void startDTD(String name, String publicId, String systemId) throws SAXException {
          throw new SAXException("DTD is forbidden");
        }
        @Override public void startElement(String uri, String local, String name, Attributes attrs) throws SAXException {
          depth++;
          if (depth == 1) {
            if (!name.equals("map")) throw new SAXException("Expected preferences map");
          } else if (depth == 2) {
            key = attrs.getValue("name");
            type = name;
            if (key == null || values.containsKey(key)) throw new SAXException("Duplicate or missing preference key");
            if (!java.util.Arrays.asList("string", "boolean", "int", "long", "float", "set").contains(type))
              throw new SAXException("Unknown preference type");
            String value = attrs.getValue("value");
            if (type.equals("boolean") && !"true".equals(value) && !"false".equals(value))
              throw new SAXException("Invalid boolean");
            text = new StringBuilder();
            values.put(key, type + ":" + (value == null ? "" : value));
          } else if (!(depth == 3 && "set".equals(type) && name.equals("string"))) {
            throw new SAXException("Unexpected preference nesting");
          }
        }
        @Override public void characters(char[] ch, int start, int length) {
          if (depth == 2 && "string".equals(type)) text.append(ch, start, length);
        }
        @Override public void endElement(String uri, String local, String name) {
          if (depth == 2 && "string".equals(type)) values.put(key, "string:" + text);
          depth--;
        }
      };
      reader.setContentHandler(handler);
      reader.setErrorHandler(handler);
      reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
      reader.parse(file.toURI().toString());
      return values;
    } catch (Exception e) { throw new IOException("Invalid backup preferences", e); }
  }

  static boolean validate(File root) throws IOException {
    Map<String, String> defaults = read(new File(root, SilenceBackupDetector.DEFAULT_PREFS));
    Map<String, String> secrets = read(new File(root, SilenceBackupDetector.SECRET_PREFS));
    if (!"boolean:true".equals(secrets.get("passphrase_initialized")))
      throw new IOException("Missing initialized master secret");
    for (String key : new String[]{"master_secret", "encryption_salt", "mac_salt"}) {
      String value = secrets.get(key);
      if (value == null || !value.startsWith("string:")) throw new IOException("Missing master secret field");
      try {
        byte[] decoded = org.jimvixx.smsecure.util.Base64.decode(value.substring(7).replaceAll("\\s", ""), org.jimvixx.smsecure.util.Base64.DONT_GUNZIP);
        if (decoded.length != (key.equals("master_secret") ? 68 : 16))
          throw new IOException("Invalid master secret field length");
      } catch (IllegalArgumentException e) { throw new IOException("Invalid master secret encoding", e); }
    }
    String iterations = secrets.get("passphrase_iterations");
    // The legacy reader defaults to 100 for backups without this preference.
    if (iterations != null) {
      try {
        if (!iterations.startsWith("int:") || Integer.parseInt(iterations.substring(4)) <= 0)
          throw new IOException("Invalid passphrase iterations");
      } catch (NumberFormatException e) { throw new IOException("Invalid passphrase iterations", e); }
    }
    String disabled = defaults.get("pref_disable_passphrase");
    if (disabled != null && !disabled.equals("boolean:true") && !disabled.equals("boolean:false"))
      throw new IOException("Invalid passphrase mode");
    return "boolean:true".equals(disabled);
  }
}
