package org.joinmastodon.android.api.requests.collections;

import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Collection;

import java.util.List;

/**
 * GET /api/v1/collections/:id
 *
 * Fetch a single collection with full member details.
 * Response is wrapped: {"collection": {...}, "accounts": [...]}
 */
public class GetCollection extends MastodonAPIRequest<GetCollection.Response> {
    public GetCollection(String id) {
        super(HttpMethod.GET, "/collections/" + id, new TypeToken<>() {});
    }

    public static class Response {
        public Collection collection;
        public List<Account> accounts;
    }
}
