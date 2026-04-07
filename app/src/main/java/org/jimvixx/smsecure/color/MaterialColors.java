/*
 * Copyright (C) 2015 Open Whisper Systems
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

package org.jimvixx.smsecure.color;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MaterialColors {

  public static final MaterialColorList CONVERSATION_PALETTE = new MaterialColorList(new ArrayList<>(Arrays.asList(
          MaterialColor.RED,
          MaterialColor.PINK,
          MaterialColor.PURPLE,
          MaterialColor.DEEP_PURPLE,
          MaterialColor.INDIGO,

          MaterialColor.BLUE,
          MaterialColor.LIGHT_BLUE,
          MaterialColor.CYAN,
          MaterialColor.TEAL,
          MaterialColor.GREEN,

          MaterialColor.LIGHT_GREEN,
          MaterialColor.LIME,
          MaterialColor.YELLOW,
          MaterialColor.AMBER,
          MaterialColor.ORANGE,

          MaterialColor.DEEP_ORANGE,
          MaterialColor.BROWN,
          MaterialColor.GREY,
          MaterialColor.BLUE_GREY
  )));

  public static class MaterialColorList {

    private final List<MaterialColor> colors;

    private MaterialColorList(List<MaterialColor> colors) {
      this.colors = colors;
    }

    public MaterialColor get(int index) {
      return colors.get(index);
    }

    public int size() {
      return colors.size();
    }

    public @Nullable MaterialColor getByColor(Context context, int colorValue) {
      for (MaterialColor color : colors) {
        if (color.represents(context, colorValue)) {
          return color;
        }
      }

      return null;
    }

    public int[] asConversationColorArray(@NonNull Context context) {
      int[] results = new int[colors.size()];
      int index = 0;

      for (MaterialColor color : colors) {
        results[index++] = color.toConversationColor(context);
      }

      return results;
    }

  }


}

