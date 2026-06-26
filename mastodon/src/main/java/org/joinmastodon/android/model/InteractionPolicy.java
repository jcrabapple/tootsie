package org.joinmastodon.android.model;

import com.google.gson.annotations.SerializedName;

import org.joinmastodon.android.api.ObjectValidationException;
import org.parceler.Parcel;

/**
 * The {@code interactionPolicy} ActivityPub property (FEP-7aa9, Mastodon
 * 4.6).
 *
 * <p>This object tells clients how they may include this account in
 * Fediverse Collections (curated account lists for discovery). The {@link
 * #canFeature} field determines the audience for which collection
 * inclusion requests are <em>automatically approved</em>.
 *
 * <p>Note: this is independent of the user's "Feature me in discovery
 * experiences" profile preference. If that preference is OFF, the account
 * cannot be added to any collection regardless of {@code canFeature}.
 * This object only describes what happens when the toggle is on.
 */
@Parcel
public class InteractionPolicy extends BaseModel {
    public CanFeature canFeature;

    @Override
    public void postprocess() throws ObjectValidationException {
        super.postprocess();
        if (canFeature == null) {
            // Default to "manual approval" — the safest default. A server
            // omitting the field should not implicitly grant auto-approval.
            canFeature = CanFeature.MANUAL;
        }
    }

    @Override
    public String toString() {
        return "InteractionPolicy{canFeature=" + canFeature + "}";
    }

    public enum CanFeature {
        /**
         * Anyone can add this account to a collection. Inclusion is
         * automatic; the account receives a notification and may still
         * self-revoke.
         */
        @SerializedName("public")
        PUBLIC,

        /**
         * Only accounts this profile follows can add it to a collection.
         * Non-followers' requests are rejected (or queued for manual
         * approval, depending on server behavior).
         */
        @SerializedName("followers")
        FOLLOWERS,

        /**
         * All collection additions require explicit approval by the
         * account owner. Requests are queued; a notification prompts the
         * owner to accept or reject.
         */
        @SerializedName("manual")
        MANUAL
    }
}