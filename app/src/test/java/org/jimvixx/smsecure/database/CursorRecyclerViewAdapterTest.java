package org.jimvixx.smsecure.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.database.Cursor;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.junit.Before;
import org.junit.Test;

public class CursorRecyclerViewAdapterTest {

  private CursorRecyclerViewAdapter<TestViewHolder> adapter;

  @Before
  public void setUp() {
    Context context = mock(Context.class);
    Cursor cursor = mock(Cursor.class);

    when(cursor.getCount()).thenReturn(100);
    when(cursor.moveToPosition(anyInt())).thenReturn(true);

    adapter = new CursorRecyclerViewAdapter<>(context, cursor) {
      @Override
      public TestViewHolder onCreateItemViewHolder(ViewGroup parent, int viewType) {
        return new TestViewHolder(mock(View.class));
      }

      @Override
      public void onBindItemViewHolder(TestViewHolder viewHolder,
                                       @NonNull Cursor cursor) {
        // no-op
      }
    };
  }

  @Test
  public void testSanityCount() {
    assertEquals(100, adapter.getItemCount());
  }

  @Test
  public void testHeaderCount() {
    adapter.setHeaderView(mock(View.class));

    assertEquals(101, adapter.getItemCount());
    assertEquals(CursorRecyclerViewAdapter.HEADER_TYPE, adapter.getItemViewType(0));
    assertNotEquals(CursorRecyclerViewAdapter.HEADER_TYPE, adapter.getItemViewType(1));
    assertNotEquals(CursorRecyclerViewAdapter.HEADER_TYPE, adapter.getItemViewType(100));
  }

  @Test
  public void testFooterCount() {
    adapter.setFooterView(mock(View.class));

    assertEquals(101, adapter.getItemCount());
    assertEquals(CursorRecyclerViewAdapter.FOOTER_TYPE, adapter.getItemViewType(100));
    assertNotEquals(CursorRecyclerViewAdapter.FOOTER_TYPE, adapter.getItemViewType(0));
    assertNotEquals(CursorRecyclerViewAdapter.FOOTER_TYPE, adapter.getItemViewType(99));
  }

  @Test
  public void testHeaderFooterCount() {
    adapter.setHeaderView(mock(View.class));
    adapter.setFooterView(mock(View.class));

    assertEquals(102, adapter.getItemCount());
    assertEquals(CursorRecyclerViewAdapter.HEADER_TYPE, adapter.getItemViewType(0));
    assertEquals(CursorRecyclerViewAdapter.FOOTER_TYPE, adapter.getItemViewType(101));
    assertNotEquals(CursorRecyclerViewAdapter.HEADER_TYPE, adapter.getItemViewType(1));
    assertNotEquals(CursorRecyclerViewAdapter.FOOTER_TYPE, adapter.getItemViewType(100));
  }

  private static final class TestViewHolder extends RecyclerView.ViewHolder {
    TestViewHolder(@NonNull View itemView) {
      super(itemView);
    }
  }
}
