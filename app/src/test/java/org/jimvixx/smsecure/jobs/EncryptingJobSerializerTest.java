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

package org.jimvixx.smsecure.jobs;

import android.content.Context;
import android.util.Base64;
import org.jimvixx.smsecure.jobs.persistence.EncryptingJobSerializer;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.whispersystems.jobqueue.Job;
import org.whispersystems.jobqueue.persistence.JavaJobSerializer;
import java.io.IOException;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class EncryptingJobSerializerTest {
  @Test public void stableEnvelopeAndReadableLegacyDataAreSupported() throws Exception {
    try (MockedStatic<Base64> base64 = base64()) {
      Context context = mock(Context.class);
      EncryptingJobSerializer serializer = new EncryptingJobSerializer(context);
      Job original = new GenerateKeysJob(context);
      String stable = serializer.serialize(original);
      assertTrue(stable.startsWith("SMSecureJob:"));
      assertTrue(serializer.deserialize(null, false, stable) instanceof GenerateKeysJob);
      String legacy = new JavaJobSerializer().serialize(original);
      Job migrated = serializer.deserialize(null, false, legacy);
      assertTrue(migrated instanceof GenerateKeysJob);
      assertEquals(stable, serializer.serialize(migrated));
    }
  }

  @Test public void malformedAndNonJobLegacyRecordsFailClosed() throws Exception {
    try (MockedStatic<Base64> base64 = base64()) {
      EncryptingJobSerializer serializer = new EncryptingJobSerializer(mock(Context.class));
      assertThrows(IOException.class, () -> serializer.deserialize(null, false, "SMSecureJob:!!!"));
      assertThrows(IOException.class, () -> serializer.deserialize(null, false, "!!!"));
      java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
      new java.io.ObjectOutputStream(bytes).writeObject("not a job");
      String nonJob = java.util.Base64.getEncoder().encodeToString(bytes.toByteArray());
      assertThrows(IOException.class, () -> serializer.deserialize(null, false, nonJob));
    }
  }

  private MockedStatic<Base64> base64() {
    MockedStatic<Base64> result = mockStatic(Base64.class);
    result.when(() -> Base64.encodeToString(any(byte[].class), anyInt()))
            .thenAnswer(call -> java.util.Base64.getEncoder().encodeToString(call.getArgument(0)));
    result.when(() -> Base64.decode(anyString(), anyInt()))
            .thenAnswer(call -> java.util.Base64.getDecoder().decode((String)call.getArgument(0)));
    return result;
  }
}

