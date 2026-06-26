package org.joinmastodon.android.api.requests.collections;

import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.model.Collection;

/**
 * PATCH /api/v1/collections/:id
 *
 * Updates a collection's metadata.
 */
public class UpdateCollection extends MastodonAPIRequest<Collection> {
    public UpdateCollection(String id, String name, String description, String language, String tagName) {
        super(HttpMethod.PATCH, "/collections/" + id, Collection.class);
        setRequestBody(new Request(name, description, language, tagName));
    }

    private static class Request {
        public String name;
        public String description;
        public String language;
        public String tagName;

        public Request(String name, String description, String language, String tagName) {
            this.name = name;
            this.description = description;
            this.language = language;
            this.tagName = tagName;
        }
    }
}
