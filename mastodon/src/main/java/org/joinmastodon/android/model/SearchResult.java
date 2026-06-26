package org.joinmastodon.android.model;

import org.joinmastodon.android.api.ObjectValidationException;
import org.joinmastodon.android.api.RequiredField;
import org.joinmastodon.android.model.viewmodel.AccountViewModel;

public class SearchResult extends BaseModel implements DisplayItemsParent{
	public Account account;
	public Hashtag hashtag;
	public Status status;
	// TOOTSIE: FEP-7aa9 / Mastodon 4.6 — Collections in the Featured tab
	public Collection collection;
	@RequiredField
	public Type type;

	public transient String id;
	public transient boolean firstInSection;

	public SearchResult(){}

	public SearchResult(Account acc){
		account=acc;
		type=Type.ACCOUNT;
		generateID();
	}

	public SearchResult(Hashtag tag){
		hashtag=tag;
		type=Type.HASHTAG;
		generateID();
	}

	public SearchResult(Status status){
		this.status=status;
		type=Type.STATUS;
		generateID();
	}

	// TOOTSIE: FEP-7aa9 / Mastodon 4.6 — Collections in the Featured tab
	public SearchResult(Collection collection){
		this.collection=collection;
		type=Type.COLLECTION;
		generateID();
	}

	@Override
	public String getID(){
		return id;
	}

	@Override
	public String getAccountID(){
		if(type==Type.STATUS)
			return status.getAccountID();
		return null;
	}

	@Override
	public void postprocess() throws ObjectValidationException{
		super.postprocess();
		if(account!=null)
			account.postprocess();
		if(hashtag!=null)
			hashtag.postprocess();
		if(status!=null)
			status.postprocess();
		// TOOTSIE: FEP-7aa9 / Mastodon 4.6
		if(collection!=null)
			collection.postprocess();
		generateID();
	}

	private void generateID(){
		id=switch(type){
			case ACCOUNT -> "acc_"+account.id;
			case HASHTAG -> "tag_"+hashtag.name.hashCode();
			case STATUS -> "post_"+status.id;
			// TOOTSIE: FEP-7aa9 / Mastodon 4.6
			case COLLECTION -> "col_"+collection.id;
		};
	}

	public enum Type{
		ACCOUNT,
		HASHTAG,
		STATUS,
		// TOOTSIE: FEP-7aa9 / Mastodon 4.6 — Collections in the Featured tab
		COLLECTION
	}
}
