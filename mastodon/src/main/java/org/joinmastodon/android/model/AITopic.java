package org.joinmastodon.android.model;

/**
 * Represents a topic inferred by the AI from the user's favorites and boosts,
 * or manually added by the user.
 */
public class AITopic {
	public String label;
	public boolean enabled = true;
	public boolean userAdded = false;

	public AITopic() {}

	public AITopic(String label) {
		this.label = label;
	}

	public AITopic(String label, boolean userAdded) {
		this.label = label;
		this.userAdded = userAdded;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		AITopic aiTopic = (AITopic) o;
		return label != null ? label.equalsIgnoreCase(aiTopic.label) : aiTopic.label == null;
	}

	@Override
	public int hashCode() {
		return label != null ? label.toLowerCase().hashCode() : 0;
	}
}
