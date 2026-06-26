package org.joinmastodon.android.api.requests.collections;

import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.model.Collection;

import java.util.List;

/**
 * GET /api/v1/accounts/:id/collections
 *
 * Lists collections that include the given account. Returns only
 * collections where the viewer is the owner, or where the account is
 * public and has been added by someone else.
 */
public class GetAccountCollections extends MastodonAPIRequest<List<Collection>> {
    public GetAccountCollections(String accountId) {
        super(HttpMethod.GET, "/accounts/" + accountId + "/collections", new TypeToken<>() {});
    }
}