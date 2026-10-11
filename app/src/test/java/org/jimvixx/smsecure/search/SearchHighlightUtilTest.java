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

package org.jimvixx.smsecure.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SearchHighlightUtilTest {

  @Test
  public void excerptAroundMatchKeepsMatchVisible() {
    String body = "A".repeat(80) + " important phrase " + "B".repeat(100);

    String excerpt = SearchHighlightUtil.excerptAroundMatch(body, "IMPORTANT");

    assertTrue(excerpt.startsWith("…"));
    assertTrue(excerpt.endsWith("…"));
    assertTrue(excerpt.contains("important phrase"));
    assertTrue(excerpt.toLowerCase().indexOf("important") <= 13);
  }

  @Test
  public void excerptAroundMatchLeavesShortTextUnchanged() {
    assertEquals("Short matching text",
            SearchHighlightUtil.excerptAroundMatch("Short matching text", "matching"));
  }
}
