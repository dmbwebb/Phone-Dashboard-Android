# Version 112: one-tap mood faces in the child check-in survey

## What changed

The check-in survey's second question is now a one-tap mood scale instead of the multi-select emotion list. In pilot data up to 9 October 2026, 71% of 76 answers chose "Tranquilo/a", which in Colombian Spanish often just means "fine". The research team (9 October call) asked for simpler options and emojis.

- Survey id `daily_ema_v1` becomes `daily_ema_v2`. Questions 1, 3 and 4 (`phone_activity`, `sleep_hours`, `social_time_yesterday`) are unchanged.
- `current_affect` (MULTI, eight options) is replaced by `mood_valence` (SINGLE, five options): "¿Cómo te sientes en este momento?" with 😢 Muy mal / 🙁 Mal / 😐 Más o menos / 🙂 Bien / 😄 Muy bien.
- `response_values` is always coded 1 = Muy mal to 5 = Muy bien. Each prompt shows the faces best-first or worst-first, a fair coin derived from `prompt_shown_at`, so the order is fixed within one prompt even if the screen is rebuilt. Each `mood_valence` answer carries a new transmitted field `choice_order` (`ascending` = Muy mal first, `descending` = Muy bien first). It is sent to the server only; the local `daily-survey-response` table and its schema version are unchanged.
- `SurveyQuestion` gained an optional value per on-screen position and `indexForValue`, which Back-navigation now uses to restore an answer; questions without a custom order keep position + 1.
- `SurveyEngine.currentSurvey` now takes the prompt time.

Analysis: the baseline pipeline's `code/10d_app_survey.R` reads `mood_valence` alongside the old `current_affect` (v1 answers from phones on v111 and earlier keep arriving until they update).

No database, upload, scheduling or permission code changed.

## Verification

New JVM tests: `ScriptedSurveyEngineTest` (one value coding in both orders, the order stable per prompt and between 47% and 53% best-first over 20,000 daily prompts, v2 question set) and `SurveyActivityPolicyTest.childMoodAnswerIsRestoredByValueWhenFacesAreShownBestFirst` (a child answers "Mal" on a best-first prompt, goes Back, and the same face is shown as selected; the v1 restore rule would have selected the wrong one).

## Google Play release notes

Mejoramos la encuesta corta de la app: ahora puedes contar cómo te sientes con un solo toque.
