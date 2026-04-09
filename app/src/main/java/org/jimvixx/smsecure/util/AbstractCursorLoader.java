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

package org.jimvixx.smsecure.util;

import android.content.Context;
import android.database.Cursor;

import androidx.loader.content.AsyncTaskLoader;

/// A Loader similar to CursorLoader that doesn't require queries to go through the ContentResolver
/// to get the benefits of reloading when content has changed.
public abstract class AbstractCursorLoader extends AsyncTaskLoader<Cursor> {
  private static final String TAG = AbstractCursorLoader.class.getSimpleName();

  protected final ForceLoadContentObserver observer;
  protected final Context context;
  protected Cursor cursor;

  public AbstractCursorLoader(Context context) {
    super(context);
    this.context = context.getApplicationContext();
    this.observer = new ForceLoadContentObserver();
  }

  public abstract Cursor getCursor();

  @Override
  public void deliverResult(Cursor newCursor) {
    if (isReset()) {
      if (newCursor != null) {
        newCursor.close();
      }
      return;
    }
    Cursor oldCursor = this.cursor;

    this.cursor = newCursor;

    if (isStarted()) {
      super.deliverResult(newCursor);
    }
    if (oldCursor != null && oldCursor != cursor && !oldCursor.isClosed()) {
      oldCursor.close();
    }
  }

  @Override
  protected void onStartLoading() {
    if (cursor != null) {
      deliverResult(cursor);
    }
    if (takeContentChanged() || cursor == null) {
      forceLoad();
    }
  }

  @Override
  protected void onStopLoading() {
    cancelLoad();
  }

  @Override
  public void onCanceled(Cursor cursor) {
    if (cursor != null && !cursor.isClosed()) {
      cursor.close();
    }
  }

  @Override
  public Cursor loadInBackground() {
    Cursor newCursor = getCursor();
    if (newCursor != null) {
      newCursor.getCount();
      newCursor.registerContentObserver(observer);
    }
    return newCursor;
  }

  @Override
  protected void onReset() {
    super.onReset();

    onStopLoading();

    if (cursor != null && !cursor.isClosed()) {
      cursor.close();
    }
    cursor = null;
  }
}
