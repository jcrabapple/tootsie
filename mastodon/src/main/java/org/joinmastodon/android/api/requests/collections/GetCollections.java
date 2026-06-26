package org.joinmastodon.android.api.requests.collections;

import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.model.Collection;

import java.util.List;

/**
 * GET /api/v1/accounts/:id/collections
 *
 * Lists collections owned by the given account.
 * Response is wrapped: {"collections": [...]}
 */
public class GetCollections extends MastodonAPIRequest<GetCollections.Response> {

    public GetCollections(String accountId) {
        super(HttpMethod.GET, "/accounts/" + accountId + "/collections", new TypeToken<>() {});
    }

    public static class Response {
        public List<Collection> collections;
    }
}
