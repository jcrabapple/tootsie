package org.joinmastodon.android.ui.displayitems;

import android.content.Context;
import android.view.ViewGroup;
import android.widget.TextView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.ai.FeedbackTracker;

/**
 * A "Not interested" button shown below posts in the Personal timeline.
 * When tapped, records a dismissal via FeedbackTracker and removes the post from the list.
 */
public class NotInterestedStatusDisplayItem extends StatusDisplayItem {
	public final String statusId;
	public final String accountId;
	public final Runnable onDismiss;

	public NotInterestedStatusDisplayItem(String parentID, Callbacks callbacks, Context context,
										  String statusId, String accountId, Runnable onDismiss) {
		super(parentID, callbacks, context);
		this.statusId = statusId;
		this.accountId = accountId;
		this.onDismiss = onDismiss;
	}

	@Override
	public Type getType() {
		return Type.NOT_INTERESTED;
	}

	public static class Holder extends StatusDisplayItem.Holder<NotInterestedStatusDisplayItem> {
		private final TextView button;

		public Holder(Context context, ViewGroup parent) {
			super(context, R.layout.display_item_not_interested, parent);
			button = findViewById(R.id.not_interested_button);
		}

		@Override
		public void onBind(NotInterestedStatusDisplayItem item) {
			button.setOnClickListener(v -> {
				FeedbackTracker.record(item.accountId, item.statusId, "dismiss");
				if (item.onDismiss != null) {
					item.onDismiss.run();
				}
			});
		}

		@Override
		public boolean isEnabled() {
			return false;
		}
	}
}
