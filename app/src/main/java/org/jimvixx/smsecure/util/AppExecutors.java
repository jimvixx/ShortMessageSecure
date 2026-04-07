/*
 * Copyright (C) 2014 Open Whisper Systems
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

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/// Centralized executors for the app.
/// Avoids creating per-Activity thread pools and Handler warnings.
public final class AppExecutors {
  public static final Executor DB = Executors.newSingleThreadExecutor();

  private static final ExecutorService BACKGROUND = Executors.newCachedThreadPool();

  private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

  private AppExecutors() {
  }

  public static Executor background() {
    return BACKGROUND;
  }

  public static Handler mainHandler() {
    return MAIN_HANDLER;
  }
}
