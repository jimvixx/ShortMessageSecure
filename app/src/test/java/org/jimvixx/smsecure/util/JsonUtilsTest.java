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

package org.jimvixx.smsecure.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.databind.type.TypeFactory;

import org.jimvixx.smsecure.database.documents.IdentityKeyMismatch;
import org.jimvixx.smsecure.database.documents.IdentityKeyMismatchList;
import org.jimvixx.smsecure.database.documents.NetworkFailure;
import org.jimvixx.smsecure.database.documents.NetworkFailureList;
import org.junit.Test;
import org.whispersystems.libsignal.IdentityKey;

import java.util.Collections;
import java.util.LinkedHashSet;

import static org.assertj.core.api.Assertions.assertThat;

public class JsonUtilsTest {

  @Test
  public void networkFailureDocumentKeepsCompactSchema() throws Exception {
    NetworkFailureList document = new NetworkFailureList(
            Collections.singletonList(new NetworkFailure(42L)));

    assertThat(JsonUtils.toJson(document)).isEqualTo("{\"l\":[{\"r\":42}]}");
  }

  @Test
  public void persistedDocumentsIgnoreUnknownFields() throws Exception {
    String serialized = "{\"l\":[{\"r\":42,\"legacy\":true}],\"future\":\"ignored\"}";

    NetworkFailureList document = JsonUtils.fromJson(serialized, NetworkFailureList.class);

    assertThat(document.getList()).containsExactly(new NetworkFailure(42L));
  }

  @Test
  public void identityKeyMismatchRoundTripsWithCustomEncoding() throws Exception {
    IdentityKey identityKey = new IdentityKey(identityKeyBytes(), 0);
    IdentityKeyMismatchList document = new IdentityKeyMismatchList(
            Collections.singletonList(new IdentityKeyMismatch(7L, identityKey)));

    String serialized = JsonUtils.toJson(document);
    IdentityKeyMismatchList restored =
            JsonUtils.fromJson(serialized, IdentityKeyMismatchList.class);

    JsonNode json = JsonUtils.getMapper().readTree(serialized);
    JsonNode mismatch = json.path("m").get(0);
    assertThat(mismatch.path("r").asLong()).isEqualTo(7L);
    assertThat(mismatch.path("k").asText())
            .isEqualTo(Base64.encodeBytes(identityKey.serialize()));
    assertThat(restored.getList()).containsExactly(new IdentityKeyMismatch(7L, identityKey));
  }

  @Test
  public void recentEmojiCollectionPreservesOrderAndUnicode() throws Exception {
    CollectionType collectionType = TypeFactory.defaultInstance()
            .constructCollectionType(LinkedHashSet.class, String.class);
    LinkedHashSet<String> emojis = JsonUtils.getMapper().readValue(
            "[\"😀\",\"🚀\",\"📱\"]", collectionType);

    assertThat(emojis).containsExactly("😀", "🚀", "📱");
    assertThat(JsonUtils.toJson(emojis)).isEqualTo("[\"😀\",\"🚀\",\"📱\"]");
  }

  private static byte[] identityKeyBytes() {
    byte[] bytes = new byte[33];
    bytes[0] = 0x05;
    return bytes;
  }
}
