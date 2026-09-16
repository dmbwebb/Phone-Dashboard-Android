package com.audacious_software.phone_dashboard;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.audacious_software.phone_dashboard.survey.AppLocale;
import com.audacious_software.phone_dashboard.survey.Survey;
import com.audacious_software.phone_dashboard.survey.SurveyEngine;
import com.audacious_software.phone_dashboard.survey.SurveyEngines;
import com.audacious_software.phone_dashboard.survey.SurveyQuestion;
import com.audacious_software.phone_dashboard.survey.SurveyScheduler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * The scheduled survey screen. Presents the {@link Survey}'s questions one at a
 * time: single-choice questions advance on tap; multi-choice questions show
 * checkboxes plus a Continue button. Choosing an "other" option opens a write-in
 * box that has to be filled before moving on. Back (the button or the system
 * gesture) returns to the previous question with its answer restored.
 *
 * Answers are buffered rather than written on every tap, so going back and
 * changing one does not leave a stale data point behind. The buffer is flushed
 * to {@link DailySurveyGenerator} — one point per answered question — when the
 * survey finishes, when it is skipped, and in {@link #onStop()} so a survey
 * abandoned mid-way still records what was answered.
 */
public class SurveyActivity extends AppCompatActivity {
    // The question bubble's emerald, so a restored choice reads as picked against
    // the default grey of the others.
    private static final int CHOICE_SELECTED_TINT = 0xFFA7F3D0;

    /** A buffered answer to one question, plus whether it has reached the generator. */
    private static class Answer {
        final int[] values;
        final String[] labels;
        final String otherText;
        final long respondedAt;
        boolean saved = false;

        Answer(int[] values, String[] labels, String otherText, long respondedAt) {
            this.values = values;
            this.labels = labels;
            this.otherText = otherText;
            this.respondedAt = respondedAt;
        }

        boolean matches(int[] values, String otherText) {
            return Arrays.equals(this.values, values) && this.otherText.equals(otherText);
        }
    }

    private SurveyEngine mEngine;
    private Survey mSurvey;
    private long mPromptShownAt;
    private int mIndex = 0;
    private boolean mFinished = false;

    private final SparseArray<Answer> mAnswers = new SparseArray<>();

    private final List<CheckBox> mCheckBoxes = new ArrayList<>();
    private final List<Button> mChoiceButtons = new ArrayList<>();
    private ColorStateList mChoiceTint = null;
    private int mSelectedIndex = -1;
    private boolean mUpdatingChecks = false;

    @Override
    protected void attachBaseContext(Context newBase) {
        // Honor the in-app language choice even though this is a plain Activity.
        super.attachBaseContext(AppLocale.wrap(newBase));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.setContentView(R.layout.activity_survey);

        this.mEngine = SurveyEngines.forContext(this);
        this.mSurvey = this.mEngine.currentSurvey(this);
        this.mPromptShownAt = this.getIntent().getLongExtra(SurveyScheduler.EXTRA_PROMPT_SHOWN_AT, System.currentTimeMillis());

        SurveyScheduler.cancelNotification(this);

        this.getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (SurveyActivity.this.mIndex > 0) {
                    SurveyActivity.this.goBack();
                } else {
                    SurveyActivity.this.skipSurvey();
                }
            }
        });

        Button skip = this.findViewById(R.id.survey_skip);
        skip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SurveyActivity.this.skipSurvey();
            }
        });

        Button back = this.findViewById(R.id.survey_back);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SurveyActivity.this.goBack();
            }
        });

        this.renderQuestion(0);
    }

    @Override
    protected void onStop() {
        super.onStop();

        // The activity may never come back (participant went home, system reclaimed
        // it) — keep whatever has been answered so far.
        this.flushAnswers();
    }

    private void renderQuestion(int index) {
        final SurveyQuestion question = this.mSurvey.get(index);
        final Answer previous = this.mAnswers.get(index);

        TextView progress = this.findViewById(R.id.survey_progress);
        progress.setText(this.getString(R.string.survey_progress_format, index + 1, this.mSurvey.size()));

        TextView questionText = this.findViewById(R.id.survey_question);
        questionText.setText(question.questionText);

        TextView hint = this.findViewById(R.id.survey_hint);
        hint.setText(question.type == SurveyQuestion.Type.MULTI
                ? this.getString(R.string.survey_hint_multi)
                : this.getString(R.string.survey_hint_single));

        LinearLayout container = this.findViewById(R.id.survey_choices);
        container.removeAllViews();
        this.mCheckBoxes.clear();
        this.mChoiceButtons.clear();
        this.mSelectedIndex = -1;

        EditText otherText = this.findViewById(R.id.survey_other_text);
        otherText.setText(previous != null ? previous.otherText : "");
        otherText.setVisibility(View.GONE);

        Button continueButton = this.findViewById(R.id.survey_continue);
        continueButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SurveyActivity.this.continueTapped(question);
            }
        });

        if (question.type == SurveyQuestion.Type.MULTI) {
            this.buildMultiChoices(container, question, previous);
            continueButton.setVisibility(View.VISIBLE);
        } else {
            this.buildSingleChoices(container, question);

            if (previous != null && previous.values.length > 0) {
                // Restore the earlier choice so Back-then-forward keeps the answer.
                this.selectSingleChoice(question, previous.values[0] - 1);
            }

            // Nothing to continue past until a choice is on screen; a plain choice
            // tap advances on its own.
            continueButton.setVisibility(this.mSelectedIndex >= 0 ? View.VISIBLE : View.GONE);
        }

        this.updateNavigation();
    }

    /** Show the Back button from the second question on, and hide an empty nav row. */
    private void updateNavigation() {
        Button back = this.findViewById(R.id.survey_back);
        back.setVisibility(this.mIndex > 0 ? View.VISIBLE : View.GONE);

        Button continueButton = this.findViewById(R.id.survey_continue);
        View nav = this.findViewById(R.id.survey_nav);
        nav.setVisibility(back.getVisibility() == View.GONE && continueButton.getVisibility() == View.GONE
                ? View.GONE
                : View.VISIBLE);
    }

    private LinearLayout.LayoutParams choiceParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = (int) (8 * this.getResources().getDisplayMetrics().density);
        return params;
    }

    private void buildSingleChoices(LinearLayout container, final SurveyQuestion question) {
        for (int i = 0; i < question.choiceLabels.length; i++) {
            final int position = i;

            Button button = new Button(this);
            button.setLayoutParams(this.choiceParams());
            button.setAllCaps(false);
            button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            button.setText(question.labelForIndex(i));
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    SurveyActivity.this.singleChoiceTapped(question, position);
                }
            });
            container.addView(button);
            this.mChoiceButtons.add(button);

            if (this.mChoiceTint == null) {
                // The theme's own button tint, restored when a choice is deselected.
                this.mChoiceTint = button.getBackgroundTintList();
            }
        }
    }

    /**
     * A plain choice is the whole answer, so it advances immediately; an "other"
     * choice waits for the write-in box and the Continue button.
     */
    private void singleChoiceTapped(SurveyQuestion question, int position) {
        this.selectSingleChoice(question, position);

        if (question.isOther(position)) {
            this.findViewById(R.id.survey_other_text).requestFocus();
            this.findViewById(R.id.survey_continue).setVisibility(View.VISIBLE);
            this.updateNavigation();

            return;
        }

        this.continueTapped(question);
    }

    private void selectSingleChoice(SurveyQuestion question, int position) {
        this.mSelectedIndex = position;

        for (int i = 0; i < this.mChoiceButtons.size(); i++) {
            Button button = this.mChoiceButtons.get(i);
            boolean selected = (i == position);
            button.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
            button.setBackgroundTintList(selected ? ColorStateList.valueOf(CHOICE_SELECTED_TINT) : this.mChoiceTint);
        }

        this.findViewById(R.id.survey_other_text)
                .setVisibility(question.isOther(position) ? View.VISIBLE : View.GONE);
        this.findViewById(R.id.survey_continue).setVisibility(View.VISIBLE);
    }

    private void buildMultiChoices(LinearLayout container, final SurveyQuestion question, Answer previous) {
        for (int i = 0; i < question.choiceLabels.length; i++) {
            final int position = i;
            CheckBox checkBox = new CheckBox(this);
            checkBox.setLayoutParams(this.choiceParams());
            checkBox.setText(question.labelForIndex(i));
            checkBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    SurveyActivity.this.onMultiChecked(question, position, isChecked);
                }
            });
            container.addView(checkBox);
            this.mCheckBoxes.add(checkBox);
        }

        if (previous == null) {
            return;
        }

        // Restore the earlier selection without re-running the exclusivity rules.
        this.mUpdatingChecks = true;
        for (int value : previous.values) {
            int position = value - 1;
            if (position >= 0 && position < this.mCheckBoxes.size()) {
                this.mCheckBoxes.get(position).setChecked(true);
            }
        }
        this.mUpdatingChecks = false;

        this.updateOtherVisibility(question);
    }

    /** Enforce the exclusive "none of these" option: it clears the others and vice versa. */
    private void onMultiChecked(SurveyQuestion question, int position, boolean isChecked) {
        if (this.mUpdatingChecks) {
            return;
        }

        if (isChecked && question.exclusiveIndex >= 0) {
            this.mUpdatingChecks = true;
            if (position == question.exclusiveIndex) {
                for (int i = 0; i < this.mCheckBoxes.size(); i++) {
                    if (i != position) {
                        this.mCheckBoxes.get(i).setChecked(false);
                    }
                }
            } else {
                this.mCheckBoxes.get(question.exclusiveIndex).setChecked(false);
            }
            this.mUpdatingChecks = false;
        }

        this.updateOtherVisibility(question);
    }

    /** Show the write-in box while the question's "other" checkbox is ticked. */
    private void updateOtherVisibility(SurveyQuestion question) {
        if (question.otherIndex < 0 || question.otherIndex >= this.mCheckBoxes.size()) {
            return;
        }

        boolean checked = this.mCheckBoxes.get(question.otherIndex).isChecked();
        this.findViewById(R.id.survey_other_text).setVisibility(checked ? View.VISIBLE : View.GONE);
    }

    /** Record the current selection and move on, refusing an empty write-in. */
    private void continueTapped(SurveyQuestion question) {
        List<Integer> selected = new ArrayList<>();

        if (question.type == SurveyQuestion.Type.MULTI) {
            for (int i = 0; i < this.mCheckBoxes.size(); i++) {
                if (this.mCheckBoxes.get(i).isChecked()) {
                    selected.add(i);
                }
            }
        } else if (this.mSelectedIndex >= 0) {
            selected.add(this.mSelectedIndex);
        }

        String otherText = "";
        if (question.otherIndex >= 0 && selected.contains(question.otherIndex)) {
            EditText field = this.findViewById(R.id.survey_other_text);
            otherText = field.getText().toString().trim();

            if (TextUtils.isEmpty(otherText)) {
                Toast.makeText(this, R.string.survey_other_required, Toast.LENGTH_SHORT).show();
                field.requestFocus();

                return;
            }
        }

        this.putAnswer(this.mIndex, question, selected, otherText);
        this.advance();
    }

    private void putAnswer(int index, SurveyQuestion question, List<Integer> selected, String otherText) {
        int[] values = new int[selected.size()];
        String[] labels = new String[selected.size()];
        for (int i = 0; i < selected.size(); i++) {
            values[i] = question.valueForIndex(selected.get(i));
            labels[i] = question.labelForIndex(selected.get(i));
        }

        Answer existing = this.mAnswers.get(index);
        if (existing != null && existing.matches(values, otherText)) {
            // Unchanged — leave it be so an already-flushed answer isn't sent twice.
            return;
        }

        this.mAnswers.put(index, new Answer(values, labels, otherText, System.currentTimeMillis()));
    }

    private void advance() {
        this.mIndex++;
        if (this.mIndex >= this.mSurvey.size()) {
            this.finishSurvey();
        } else {
            this.renderQuestion(this.mIndex);
        }
    }

    private void goBack() {
        if (this.mIndex == 0) {
            return;
        }

        // Keep an in-progress selection the participant hasn't continued past yet,
        // so stepping back and forward again doesn't clear their work. Skip the
        // write-in check here — that only gates moving forward.
        SurveyQuestion current = this.mSurvey.get(this.mIndex);
        List<Integer> selected = new ArrayList<>();
        if (current.type == SurveyQuestion.Type.MULTI) {
            for (int i = 0; i < this.mCheckBoxes.size(); i++) {
                if (this.mCheckBoxes.get(i).isChecked()) {
                    selected.add(i);
                }
            }
        } else if (this.mSelectedIndex >= 0) {
            selected.add(this.mSelectedIndex);
        }

        if (!selected.isEmpty()) {
            EditText field = this.findViewById(R.id.survey_other_text);
            String otherText = current.otherIndex >= 0 && selected.contains(current.otherIndex)
                    ? field.getText().toString().trim()
                    : "";
            this.putAnswer(this.mIndex, current, selected, otherText);
        }

        this.mIndex--;
        this.renderQuestion(this.mIndex);
    }

    /** Emit a data point for every buffered answer that hasn't been sent yet. */
    private void flushAnswers() {
        for (int i = 0; i < this.mSurvey.size(); i++) {
            Answer answer = this.mAnswers.get(i);

            if (answer == null || answer.saved) {
                continue;
            }

            DailySurveyGenerator.getInstance(this).saveResponse(
                    this.mSurvey.surveyId,
                    this.mSurvey.get(i),
                    answer.values,
                    answer.labels,
                    answer.otherText,
                    this.mPromptShownAt,
                    answer.respondedAt,
                    this.mEngine.engineName()
            );
            answer.saved = true;
        }
    }

    private void finishSurvey() {
        this.mFinished = true;
        this.flushAnswers();
        Toast.makeText(this, R.string.survey_thanks, Toast.LENGTH_SHORT).show();
        this.finish();
    }

    private void skipSurvey() {
        if (!this.mFinished) {
            this.mFinished = true;
            this.flushAnswers();
            DailySurveyGenerator.getInstance(this).saveDismissed(
                    this.mSurvey.surveyId, this.mPromptShownAt, this.mEngine.engineName());
        }
        this.finish();
    }
}
