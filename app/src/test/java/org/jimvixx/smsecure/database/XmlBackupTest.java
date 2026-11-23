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

package org.jimvixx.smsecure.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Document;

public class XmlBackupTest {

  @Test
  public void serviceCenterNumberRoundTripsThroughXml() throws Exception {
    String serviceCenter = "+15551234567";
    String xml = writeItem(serviceCenter);

    assertEquals(serviceCenter, readServiceCenterAttribute(xml));
  }

  @Test
  public void serviceCenterSpecialCharactersAreEscapedAndRoundTrip() throws Exception {
    String serviceCenter = "+1555&<>'\"";
    String xml = writeItem(serviceCenter);

    assertTrue(xml.contains("service_center=\"+1555&amp;&lt;&gt;&apos;&quot;\""));
    assertEquals(serviceCenter, readServiceCenterAttribute(xml));
  }

  @Test
  public void nullServiceCenterUsesCompatibleXmlNullMarker() throws Exception {
    String xml = writeItem(null);

    assertTrue(xml.contains("service_center=\"null\""));
    assertEquals("null", readServiceCenterAttribute(xml));
  }

  private static String writeItem(String serviceCenter) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    XmlBackup.Writer writer = new XmlBackup.Writer(output, 1);
    writer.writeItem(new XmlBackup.XmlBackupItem(
            0,
            "+15551234567",
            1700000000000L,
            1,
            null,
            "body",
            serviceCenter,
            1,
            0));
    writer.close();
    return output.toString(StandardCharsets.UTF_8);
  }

  private static String readServiceCenterAttribute(String xml) throws Exception {
    Document document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    return document.getElementsByTagName("sms")
            .item(0)
            .getAttributes()
            .getNamedItem("service_center")
            .getNodeValue();
  }
}
