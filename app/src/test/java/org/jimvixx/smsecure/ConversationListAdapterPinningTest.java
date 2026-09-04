package org.jimvixx.smsecure;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ConversationListAdapterPinningTest {

  @Test
  public void allUnpinnedShowsPin() {
    assertFalse(ConversationListAdapter.shouldUnpinSelection(3, 0));
  }

  @Test
  public void allPinnedShowsUnpin() {
    assertTrue(ConversationListAdapter.shouldUnpinSelection(3, 3));
  }

  @Test
  public void mixedSelectionShowsPin() {
    assertFalse(ConversationListAdapter.shouldUnpinSelection(3, 1));
  }
}
