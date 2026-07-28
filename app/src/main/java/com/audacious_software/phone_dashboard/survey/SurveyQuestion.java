package com.audacious_software.phone_dashboard.survey;

/**
 * One question in the nightly survey. Single-choice questions advance on tap;
 * multi-choice ("select all that apply") show checkboxes plus a Continue button.
 *
 * Plain data so questions can later be sourced from the server / an LLM engine
 * without touching the scheduling, notification, or storage plumbing.
 */
public class SurveyQuestion {
    public enum Type {
        SINGLE,
        MULTI
    }

    public final String questionKey;
    public final String questionText;
    public final Type type;
    public final String[] choiceLabels;
    // 0-based index of an exclusive "none of these" option (clears the others when
    // picked), or -1 if the question has none. Only meaningful for MULTI.
    public final int exclusiveIndex;
    // 0-based index of an "other" option that opens a free-text box the participant
    // must fill in, or -1 if the question has none.
    public final int otherIndex;

    public SurveyQuestion(String questionKey, String questionText, Type type,
                          String[] choiceLabels, int exclusiveIndex, int otherIndex) {
        this.questionKey = questionKey;
        this.questionText = questionText;
        this.type = type;
        this.choiceLabels = choiceLabels;
        this.exclusiveIndex = exclusiveIndex;
        this.otherIndex = otherIndex;
    }

    /** True when the choice at {@code index} is the write-in "other" option. */
    public boolean isOther(int index) {
        return this.otherIndex >= 0 && index == this.otherIndex;
    }

    /** 1-based stored value for the choice at {@code index}. */
    public int valueForIndex(int index) {
        return index + 1;
    }

    public String labelForIndex(int index) {
        if (index < 0 || index >= this.choiceLabels.length) {
            return "";
        }
        return this.choiceLabels[index];
    }
}