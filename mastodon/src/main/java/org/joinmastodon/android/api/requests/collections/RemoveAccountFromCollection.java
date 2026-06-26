package org.joinmastodon.android.api.requests.collections;

import org.joinmastodon.android.api.ResultlessMastodonAPIRequest;

/**
 * DELETE /api/v1/collections/:id/items/:account_id
 *
 * Removes an account from a collection. Only the owner of the collection
 * may remove members. (To leave a collection voluntarily, use
 * {@link RevokeCollectionMembership} instead.)
 */
public class RemoveAccountFromCollection extends ResultlessMastodonAPIRequest {
    public RemoveAccountFromCollection(String collectionId, String accountId) {
        super(HttpMethod.DELETE, "/collections/" + collectionId + "/items/" + accountId);
    }
}