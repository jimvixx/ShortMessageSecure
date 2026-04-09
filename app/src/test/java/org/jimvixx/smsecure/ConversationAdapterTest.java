package org.jimvixx.smsecure;

import static org.junit.Assert.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.database.Cursor;

import org.junit.Before;
import org.junit.Test;

public class ConversationAdapterTest {

  private Cursor cursor;
  private ConversationAdapter adapter;

  @Before
  public void setUp() {
    Context context = mock(Context.class);
    cursor = mock(Cursor.class);

    adapter = new ConversationAdapter(context, cursor);

    when(cursor.getColumnIndexOrThrow(anyString())).thenReturn(0);
  }

  @Test
  public void testGetItemIdEquals() {
    when(cursor.getString(anyInt())).thenReturn("SMS::1::1");
    long firstId = adapter.getItemId(cursor);

    when(cursor.getString(anyInt())).thenReturn("MMS::1::1");
    long secondId = adapter.getItemId(cursor);
    assertNotEquals(firstId, secondId);

    when(cursor.getString(anyInt())).thenReturn("MMS::2::1");
    long thirdId = adapter.getItemId(cursor);
    assertNotEquals(secondId, thirdId);
  }
}
