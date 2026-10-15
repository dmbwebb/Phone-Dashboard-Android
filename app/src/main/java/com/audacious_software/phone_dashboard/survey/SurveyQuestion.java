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
    // Stored value for each on-screen position, so a question can show its choices
    // in a varying order while the data keeps one coding. Null means position + 1.
    private final int[] choiceValues;
    // Which on-screen order this prompt used (e.g. "ascending"), sent with the
    // answer; null for questions whose order never varies.
    public final String choiceOrder;

    public SurveyQuestion(String questionKey, String questionText, Type type,
                          String[] choiceLabels, int exclusiveIndex, int otherIndex) {
        this(questionKey, questionText, type, choiceLabels, exclusiveIndex, otherIndex, null, null);
    }

    public SurveyQuestion(String questionKey, String questionText, Type type,
                          String[] choiceLabels, int exclusiveIndex, int otherIndex,
                          int[] choiceValues, String choiceOrder) {
        if (choiceValues != null && choiceValues.length != choiceLabels.length) {
            throw new IllegalArgumentException("choiceValues must match choiceLabels");
        }
        this.questionKey = questionKey;
        this.questionText = questionText;
        this.type = type;
        this.choiceLabels = choiceLabels;
        this.exclusiveIndex = exclusiveIndex;
        this.otherIndex = otherIndex;
        this.choiceValues = choiceValues;
        this.choiceOrder = choiceOrder;
    }

    /** True when the choice at {@code index} is the write-in "other" option. */
    public boolean isOther(int index) {
        return this.otherIndex >= 0 && index == this.otherIndex;
    }

    /** 1-based stored value for the choice at {@code index}. */
    public int valueForIndex(int index) {
        return this.choiceValues != null ? this.choiceValues[index] : index + 1;
    }

    /** On-screen position of the choice stored as {@code value}, or -1 if none. */
    public int indexForValue(int value) {
        for (int i = 0; i < this.choiceLabels.length; i++) {
            if (this.valueForIndex(i) == value) {
                return i;
            }
        }
        return -1;
    }

    public String labelForIndex(int index) {
        if (index < 0 || index >= this.choiceLabels.length) {
            return "";
        }
        return this.choiceLabels[index];
    }
}