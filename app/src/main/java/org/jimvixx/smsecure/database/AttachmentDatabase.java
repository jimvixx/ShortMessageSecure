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
package org.jimvixx.smsecure.database;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.jimvixx.smsecure.crypto.AuthenticatedPartReader;
import org.jimvixx.smsecure.crypto.MasterSecret;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Read-only attachment metadata and authenticated readers; it never modifies stored data. */
public final class AttachmentDatabase {
  private static final long MAX_ATTACHMENT_BYTES = 512L * 1024L * 1024L;
  private static final long MAX_THUMBNAIL_BYTES = 32L * 1024L * 1024L;
  private static final long ENCRYPTED_FILE_OVERHEAD = 52L;
  private static final Pattern OWNED_PART_FILENAME = Pattern.compile("part[A-Za-z0-9]+\\.mms");

  private final Context context;
  private final SQLiteOpenHelper databaseHelper;
  public AttachmentDatabase(@NonNull Context context, @NonNull SQLiteOpenHelper databaseHelper) {
    this.context = context;
    this.databaseHelper = databaseHelper;
  }
  @NonNull public List<AttachmentRecord> getAttachmentsForMessage(long mmsId) {
    List<AttachmentRecord> result = new ArrayList<>();
    try (Cursor c = databaseHelper.getReadableDatabase().query("part",
            new String[]{"_id", "mid", "seq", "ct", "name", "cd", "cl", "_data", "data_size",
                    "thumbnail", "aspect_ratio", "unique_id", "digest", "encrypted", "pending_push"},
            "mid = ?", new String[]{String.valueOf(mmsId)}, null, null, "seq ASC, _id ASC")) {
      while (c.moveToNext()) result.add(new AttachmentRecord(c.getLong(0), c.getLong(1), c.getInt(2),
              c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7),
              c.getLong(8), c.getString(9), c.getFloat(10), c.getLong(11), c.getBlob(12),
              c.getInt(13), c.getInt(14)));
    }
    return result;
  }

  /** Returns only the identity and MIME metadata needed by read-only attachment consumers. */
  @NonNull public List<PartMetadata> getPartMetadataForMessage(long mmsId) {
    List<PartMetadata> result = new ArrayList<>();
    try (Cursor c = databaseHelper.getReadableDatabase().query("part",
            new String[]{"_id", "mid", "seq", "ct", "unique_id"},
            "mid = ?", new String[]{String.valueOf(mmsId)}, null, null, "seq ASC, _id ASC")) {
      while (c.moveToNext()) {
        result.add(new PartMetadata(c.getLong(0), c.getLong(1), c.getInt(2),
                c.isNull(3) ? null : c.getString(3), c.isNull(4) ? 0L : c.getLong(4)));
      }
    }
    return result;
  }

  /** Opens authenticated plaintext for one data file or its separately encrypted thumbnail. */
  @NonNull public AttachmentAccessResult openPart(long mmsId, long partId, long uniqueId,
                                                   @NonNull FileKind fileKind,
                                                   @Nullable MasterSecret masterSecret) {
    if (fileKind == null) return AttachmentAccessResult.failure(AccessStatus.UNSUPPORTED_METADATA);
    return openPart(mmsId, partId, uniqueId, fileKind, masterSecret,
            fileKind == FileKind.THUMBNAIL ? MAX_THUMBNAIL_BYTES : MAX_ATTACHMENT_BYTES);
  }

  /** Opens authenticated plaintext with a caller-specific ceiling no greater than the API limit. */
  @NonNull public AttachmentAccessResult openPart(long mmsId, long partId, long uniqueId,
                                                   @NonNull FileKind fileKind,
                                                   @Nullable MasterSecret masterSecret,
                                                   long maxPlaintextBytes) {
    if (fileKind == null) return AttachmentAccessResult.failure(AccessStatus.UNSUPPORTED_METADATA);
    if (masterSecret == null) return AttachmentAccessResult.failure(AccessStatus.MISSING_MASTER_KEY);

    long apiLimit = fileKind == FileKind.THUMBNAIL
            ? MAX_THUMBNAIL_BYTES : MAX_ATTACHMENT_BYTES;
    if (maxPlaintextBytes < 0 || maxPlaintextBytes > apiLimit) {
      return AttachmentAccessResult.failure(AccessStatus.RESOURCE_LIMIT_EXCEEDED);
    }
    AttachmentMetadata metadata;
    File encryptedFile;
    AuthenticatedPartReader.CiphertextSnapshot snapshot;

    synchronized (AttachmentCleanupProcessor.lifecycleLock()) {
      MetadataLookup lookup = resolveMetadata(mmsId, partId, uniqueId, fileKind);
      if (lookup.metadata == null) return AttachmentAccessResult.failure(lookup.status);
      metadata = lookup.metadata;
      if (metadata.dataSize < 0 || (fileKind == FileKind.DATA &&
              metadata.dataSize > maxPlaintextBytes)) {
        return AttachmentAccessResult.failure(AccessStatus.RESOURCE_LIMIT_EXCEEDED);
      }
      String storedPath = fileKind == FileKind.DATA ? metadata.path : metadata.thumbnailPath;
      PathResult pathResult = validateOwnedFile(storedPath, maxPlaintextBytes);
      if (pathResult.status != AccessStatus.OPENED) {
        return AttachmentAccessResult.failure(pathResult.status);
      }
      encryptedFile = pathResult.file;
      try {
        snapshot = AuthenticatedPartReader.snapshot(context, encryptedFile, maxPlaintextBytes);
      } catch (IOException | RuntimeException exception) {
        PathResult currentPath = validateOwnedFile(storedPath, maxPlaintextBytes);
        if (currentPath.status != AccessStatus.OPENED) {
          return AttachmentAccessResult.failure(currentPath.status);
        }
        return AttachmentAccessResult.failure(AccessStatus.IO_FAILURE);
      }
    }

    try (AuthenticatedPartReader.CiphertextSnapshot pinned = snapshot) {
      AuthenticatedPartReader.VerifiedPlaintext plaintext =
              pinned.authenticate(masterSecret, maxPlaintextBytes);
      return AttachmentAccessResult.opened(new OpenedAttachment(
              plaintext, metadata.contentType, fileKind));
    } catch (AuthenticatedPartReader.ResourceLimitException exception) {
      return AttachmentAccessResult.failure(AccessStatus.RESOURCE_LIMIT_EXCEEDED);
    } catch (IOException | RuntimeException exception) {
      return AttachmentAccessResult.failure(AccessStatus.AUTHENTICATION_FAILED);
    }
  }

  private MetadataLookup resolveMetadata(long mmsId, long partId, long uniqueId,
                                         FileKind fileKind) {
    SQLiteDatabase database = databaseHelper.getReadableDatabase();
    try (Cursor c = database.rawQuery(
            "SELECT p.ct, p._data, p.data_size, p.thumbnail, p.unique_id " +
                    "FROM part p INNER JOIN mms m ON m._id = p.mid " +
                    "WHERE m._id = ? AND p._id = ? AND p.mid = ?",
            new String[]{String.valueOf(mmsId), String.valueOf(partId), String.valueOf(mmsId)})) {
      if (!c.moveToFirst() || c.getLong(4) != uniqueId) {
        return MetadataLookup.failure(AccessStatus.NOT_FOUND);
      }
      String contentType = c.isNull(0) ? null : c.getString(0);
      if (!isSupportedMetadata(contentType)) {
        return MetadataLookup.failure(AccessStatus.UNSUPPORTED_METADATA);
      }
      String path = c.isNull(1) ? null : c.getString(1);
      String thumbnailPath = c.isNull(3) ? null : c.getString(3);
      if ((fileKind == FileKind.DATA && (path == null || path.isEmpty())) ||
              (fileKind == FileKind.THUMBNAIL &&
                      (thumbnailPath == null || thumbnailPath.isEmpty()))) {
        return MetadataLookup.failure(AccessStatus.MISSING_FILE);
      }
      return MetadataLookup.success(new AttachmentMetadata(contentType, path,
              thumbnailPath, c.isNull(2) ? 0 : c.getLong(2)));
    } catch (RuntimeException exception) {
      return MetadataLookup.failure(AccessStatus.IO_FAILURE);
    }
  }

  private static boolean isSupportedMetadata(@Nullable String contentType) {
    if (contentType == null || contentType.isEmpty() || contentType.length() > 255) return false;
    int slash = contentType.indexOf('/');
    if (slash <= 0 || slash == contentType.length() - 1 ||
            contentType.indexOf('/', slash + 1) >= 0) return false;
    for (int i = 0; i < contentType.length(); i++) {
      char c = contentType.charAt(i);
      if (c <= 0x20 || c >= 0x7f) return false;
    }
    return true;
  }

  private PathResult validateOwnedFile(@Nullable String storedPath, long maxPlaintextBytes) {
    if (storedPath == null || storedPath.isEmpty()) {
      return PathResult.failure(AccessStatus.MISSING_FILE);
    }

    try {
      File partsDirectory = context.getDir("parts", Context.MODE_PRIVATE);
      File canonicalRoot = partsDirectory.getCanonicalFile();
      File appDataRoot = context.getFilesDir().getCanonicalFile().getParentFile();
      if (appDataRoot == null || !canonicalRoot.getName().equals("app_parts") ||
              !canonicalRoot.getParentFile().equals(appDataRoot) || !canonicalRoot.isDirectory()) {
        return PathResult.failure(AccessStatus.INVALID_PATH);
      }

      File candidate = new File(storedPath);
      String name = candidate.getName();
      File expected = new File(canonicalRoot, name);
      if (!candidate.isAbsolute() || !OWNED_PART_FILENAME.matcher(name).matches() ||
              !candidate.getAbsolutePath().equals(expected.getAbsolutePath()) ||
              !expected.getCanonicalFile().equals(expected)) {
        return PathResult.failure(AccessStatus.INVALID_PATH);
      }

      if (!hasNoSymlinkComponents(canonicalRoot)) {
        return PathResult.failure(AccessStatus.INVALID_PATH);
      }
      StructStat attributes;
      try {
        attributes = Os.lstat(expected.getAbsolutePath());
      } catch (ErrnoException exception) {
        return PathResult.failure(exception.errno == OsConstants.ENOENT
                ? AccessStatus.MISSING_FILE : AccessStatus.IO_FAILURE);
      }
      if (!OsConstants.S_ISREG(attributes.st_mode)) {
        return PathResult.failure(AccessStatus.INVALID_PATH);
      }
      long maximumCiphertextBytes = maxPlaintextBytes + ENCRYPTED_FILE_OVERHEAD;
      if (attributes.st_size > maximumCiphertextBytes) {
        return PathResult.failure(AccessStatus.RESOURCE_LIMIT_EXCEEDED);
      }
      return PathResult.opened(expected);
    } catch (IOException exception) {
      return PathResult.failure(AccessStatus.IO_FAILURE);
    } catch (SecurityException exception) {
      return PathResult.failure(AccessStatus.INVALID_PATH);
    }
  }

  private static boolean hasNoSymlinkComponents(File partsRoot) {
    File current = partsRoot;
    while (current != null) {
      try {
        StructStat attributes = Os.lstat(current.getAbsolutePath());
        if (OsConstants.S_ISLNK(attributes.st_mode)) return false;
      } catch (ErrnoException exception) {
        return false;
      }
      if (current.getParentFile() == null) return true;
      current = current.getParentFile();
    }
    return true;
  }

  public enum FileKind { DATA, THUMBNAIL }

  public static final class PartMetadata {
    public final long id;
    public final long mmsId;
    public final int sequence;
    @Nullable public final String contentType;
    public final long uniqueId;

    private PartMetadata(long id, long mmsId, int sequence, @Nullable String contentType,
                         long uniqueId) {
      this.id = id;
      this.mmsId = mmsId;
      this.sequence = sequence;
      this.contentType = contentType;
      this.uniqueId = uniqueId;
    }
  }

  public enum AccessStatus {
    OPENED,
    NOT_FOUND,
    MISSING_FILE,
    INVALID_PATH,
    MISSING_MASTER_KEY,
    AUTHENTICATION_FAILED,
    UNSUPPORTED_METADATA,
    RESOURCE_LIMIT_EXCEEDED,
    IO_FAILURE
  }

  public static final class AttachmentAccessResult {
    public final AccessStatus status;
    @Nullable public final OpenedAttachment attachment;

    private AttachmentAccessResult(AccessStatus status, @Nullable OpenedAttachment attachment) {
      this.status = status;
      this.attachment = attachment;
    }

    public boolean isSuccess() { return status == AccessStatus.OPENED; }

    private static AttachmentAccessResult opened(OpenedAttachment attachment) {
      return new AttachmentAccessResult(AccessStatus.OPENED, attachment);
    }

    private static AttachmentAccessResult failure(AccessStatus status) {
      return new AttachmentAccessResult(status, null);
    }
  }

  public static final class OpenedAttachment implements AutoCloseable {
    private final AuthenticatedPartReader.VerifiedPlaintext plaintext;
    public final String contentType;
    public final FileKind fileKind;

    private OpenedAttachment(AuthenticatedPartReader.VerifiedPlaintext plaintext,
                             String contentType, FileKind fileKind) {
      this.plaintext = plaintext;
      this.contentType = contentType;
      this.fileKind = fileKind;
    }

    @NonNull public InputStream openStream() throws IOException { return plaintext.openStream(); }

    @Override public void close() throws IOException { plaintext.close(); }
  }

  private static final class AttachmentMetadata {
    final String contentType;
    final String path;
    final String thumbnailPath;
    final long dataSize;

    AttachmentMetadata(String contentType, String path, String thumbnailPath, long dataSize) {
      this.contentType = contentType;
      this.path = path;
      this.thumbnailPath = thumbnailPath;
      this.dataSize = dataSize;
    }
  }

  private static final class PathResult {
    final AccessStatus status;
    final File file;

    private PathResult(AccessStatus status, File file) {
      this.status = status;
      this.file = file;
    }

    static PathResult opened(File file) { return new PathResult(AccessStatus.OPENED, file); }
    static PathResult failure(AccessStatus status) { return new PathResult(status, null); }
  }

  private static final class MetadataLookup {
    final AccessStatus status;
    final AttachmentMetadata metadata;

    private MetadataLookup(AccessStatus status, AttachmentMetadata metadata) {
      this.status = status;
      this.metadata = metadata;
    }

    static MetadataLookup success(AttachmentMetadata metadata) {
      return new MetadataLookup(AccessStatus.OPENED, metadata);
    }

    static MetadataLookup failure(AccessStatus status) {
      return new MetadataLookup(status, null);
    }
  }
  public static final class AttachmentRecord {
    public final long id, mmsId, dataSize, uniqueId; public final int sequence, encrypted, transferState;
    public final String contentType, name, disposition, location; public final float aspectRatio;
    final String path, thumbnailPath;
    public final byte[] digest;
    private AttachmentRecord(long id, long mmsId, int sequence, String contentType, String name,
                             String disposition, String location, String path, long dataSize,
                             String thumbnailPath, float aspectRatio, long uniqueId, byte[] digest,
                             int encrypted, int transferState) {
      this.id=id; this.mmsId=mmsId; this.sequence=sequence; this.contentType=contentType; this.name=name;
      this.disposition=disposition; this.location=location; this.path=path; this.dataSize=dataSize;
      this.thumbnailPath=thumbnailPath; this.aspectRatio=aspectRatio; this.uniqueId=uniqueId;
      this.digest=digest == null ? null : digest.clone(); this.encrypted=encrypted; this.transferState=transferState;
    }
  }
}
