package org.joinmastodon.android.api.requests.collections;

import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.model.Collection;

import java.util.List;

/**
 * GET /api/v1/accounts/:id/in_collections
 *
 * Lists collections the given account has been added to (visibility-aware).
 * Useful for an "incoming" review surface where a user can audit their
 * appearances in other people's collections.
 */
public class GetAccountInCollections extends MastodonAPIRequest<List<Collection>> {
    public GetAccountInCollections(String accountId) {
        super(HttpMethod.GET, "/accounts/" + accountId + "/in_collections", new TypeToken<>() {});
    }
}