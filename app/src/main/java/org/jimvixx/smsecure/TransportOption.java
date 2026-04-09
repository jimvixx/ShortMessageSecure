/*
 * Copyright (C) 2011 Whisper Systems
 * Copyright (C) 2013 Open Whisper Systems
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

package org.jimvixx.smsecure;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;

import org.jimvixx.smsecure.util.CharacterCalculator;
import org.jimvixx.smsecure.util.CharacterCalculator.CharacterState;
import org.whispersystems.libsignal.util.guava.Optional;

public class TransportOption {

  private final int drawable;
  private final int backgroundColor;
  private final @NonNull String text;
  private final @NonNull Type type;
  private final @NonNull String composeHint;
  private final @NonNull CharacterCalculator characterCalculator;
  private final @NonNull Optional<CharSequence> simName;
  private final @NonNull Optional<Integer> simSubscriptionId;

  public TransportOption(@NonNull Type type,
                         @DrawableRes int drawable,
                         int backgroundColor,
                         @NonNull String text,
                         @NonNull String composeHint,
                         @NonNull CharacterCalculator characterCalculator,
                         @NonNull Optional<CharSequence> simName,
                         @NonNull Optional<Integer> simSubscriptionId) {
    this.type = type;
    this.drawable = drawable;
    this.backgroundColor = backgroundColor;
    this.text = text;
    this.composeHint = composeHint;
    this.characterCalculator = characterCalculator;
    this.simName = simName;
    this.simSubscriptionId = simSubscriptionId;
  }

  public @NonNull Type getType() {
    return type;
  }

  public boolean isType(Type type) {
    return this.type == type;
  }

  public boolean isPlaintext() {
    return type == Type.INSECURE_SMS;
  }

  public CharacterState calculateCharacters(String messageBody) {
    return characterCalculator.calculateCharacters(messageBody);
  }

  public @DrawableRes int getDrawable() {
    return drawable;
  }

  public int getBackgroundColor() {
    return backgroundColor;
  }

  public @NonNull String getComposeHint() {
    return composeHint;
  }

  public @NonNull String getDescription() {
    return text;
  }

  @NonNull
  public Optional<CharSequence> getSimName() {
    return simName;
  }

  @NonNull
  public Optional<Integer> getSimSubscriptionId() {
    return simSubscriptionId;
  }

  public enum Type {
    DISABLED,
    INSECURE_SMS,
    SECURE_SMS
  }

}
