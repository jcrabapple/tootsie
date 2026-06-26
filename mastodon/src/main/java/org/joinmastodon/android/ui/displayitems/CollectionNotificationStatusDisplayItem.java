package org.joinmastodon.android.ui.displayitems;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.style.StyleSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.requests.collections.GetCollection;
import org.joinmastodon.android.api.requests.collections.RevokeCollectionMembership;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.events.CollectionUpdatedEvent;
import org.joinmastodon.android.model.Collection;
import org.joinmastodon.android.model.NotificationType;
import org.joinmastodon.android.model.viewmodel.NotificationViewModel;
import org.joinmastodon.android.ui.M3AlertDialogBuilder;

import me.grishka.appkit.Nav;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.utils.V;

/**
 * TOOTSIE: FEP-7aa9 / Mastodon 4.6 — notification row for
 * {@code ADDED_TO_COLLECTION} and {@code COLLECTION_UPDATE}.
 *
 * <p>Renders a title line (e.g. "X added you to the collection Y" or
 * "X updated the collection Y"), a collection description snippet (if
 * available from cache), and two action buttons:
 * <ul>
 *   <li><b>View</b> — opens {@link org.joinmastodon.android.fragments.CollectionDetailFragment}</li>
 *   <li><b>Remove me</b> (ADDED_TO_COLLECTION only) — calls
 *       {@link RevokeCollectionMembership} with a confirmation dialog</li>
 * </ul>
 *
 * <p>If the collection isn't in the in-memory cache, the View button still
 * works — it passes the collectionId to the detail fragment which fetches
 * it via {@link GetCollection}.
 */
public class CollectionNotificationStatusDisplayItem extends StatusDisplayItem{
	private final NotificationViewModel notification;
	private final String accountID;
	private final boolean isAdded;
	private CharSequence text;
	private Collection cachedCollection;

	public CollectionNotificationStatusDisplayItem(String parentID, Callbacks callbacks, Context context, NotificationViewModel notification, String accountID){
		super(parentID, callbacks, context);
		this.notification=notification;
		this.accountID=accountID;
		this.isAdded=notification.notification.type==NotificationType.ADDED_TO_COLLECTION;

		// Try to get collection from cache
		String collectionId=notification.notification.collectionId;
		if(collectionId!=null){
			cachedCollection=AccountSessionManager.get(accountID).getCacheController().getCollection(collectionId);
		}

		// Build the notification text
		String accountName=notification.accounts!=null && !notification.accounts.isEmpty()
				? notification.accounts.get(0).displayName
				: "Someone";

		if(cachedCollection!=null){
			String title=cachedCollection.name;
			if(isAdded){
				text=context.getString(R.string.added_to_collection, bold(accountName), bold(title));
			}else{
				text=context.getString(R.string.collection_updated_notification, bold(accountName), bold(title));
			}
		}else{
			// No cached collection — show generic text
			if(isAdded){
				text=context.getString(R.string.added_to_collection, bold(accountName), bold("a collection"));
			}else{
				text=context.getString(R.string.collection_updated_notification, bold(accountName), bold("a collection"));
			}
		}
	}

	private SpannableStringBuilder bold(String s){
		SpannableStringBuilder ssb=new SpannableStringBuilder(s);
		ssb.setSpan(new StyleSpan(Typeface.BOLD), 0, s.length(), 0);
		return ssb;
	}

	@Override
	public Type getType(){
		return Type.COLLECTION_NOTIFICATION;
	}

	public static class Holder extends StatusDisplayItem.Holder<CollectionNotificationStatusDisplayItem>{
		private final TextView text;
		private final Button viewBtn, removeBtn;

		public Holder(Context context, ViewGroup parent){
			super(context, R.layout.item_collection_notification, parent);
			text=findViewById(R.id.text);
			viewBtn=findViewById(R.id.btn_view);
			removeBtn=findViewById(R.id.btn_remove);
		}

		@Override
		public void onBind(CollectionNotificationStatusDisplayItem item){
			text.setText(item.text);

			viewBtn.setOnClickListener(v->{
				String collectionId=item.notification.notification.collectionId;
				if(collectionId!=null){
					android.os.Bundle args=new android.os.Bundle();
					args.putString("account", item.accountID);
					args.putString("collectionID", collectionId);
					if(item.cachedCollection!=null){
						args.putParcelable("collection", org.parceler.Parcels.wrap(item.cachedCollection));
					}
					Nav.go((android.app.Activity) v.getContext(),
							org.joinmastodon.android.fragments.CollectionDetailFragment.class, args);
				}
			});

			if(item.isAdded){
				removeBtn.setVisibility(View.VISIBLE);
				removeBtn.setOnClickListener(v->{
					new M3AlertDialogBuilder(v.getContext())
							.setTitle(R.string.collection_revoke_membership)
							.setMessage(v.getContext().getString(R.string.collection_revoke_membership))
							.setPositiveButton(R.string.remove, (dlg, which)->{
								String collectionId=item.notification.notification.collectionId;
								String selfId=AccountSessionManager.get(item.accountID).self.id;
								if(collectionId!=null){
									new RevokeCollectionMembership(collectionId, selfId)
											.setCallback(new Callback<>(){
												@Override
												public void onSuccess(Void result){
													removeBtn.setVisibility(View.GONE);
												}

												@Override
												public void onError(ErrorResponse error){
													error.showToast((android.app.Activity) v.getContext());
												}
											})
											.exec(item.accountID);
								}
							})
							.setNegativeButton(R.string.cancel, null)
							.show();
				});
			}else{
				removeBtn.setVisibility(View.GONE);
			}
		}
	}
}
