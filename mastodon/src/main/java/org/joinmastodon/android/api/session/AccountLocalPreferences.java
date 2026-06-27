package org.joinmastodon.android.api.session;

import static org.joinmastodon.android.GlobalUserPreferences.enumValue;
import static org.joinmastodon.android.GlobalUserPreferences.fromJson;
import static org.joinmastodon.android.api.MastodonAPIController.gson;

import android.content.SharedPreferences;

import androidx.annotation.StringRes;

import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.R;
import org.joinmastodon.android.model.AITopic;
import org.joinmastodon.android.model.ContentType;
import org.joinmastodon.android.model.Emoji;
import org.joinmastodon.android.model.PushSubscription;
import org.joinmastodon.android.model.TimelineDefinition;

import java.lang.reflect.Type;
import java.util.ArrayList;

public class AccountLocalPreferences{
	private final SharedPreferences prefs;

	public boolean serverSideFiltersSupported;


	// MOSHIDON:
	public boolean showReplies;
	public boolean showBoosts;
	public ArrayList<String> recentLanguages;
	public boolean bottomEncoding;
	public ContentType defaultContentType;
	public boolean contentTypesEnabled;
	public ArrayList<TimelineDefinition> timelines;
	public boolean localOnlySupported;
	public boolean glitchInstance;
	public String publishButtonText;
	public String timelineReplyVisibility; // akkoma-only
	public boolean keepOnlyLatestNotification;
	public boolean emojiReactionsEnabled;
	public ShowEmojiReactions showEmojiReactions;
	public ColorPreference color;
	public ArrayList<Emoji> recentCustomEmoji;
	public boolean preReplySheet;

	// TOOTSIE: AI Personalization per-account config
	public String aiApiUrl;           // default: "https://openrouter.ai/api/v1"
	public String aiApiKey;           // user's OpenRouter API key
	public String aiModel;            // model ID, e.g. "openai/gpt-4o-mini"
	public int aiPostCount;           // 5-25, default 10
	public ArrayList<AITopic> aiTopics;       // inferred + user topics
	public long aiTopicsLastUpdated;          // timestamp of last inference (0 = never)


	// MOSHIDON: this is also ours
	private final static Type recentLanguagesType=new TypeToken<ArrayList<String>>() {}.getType();
	private final static Type timelinesType=new TypeToken<ArrayList<TimelineDefinition>>() {}.getType();
	private final static Type recentCustomEmojiType=new TypeToken<ArrayList<Emoji>>() {}.getType();
	private final static Type notificationFiltersType = new TypeToken<PushSubscription.Alerts>() {}.getType();
	private final static Type aiTopicsType=new TypeToken<ArrayList<AITopic>>() {}.getType();
	public PushSubscription.Alerts notificationFilters;


	public AccountLocalPreferences(SharedPreferences prefs, AccountSession session){
		this.prefs=prefs;
		serverSideFiltersSupported=prefs.getBoolean("serverSideFilters", false);

		// MOSHIDON:
		showReplies=prefs.getBoolean("showReplies", true);
		showBoosts=prefs.getBoolean("showBoosts", true);
		recentLanguages=fromJson(prefs.getString("recentLanguages", null), recentLanguagesType, new ArrayList<>());
		bottomEncoding=prefs.getBoolean("bottomEncoding", false);
		defaultContentType=enumValue(ContentType .class, prefs.getString("defaultContentType", ContentType.PLAIN.name()));
		contentTypesEnabled=prefs.getBoolean("contentTypesEnabled", true);
		timelines=fromJson(prefs.getString("timelines", null), timelinesType, TimelineDefinition.getDefaultTimelines(session.getID()));
		localOnlySupported=prefs.getBoolean("localOnlySupported", false);
		glitchInstance=prefs.getBoolean("glitchInstance", false);
		publishButtonText=prefs.getString("publishButtonText", null);
		timelineReplyVisibility=prefs.getString("timelineReplyVisibility", null);
		keepOnlyLatestNotification=prefs.getBoolean("keepOnlyLatestNotification", false);
		emojiReactionsEnabled=prefs.getBoolean("emojiReactionsEnabled", session.getInstance().isPresent() && session.getInstance().get().isAkkoma());
		showEmojiReactions=ShowEmojiReactions.valueOf(prefs.getString("showEmojiReactions", ShowEmojiReactions.HIDE_EMPTY.name()));
		color=prefs.contains("color") ? ColorPreference.valueOf(prefs.getString("color", null)) : null;
		recentCustomEmoji=fromJson(prefs.getString("recentCustomEmoji", null), recentCustomEmojiType, new ArrayList<>());
		preReplySheet=prefs.getBoolean("preReplySheet", false);
		notificationFilters=fromJson(prefs.getString("notificationFilters", gson.toJson(PushSubscription.Alerts.ofAll())), notificationFiltersType, PushSubscription.Alerts.ofAll());

		// TOOTSIE: AI Personalization
		aiApiUrl=prefs.getString("aiApiUrl", "https://openrouter.ai/api/v1");
		aiApiKey=prefs.getString("aiApiKey", null);
		aiModel=prefs.getString("aiModel", "openai/gpt-4o-mini");
		aiPostCount=prefs.getInt("aiPostCount", 10);
		aiTopics=fromJson(prefs.getString("aiTopics", null), aiTopicsType, new ArrayList<>());
		aiTopicsLastUpdated=prefs.getLong("aiTopicsLastUpdated", 0);

	}

	public long getNotificationsPauseEndTime(){
		return prefs.getLong("notificationsPauseTime", 0L);
	}

	public void setNotificationsPauseEndTime(long time){
		prefs.edit().putLong("notificationsPauseTime", time).apply();
	}

	public void save(){
		prefs.edit()
				.putBoolean("serverSideFilters", serverSideFiltersSupported)

				// MOSHIDON:
				.putBoolean("showReplies", showReplies)
				.putBoolean("showBoosts", showBoosts)
				.putString("recentLanguages", gson.toJson(recentLanguages))
				.putBoolean("bottomEncoding", bottomEncoding)
				.putString("defaultContentType", defaultContentType==null ? null : defaultContentType.name())
				.putBoolean("contentTypesEnabled", contentTypesEnabled)
				.putString("timelines", gson.toJson(timelines))
				.putBoolean("localOnlySupported", localOnlySupported)
				.putBoolean("glitchInstance", glitchInstance)
				.putString("publishButtonText", publishButtonText)
				.putString("timelineReplyVisibility", timelineReplyVisibility)
				.putBoolean("keepOnlyLatestNotification", keepOnlyLatestNotification)
				.putBoolean("emojiReactionsEnabled", emojiReactionsEnabled)
				.putString("showEmojiReactions", showEmojiReactions.name())
				.putString("color", color!=null ? color.name() : null)
				.putString("recentCustomEmoji", gson.toJson(recentCustomEmoji))
				.putString("notificationFilters", gson.toJson(notificationFilters))

				// TOOTSIE: AI Personalization
				.putString("aiApiUrl", aiApiUrl)
				.putString("aiApiKey", aiApiKey)
				.putString("aiModel", aiModel)
				.putInt("aiPostCount", aiPostCount)
				.putString("aiTopics", gson.toJson(aiTopics))
				.putLong("aiTopicsLastUpdated", aiTopicsLastUpdated)

				.apply();
	}

	// MOSHIDON:
	public ColorPreference getCurrentColor(){
		return color!=null ? color : GlobalUserPreferences.color!=null ? GlobalUserPreferences.color : ColorPreference.PURPLE;
	}

	// MOSHIDON:
	public enum ColorPreference{
		MATERIAL3,
		PURPLE,
		PINK,
		GREEN,
		BLUE,
		BROWN,
		RED,
		YELLOW,
		NORD,
		WHITE;

		public @StringRes int getName() {
			return switch(this){
				case MATERIAL3 -> R.string.sk_color_palette_material3;
				case PINK -> R.string.sk_color_palette_pink;
				case PURPLE -> R.string.sk_color_palette_purple;
				case GREEN -> R.string.sk_color_palette_green;
				case BLUE -> R.string.sk_color_palette_blue;
				case BROWN -> R.string.sk_color_palette_brown;
				case RED -> R.string.sk_color_palette_red;
				case YELLOW -> R.string.sk_color_palette_yellow;
				case NORD -> R.string.mo_color_palette_nord;
				case WHITE -> R.string.mo_color_palette_black_and_white;
			};
		}
	}

	// MOSHIDON:
	public enum ShowEmojiReactions{
		HIDE_EMPTY,
		ONLY_OPENED,
		ALWAYS
	}
}
