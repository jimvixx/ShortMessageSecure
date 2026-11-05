package org.jimvixx.smsecure;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
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

  @Test
  public void identifiesPinnedConversationBoundary() {
    assertPinnedConversationBoundary(false, true, true);
    assertPinnedConversationBoundary(true, true, false);
    assertPinnedConversationBoundary(false, false, false);
  }

  private static void assertPinnedConversationBoundary(boolean currentPinned,
                                                        boolean previousPinned,
                                                        boolean expected) {
    assertEquals(expected,
            ConversationListAdapter.isPinnedConversationBoundary(currentPinned, previousPinned));
  }
}
