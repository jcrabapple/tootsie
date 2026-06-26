package org.joinmastodon.android.api.requests.collections;

import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.model.Account;

import java.util.List;

/**
 * GET /api/v1/collections/:id/items
 *
 * Lists members of a collection. Unlike {@link GetCollection}, this
 * endpoint accepts pagination and returns just the accounts.
 */
public class GetCollectionItems extends MastodonAPIRequest<List<Account>> {
    public GetCollectionItems(String id, String maxId, int limit) {
        super(HttpMethod.GET, "/collections/" + id + "/items", new TypeToken<>() {});
        if (maxId != null) {
            addQueryParameter("max_id", maxId);
        }
        addQueryParameter("limit", String.valueOf(limit));
    }

    public GetCollectionItems(String id, String maxId) {
        this(id, maxId, 20);
    }

    public GetCollectionItems(String id) {
        this(id, null);
    }
}