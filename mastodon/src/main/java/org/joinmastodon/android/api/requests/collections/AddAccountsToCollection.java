package org.joinmastodon.android.api.requests.collections;

import org.joinmastodon.android.api.ResultlessMastodonAPIRequest;

import java.nio.charset.StandardCharsets;
import java.util.Collection;

import okhttp3.FormBody;

/**
 * POST /api/v1/collections/:id/items
 *
 * Adds accounts to a collection. The 4.6 spec caps each collection at 25
 * members; the server returns 422 if exceeded. Adding an account triggers
 * a notification to that account (if they have opted in) and to the
 * collection owner.
 */
public class AddAccountsToCollection extends ResultlessMastodonAPIRequest {
    public AddAccountsToCollection(String collectionId, Collection<String> accountIds) {
        super(HttpMethod.POST, "/collections/" + collectionId + "/items");
        FormBody.Builder builder = new FormBody.Builder(StandardCharsets.UTF_8);
        for (String id : accountIds) {
            builder.add("account_ids[]", id);
        }
        setRequestBody(builder.build());
    }
}