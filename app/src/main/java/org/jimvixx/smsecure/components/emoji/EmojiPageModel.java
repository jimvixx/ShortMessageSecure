package org.jimvixx.smsecure.components.emoji;

import androidx.annotation.DrawableRes;

public interface EmojiPageModel {
  @DrawableRes int getCategoryIcon();
  String[] getEmoji();
  boolean hasSpriteMap();
  String getSprite();
  boolean isDynamic();
}
