package org.joinmastodon.android.ui.displayitems;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.model.Hashtag;
import org.joinmastodon.android.utils.TypedObjectPool;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

/**
 * Display item for a Collection's metadata header in {@code CollectionDetailFragment}.

 * <p>Renders the collection title, description (or "No description" placeholder),
 * hashtag badge (if present), member count, language code, and creation/update
 * timestamps. This is NOT interactive — tapping it is a no-op. The header sits
 * above the member account list.

 * <p>Layout: {@code R.layout.display_item_collection_header}. Uses a simple
 * vertical stack with text views for each metadata field.
 */
public class CollectionHeaderStatusDisplayItem extends StatusDisplayItem {
	public final String title, description, subtitle, hashtagText;
	public final int memberCount;
	public final String language;
	public final Instant createdAt, updatedAt;

	public CollectionHeaderStatusDisplayItem(
			String parentID,
			Callbacks callbacks,
			Context context,
			String title,
			String description,
			Hashtag tag,
			int memberCount,
			String language,
			Instant createdAt,
			Instant updatedAt,
			String accountID
	) {
		super(parentID, callbacks, context);
		this.title = title;
		this.description = description;
		this.memberCount = memberCount;
		this.language = language;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;

		// Subtitle: member count + language
		StringBuilder sb = new StringBuilder();
		sb.append(context.getResources().getQuantityString(
				R.plurals.collection_member_count,
				memberCount,
				memberCount));
		if (!TextUtils.isEmpty(language)) {
			sb.append(" · ");
			sb.append(language.toUpperCase());
		}
		this.subtitle = sb.toString();

		// Hashtag badge
		this.hashtagText = (tag != null) ? "#" + tag.name : null;
	}

	@Override
	public Type getType() {
		return Type.COLLECTION_HEADER;
	}

	public static class Holder extends StatusDisplayItem.Holder<CollectionHeaderStatusDisplayItem> {
		private final TextView titleView, descriptionView, subtitleView, hashtagView;
		private final TextView createdAtView, updatedAtView;

		public Holder(Context context, ViewGroup parent) {
			super(context, R.layout.display_item_collection_header, parent);
			titleView = findViewById(R.id.collection_title);
			descriptionView = findViewById(R.id.collection_description);
			subtitleView = findViewById(R.id.collection_subtitle);
			hashtagView = findViewById(R.id.collection_hashtag);
			createdAtView = findViewById(R.id.collection_created_at);
			updatedAtView = findViewById(R.id.collection_updated_at);
		}

		@Override
		public void onBind(CollectionHeaderStatusDisplayItem item) {
			titleView.setText(item.title);

			if (!TextUtils.isEmpty(item.description)) {
				descriptionView.setText(item.description);
				descriptionView.setVisibility(View.VISIBLE);
			} else {
				descriptionView.setText(itemView.getResources().getString(R.string.collection_no_description));
				descriptionView.setVisibility(View.VISIBLE);
			}

			subtitleView.setText(item.subtitle);

			if (item.hashtagText != null) {
				hashtagView.setText(item.hashtagText);
				hashtagView.setVisibility(View.VISIBLE);
			} else {
				hashtagView.setVisibility(View.GONE);
			}

			// Timestamps
			if (item.createdAt != null) {
				DateTimeFormatter fmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
						.withZone(ZoneId.systemDefault());
				createdAtView.setText(itemView.getResources().getString(
						R.string.collection_created, fmt.format(item.createdAt)));
				createdAtView.setVisibility(View.VISIBLE);
			} else {
				createdAtView.setVisibility(View.GONE);
			}
			if (item.updatedAt != null) {
				DateTimeFormatter fmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
						.withZone(ZoneId.systemDefault());
				updatedAtView.setText(itemView.getResources().getString(
						R.string.collection_updated, fmt.format(item.updatedAt)));
				updatedAtView.setVisibility(View.VISIBLE);
			} else {
				updatedAtView.setVisibility(View.GONE);
			}
		}

		@Override
		public boolean isEnabled() {
			return false; // Header is not clickable
		}
	}
}