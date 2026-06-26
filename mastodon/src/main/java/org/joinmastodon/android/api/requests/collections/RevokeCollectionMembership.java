package org.joinmastodon.android.api.requests.collections;

import org.joinmastodon.android.api.ResultlessMastodonAPIRequest;

/**
 * POST /api/v1/collections/:id/items/:account_id/revoke
 *
 * Self-removal from a collection. This is the safety primitive: any
 * account can revoke their own inclusion at any time, regardless of who
 * added them. The 4.6 spec makes this a separate endpoint (vs DELETE)
 * so the server can attribute the removal correctly and not notify the
 * owner as if it were a removal-by-owner.
 */
public class RevokeCollectionMembership extends ResultlessMastodonAPIRequest {
    public RevokeCollectionMembership(String collectionId, String accountId) {
        super(HttpMethod.POST, "/collections/" + collectionId + "/items/" + accountId + "/revoke");
    }
}