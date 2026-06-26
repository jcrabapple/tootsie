package org.joinmastodon.android.api.requests.collections;

import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.model.Collection;

/**
 * GET /api/v1/collections/:id
 *
 * Fetch a single collection with full member details. The 4.6 spec says
 * detail responses include the {@code accounts} array; list responses do not.
 */
public class GetCollection extends MastodonAPIRequest<Collection> {
    public GetCollection(String id) {
        super(HttpMethod.GET, "/collections/" + id, Collection.class);
    }
}