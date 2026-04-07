/*
 * Copyright (C) 2011 Whisper Systems
 * Copyright (C) 2025 Jimvixx
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

package org.jimvixx.smsecure.components.emoji.parsing;

public enum Fitzpatrick {
  /**
   * Fitzpatrick modifier of type 1/2 (pale white/white)
   */
  TYPE_1_2("\uD83C\uDFFB"),

  /**
   * Fitzpatrick modifier of type 3 (cream white)
   */
  TYPE_3("\uD83C\uDFFC"),

  /**
   * Fitzpatrick modifier of type 4 (moderate brown)
   */
  TYPE_4("\uD83C\uDFFD"),

  /**
   * Fitzpatrick modifier of type 5 (dark brown)
   */
  TYPE_5("\uD83C\uDFFE"),

  /**
   * Fitzpatrick modifier of type 6 (black)
   */
  TYPE_6("\uD83C\uDFFF");

  /**
   * The unicode representation of the Fitzpatrick modifier
   */
  public final String unicode;

  Fitzpatrick(String unicode) {
    this.unicode = unicode;
  }


  public static Fitzpatrick fitzpatrickFromUnicode(CharSequence unicode, int index) {
    for (Fitzpatrick v : values()) {
      boolean match = true;

      for (int i = 0; i< v.unicode.length(); i++) {
        if (v.unicode.toCharArray()[i] != unicode.charAt(index + i)) {
          match = false;
        }
      }

      if (match) return v;
    }

    return null;
  }
}
