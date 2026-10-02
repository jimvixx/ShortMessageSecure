package org.jimvixx.smsecure.jobs.persistence;

import android.content.Context;
import android.util.Base64;
import org.jimvixx.smsecure.jobs.StableJobCodec;

import org.jimvixx.smsecure.crypto.MasterCipher;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.util.ParcelUtil;
import org.whispersystems.jobqueue.EncryptionKeys;
import org.whispersystems.jobqueue.Job;
import org.whispersystems.jobqueue.persistence.JavaJobSerializer;
import org.whispersystems.jobqueue.persistence.JobSerializer;
import org.whispersystems.libsignal.InvalidMessageException;

import java.io.IOException;

public class EncryptingJobSerializer implements JobSerializer {

  private final JavaJobSerializer delegate;
  private final Context context;
  private static final String PREFIX = "SMSecureJob:";

  public EncryptingJobSerializer(Context context) {
    this.context = context;
    this.delegate = new JavaJobSerializer();
  }

  @Override
  public String serialize(Job job) throws IOException {
    String plaintext = PREFIX + Base64.encodeToString(StableJobCodec.encode(job), Base64.NO_WRAP);

    if (job.getEncryptionKeys() != null) {
      MasterSecret masterSecret = ParcelUtil.deserialize(job.getEncryptionKeys().getEncoded(),
              MasterSecret.CREATOR);
      MasterCipher masterCipher = new MasterCipher(masterSecret);

      return masterCipher.encryptBody(plaintext);
    } else {
      return plaintext;
    }
  }

  @Override
  public Job deserialize(EncryptionKeys keys, boolean encrypted, String serialized) throws IOException {
    try {
      String plaintext;

      if (encrypted) {
        MasterSecret masterSecret = ParcelUtil.deserialize(keys.getEncoded(), MasterSecret.CREATOR);
        MasterCipher masterCipher = new MasterCipher(masterSecret);
        plaintext = masterCipher.decryptBody(serialized);
      } else {
        plaintext = serialized;
      }

      if (plaintext.startsWith(PREFIX)) {
        try {
          return StableJobCodec.decode(context, Base64.decode(plaintext.substring(PREFIX.length()), Base64.NO_WRAP));
        } catch (IllegalArgumentException e) {
          throw new IOException("Invalid job encoding", e);
        }
      }
      // Read compatible legacy records; PersistentStorage rewrites them before execution.
      return delegate.deserialize(keys, encrypted, plaintext);
    } catch (InvalidMessageException e) {
      throw new IOException(e);
    }
  }
}
