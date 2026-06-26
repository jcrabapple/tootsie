package org.joinmastodon.android.api.requests.collections;

import org.joinmastodon.android.api.ResultlessMastodonAPIRequest;

/**
 * DELETE /api/v1/collections/:id
 *
 * Deletes a collection. Only the owner may delete.
 */
public class DeleteCollection extends ResultlessMastodonAPIRequest {
    public DeleteCollection(String id) {
        super(HttpMethod.DELETE, "/collections/" + id);
    }
}