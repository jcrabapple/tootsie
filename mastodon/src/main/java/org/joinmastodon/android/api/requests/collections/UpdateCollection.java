package org.joinmastodon.android.api.requests.collections;

import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.model.Collection;

/**
 * PATCH /api/v1/collections/:id
 *
 * Updates a collection's metadata. Per the 4.6 spec, title/description
 * updates notify all members, so users see when a collection they are in
 * has been reworded. Only owner can edit.
 */
public class UpdateCollection extends MastodonAPIRequest<Collection> {
    public UpdateCollection(String id, String title, String description, String language, String tag) {
        super(HttpMethod.PATCH, "/collections/" + id, Collection.class);
        setRequestBody(new Request(title, description, language, tag));
    }

    private static class Request {
        public String title;
        public String description;
        public String language;
        public String tag;

        public Request(String title, String description, String language, String tag) {
            this.title = title;
            this.description = description;
            this.language = language;
            this.tag = tag;
        }
    }
}