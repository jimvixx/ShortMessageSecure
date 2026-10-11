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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jimvixx.smsecure.database.model.ThreadRecord;

public final class GlobalSearchResult {
  public enum Type { HEADER, CHAT, MESSAGE }

  private final Type type;
  private final String header;
  private final ThreadRecord thread;
  private final long messageId;
  private final String messageBody;
  private final long messageDate;

  private GlobalSearchResult(@NonNull Type type, @Nullable String header,
                             @Nullable ThreadRecord thread, long messageId,
                             @Nullable String messageBody, long messageDate) {
    this.type = type;
    this.header = header;
    this.thread = thread;
    this.messageId = messageId;
    this.messageBody = messageBody;
    this.messageDate = messageDate;
  }

  public static GlobalSearchResult header(@NonNull String title) {
    return new GlobalSearchResult(Type.HEADER, title, null, -1, null, 0);
  }

  public static GlobalSearchResult chat(@NonNull ThreadRecord thread) {
    return new GlobalSearchResult(Type.CHAT, null, thread, -1, null, thread.getDate());
  }

  public static GlobalSearchResult message(@NonNull ThreadRecord thread, long messageId,
                                           @NonNull String body, long date) {
    return new GlobalSearchResult(Type.MESSAGE, null, thread, messageId, body, date);
  }

  public @NonNull Type getType() { return type; }
  public @Nullable String getHeader() { return header; }
  public @Nullable ThreadRecord getThread() { return thread; }
  public long getMessageId() { return messageId; }
  public @Nullable String getMessageBody() { return messageBody; }
  public long getMessageDate() { return messageDate; }
}
