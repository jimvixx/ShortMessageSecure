/*
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


package org.jimvixx.smsecure.util.views;

import android.view.View;
import android.view.ViewStub;

import androidx.annotation.NonNull;

import java.util.Objects;

public final class Stub<T extends View> {

  private final Class<T> type;
  private ViewStub viewStub;
  private T view;

  public Stub(@NonNull ViewStub viewStub, @NonNull Class<T> type) {
    this.viewStub = viewStub;
    this.type = type;
  }

  public @NonNull T get() {
    T local = view;
    if (local == null) {
      ViewStub stub = Objects.requireNonNull(viewStub, "Stub already inflated");
      View inflated = stub.inflate();

      local = Objects.requireNonNull(type.cast(inflated),
              "Inflated view is null (stub=" + stub + ", expected=" + type.getName() + ")");

      view = local;
      viewStub = null;
    }
    return local;
  }

  public boolean resolved() {
    return view != null;
  }
}