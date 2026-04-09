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

package org.jimvixx.smsecure.components.emoji;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;

import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.databind.type.TypeFactory;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.util.JsonUtils;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RecentEmojiPageModel implements EmojiPageModel {

  private static final String TAG = RecentEmojiPageModel.class.getSimpleName();
  private static final String EMOJI_LRU_PREFERENCE = "pref_recent_emoji2";
  private static final int EMOJI_LRU_SIZE = 50;

  private final SharedPreferences prefs;
  private final LinkedHashSet<String> recentlyUsed;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  public RecentEmojiPageModel(@NonNull Context context) {
    Context appContext = context.getApplicationContext();
    this.prefs = appContext.getSharedPreferences("smsecure_prefs", Context.MODE_PRIVATE);
    this.recentlyUsed = getPersistedCache();
  }

  private LinkedHashSet<String> getPersistedCache() {
    String serialized = prefs.getString(EMOJI_LRU_PREFERENCE, "[]");
    try {
      CollectionType collectionType =
              TypeFactory.defaultInstance()
                      .constructCollectionType(LinkedHashSet.class, String.class);

      return JsonUtils.getMapper().readValue(serialized, collectionType);
    } catch (IOException e) {
      Log.w(TAG, e);
      return new LinkedHashSet<>();
    }
  }

  @Override
  public @DrawableRes int getCategoryIcon() {
    return R.drawable.ic_clock_outline;
  }

  @Override
  public String[] getEmoji() {
    return toReversePrimitiveArray(recentlyUsed);
  }

  @Override
  public boolean hasSpriteMap() {
    return false;
  }

  @Override
  public String getSprite() {
    return null;
  }

  @Override
  public boolean isDynamic() {
    return true;
  }

  public void onCodePointSelected(@NonNull String emoji) {
    Log.w(TAG, "onCodePointSelected(" + emoji + ")");

    recentlyUsed.remove(emoji);
    recentlyUsed.add(emoji);

    if (recentlyUsed.size() > EMOJI_LRU_SIZE) {
      Iterator<String> iterator = recentlyUsed.iterator();
      if (iterator.hasNext()) {
        iterator.next();
        iterator.remove();
      }
    }

    LinkedHashSet<String> snapshot = new LinkedHashSet<>(recentlyUsed);

    executor.execute(() -> persist(snapshot));
  }

  private void persist(@NonNull LinkedHashSet<String> emojiSet) {
    try {
      String serialized = JsonUtils.toJson(emojiSet);
      prefs.edit()
              .putString(EMOJI_LRU_PREFERENCE, serialized)
              .apply();
    } catch (IOException e) {
      Log.w(TAG, e);
    }
  }

  private String[] toReversePrimitiveArray(@NonNull LinkedHashSet<String> emojiSet) {
    String[] emojis = new String[emojiSet.size()];
    int i = emojiSet.size() - 1;
    for (String emoji : emojiSet) {
      emojis[i--] = emoji;
    }
    return emojis;
  }
}
