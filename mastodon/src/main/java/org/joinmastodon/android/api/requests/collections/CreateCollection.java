package org.joinmastodon.android.api.requests.collections;

import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.model.Collection;

/**
 * POST /api/v1/collections
 *
 * Creates a new collection. Per FEP-7aa9, accounts must opt into "Feature
 * me in discovery experiences" to be eligible for inclusion; opted-in
 * accounts are notified when added.
 */
public class CreateCollection extends MastodonAPIRequest<Collection> {
    public CreateCollection(String title, String description, String language, String tag) {
        super(HttpMethod.POST, "/collections", Collection.class);
        setRequestBody(new Request(title, description, language, tag));
    }

    private static class Request {
        public String title;
        public String description;
        public String language; // ISO 639-1 (e.g. "en")
        public String tag; // hashtag name without leading #

        public Request(String title, String description, String language, String tag) {
            this.title = title;
            this.description = description;
            this.language = language;
            this.tag = tag;
        }
    }
}