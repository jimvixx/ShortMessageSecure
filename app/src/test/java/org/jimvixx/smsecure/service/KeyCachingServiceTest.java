package org.jimvixx.smsecure.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import android.content.Context;
import android.content.Intent;

import org.jimvixx.smsecure.logging.Log;
import org.junit.Test;
import org.mockito.MockedStatic;

public class KeyCachingServiceTest {

  @Test
  public void startServiceSafelyReturnsTrueWhenServiceStarts() {
    Context context = mock(Context.class);
    Intent intent = mock(Intent.class);

    assertTrue(KeyCachingService.startServiceSafely(context, intent));
  }

  @Test
  public void startServiceSafelyReturnsFalseWhenBackgroundStartIsRejected() {
    Context context = mock(Context.class);
    Intent intent = mock(Intent.class);
    doThrow(new IllegalStateException("Not allowed to start service"))
            .when(context)
            .startService(intent);

    try (MockedStatic<Log> ignored = mockStatic(Log.class)) {
      assertFalse(KeyCachingService.startServiceSafely(context, intent));
    }
  }
}
