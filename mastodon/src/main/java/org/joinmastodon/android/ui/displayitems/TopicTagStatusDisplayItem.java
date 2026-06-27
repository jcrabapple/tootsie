package org.joinmastodon.android.ui.displayitems;

import android.content.Context;
import android.view.ViewGroup;
import android.widget.TextView;

import org.joinmastodon.android.R;

import java.util.List;

/**
 * A small chip shown above a post in the Personal timeline indicating
 * which AI topic(s) matched this post.
 */
public class TopicTagStatusDisplayItem extends StatusDisplayItem {
	public final List<String> topics;

	public TopicTagStatusDisplayItem(String parentID, Callbacks callbacks, Context context, List<String> topics) {
		super(parentID, callbacks, context);
		this.topics = topics;
	}

	@Override
	public Type getType() {
		return Type.TOPIC_TAG;
	}

	public static class Holder extends StatusDisplayItem.Holder<TopicTagStatusDisplayItem> {
		private final TextView label;

		public Holder(Context context, ViewGroup parent) {
			super(context, R.layout.display_item_topic_tag, parent);
			label = findViewById(R.id.topic_label);
		}

		@Override
		public void onBind(TopicTagStatusDisplayItem item) {
			// Show topics as "✦ Topic1 · Topic2"
			StringBuilder sb = new StringBuilder("\u2726 ");
			for (int i = 0; i < item.topics.size(); i++) {
				if (i > 0) sb.append(" \u00B7 ");
				sb.append(item.topics.get(i));
			}
			label.setText(sb);
		}

		@Override
		public boolean isEnabled() {
			return false;
		}
	}
}
