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

package org.jimvixx.smsecure.util;

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;

import org.junit.Test;
import org.mockito.MockedStatic;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

public class BitmapUtilTest {

  @Test(timeout = 5000)
  public void interruptedWaitSkipsOptionalIconAndPreservesInterrupt() {
    BitmapDrawable drawable = mock(BitmapDrawable.class);
    try (MockedStatic<Util> util = mockStatic(Util.class, CALLS_REAL_METHODS)) {
      // Keep rendering queued, as when the main thread is busy.
      util.when(() -> Util.runOnMain(any(Runnable.class))).thenAnswer(invocation -> null);
      Thread.currentThread().interrupt();
      try {
        assertNull(BitmapUtil.createFromDrawable(drawable, 48, 48));
        assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
    }
  }

  @Test(timeout = 5000)
  public void completedRenderingReturnsBitmap() {
    Bitmap bitmap = mock(Bitmap.class);
    BitmapDrawable drawable = mock(BitmapDrawable.class);
    doReturn(bitmap).when(drawable).getBitmap();
    try (MockedStatic<Util> util = mockStatic(Util.class, CALLS_REAL_METHODS)) {
      util.when(() -> Util.runOnMain(any(Runnable.class))).thenAnswer(invocation -> {
        invocation.<Runnable>getArgument(0).run();
        return null;
      });
      assertSame(bitmap, BitmapUtil.createFromDrawable(drawable, 48, 48));
    }
  }
}
