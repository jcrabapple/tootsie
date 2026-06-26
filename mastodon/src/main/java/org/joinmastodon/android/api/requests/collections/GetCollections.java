package org.joinmastodon.android.api.requests.collections;

import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.model.Collection;

import java.util.List;

/**
 * GET /api/v1/collections
 *
 * Lists collections owned by the authenticated user.
 */
public class GetCollections extends MastodonAPIRequest<List<Collection>> {
    public GetCollections() {
        super(HttpMethod.GET, "/collections", new TypeToken<>() {});
    }
}