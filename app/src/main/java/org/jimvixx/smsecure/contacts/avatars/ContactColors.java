package org.jimvixx.smsecure.contacts.avatars;

import androidx.annotation.NonNull;

import org.jimvixx.smsecure.color.MaterialColor;
import org.jimvixx.smsecure.color.MaterialColors;

public class ContactColors {

  public static final MaterialColor UNKNOWN_COLOR = MaterialColor.GREY;

  public static MaterialColor generateFor(@NonNull String name) {
    return MaterialColors.CONVERSATION_PALETTE.get(Math.abs(name.hashCode()) % MaterialColors.CONVERSATION_PALETTE.size());
  }

}
