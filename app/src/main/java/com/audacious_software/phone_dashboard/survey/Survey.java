package com.audacious_software.phone_dashboard.survey;

import java.util.List;

/** An ordered set of questions shown one at a time, identified by a versioned id. */
public class Survey {
    public final String surveyId;
    public final List<SurveyQuestion> questions;

    public Survey(String surveyId, List<SurveyQuestion> questions) {
        this.surveyId = surveyId;
        this.questions = questions;
    }

    public int size() {
        return this.questions.size();
    }

    public SurveyQuestion get(int index) {
        return this.questions.get(index);
    }
}
