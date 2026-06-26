package org.joinmastodon.android.ui.displayitems;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.model.Collection;

/**
 * Compact collection card for the Featured tab and other list surfaces.
 *
 * <p>Shows the collection title, member count, optional description snippet,
 * and optional hashtag. Tapping opens {@link org.joinmastodon.android.fragments.CollectionDetailFragment}.
 * This is the row used inside {@link org.joinmastodon.android.fragments.ProfileFeaturedFragment}
 * for the Collections section.
 *
 * <p>Contrast with {@link CollectionHeaderStatusDisplayItem}, which is the
 * full metadata header at the top of the detail view.
 */
public class CollectionCardStatusDisplayItem extends StatusDisplayItem{
	public final Collection collection;

	public CollectionCardStatusDisplayItem(String parentID, Callbacks callbacks, Context context, Collection collection){
		super(parentID, callbacks, context);
		this.collection=collection;
	}

	@Override
	public Type getType(){
		return Type.COLLECTION_CARD;
	}

	public static class Holder extends StatusDisplayItem.Holder<CollectionCardStatusDisplayItem>{
		private final TextView title, memberCount, description, hashtag;

		public Holder(Context context, ViewGroup parent){
			super(context, R.layout.item_collection_card, parent);
			title=findViewById(R.id.collection_title);
			memberCount=findViewById(R.id.collection_member_count);
			description=findViewById(R.id.collection_description);
			hashtag=findViewById(R.id.collection_hashtag);
		}

		@Override
		public void onBind(CollectionCardStatusDisplayItem item){
			Collection c=item.collection;
			title.setText(c.title);

			memberCount.setText(itemView.getResources().getQuantityString(
					R.plurals.collection_member_count, c.accountsCount, c.accountsCount));

			if(!TextUtils.isEmpty(c.description)){
				description.setText(c.description);
				description.setVisibility(View.VISIBLE);
			}else{
				description.setVisibility(View.GONE);
			}

			if(c.tag!=null && !TextUtils.isEmpty(c.tag.name)){
				hashtag.setText("#"+c.tag.name);
				hashtag.setVisibility(View.VISIBLE);
			}else{
				hashtag.setVisibility(View.GONE);
			}
		}
	}
}
