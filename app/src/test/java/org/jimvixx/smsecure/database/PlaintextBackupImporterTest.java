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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.database.sqlite.SQLiteStatement;

import org.junit.Test;

public class PlaintextBackupImporterTest {

  @Test
  public void serviceCenterNumberIsBoundToSmsInsert() {
    SQLiteStatement statement = mock(SQLiteStatement.class);

    PlaintextBackupImporter.addStringToStatement(statement, 12, "+15551234567");

    verify(statement).bindString(12, "+15551234567");
  }

  @Test
  public void compatibleXmlNullMarkerIsRestoredAsSqlNull() {
    SQLiteStatement statement = mock(SQLiteStatement.class);

    PlaintextBackupImporter.addStringToStatement(statement, 12, "null");

    verify(statement).bindNull(12);
  }
}
