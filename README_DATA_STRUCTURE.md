# Phone Dashboard Data Structure

This document describes the data collected by the Phone Dashboard Android app and transmitted to the Django server.

## Overview

The app uses the Passive Data Kit (PDK) framework to collect and transmit data. Data is stored locally in SQLite databases, then periodically bundled and sent to the server as JSON payloads.

## Data Collection Mechanisms

| Approach | Description | Example |
|----------|-------------|---------|
| **Polling** | Checks state at fixed intervals | ForegroundApplication (every 15 sec) |
| **Event-driven** | Records when events occur | UsageStats, AppSnooze |

---

## Data Generators

### 1. Foreground Application (`pdk-foreground-application`)

**Type:** Polling (default: every 15 seconds)

**Purpose:** Tracks which app is currently in the foreground.

**Configuration:**
- Default interval: 15,000 ms (configurable via server)
- Data retention: 60 days

**Data Fields:**

| Field | Type | Description |
|-------|------|-------------|
| `observed` | Long (ms) | Timestamp when observation was made |
| `application` | String | Package name (e.g., `com.instagram.android`) |
| `category` | String | App category (see below) |
| `duration` | Long (ms) | Always equals sample interval (e.g., 15000) |
| `screen_active` | Boolean | Whether the screen was on |
| `is_home` | Boolean | Whether this is the home/launcher app |
| `display_state` | String | Display state (see below) |

**App Categories:**
- `unknown`, `accessibility`, `audio`, `game`, `image`, `maps`, `news`, `productivity`, `social`, `video`

**Display States:**
- `on`, `off`, `doze`, `doze-suspend`, `on-suspend`, `virtual-reality`, `unknown`

**Example JSON:**
```json
{
  "observed": 1704567890123,
  "application": "com.instagram.android",
  "category": "social",
  "duration": 15000,
  "screen_active": true,
  "is_home": false,
  "display_state": "on",
  "passive-data-metadata": {
    "timestamp": 1704567890,
    "generator-id": "pdk-foreground-application",
    "source": "participant-abc123",
    "timezone": "America/Bogota"
  }
}
```

**Notes:**
- If `screen_active` is false, the app was in foreground but screen was off (not real usage)
- Duration is always the sample interval, not actual usage time
- To calculate total usage, sum durations where `screen_active = true`

---

### 2. Usage Stats (`pdk-usage-stats`)

**Type:** Event-driven (polled from Android every 5 minutes)

**Purpose:** Captures exact app transition events from Android's UsageStats API.

**Configuration:**
- Default poll interval: 300,000 ms (5 minutes)
- Disabled by default (must be enabled via server config)

**Data Fields:**

| Field | Type | Description |
|-------|------|-------------|
| `observed` | Long (ms) | Exact timestamp when event occurred |
| `package` | String | Package name of the app |
| `event_type` | String | Type of event (see below) |

**Event Types:**

| Event | Description |
|-------|-------------|
| `activity-resumed` | App came to foreground |
| `activity-paused` | App went to background |
| `activity-stopped` | App fully stopped |
| `screen-interactive` | Screen turned on |
| `screen-non-interactive` | Screen turned off |
| `user-interaction` | User touched/interacted with app |
| `keyguard-shown` | Lock screen appeared |
| `keyguard-hidden` | Lock screen dismissed |
| `device-startup` | Device booted |
| `device-shutdown` | Device shutting down |
| `foreground-service-start` | Foreground service started |
| `foreground-service-stop` | Foreground service stopped |
| `configuration-change` | Device configuration changed |
| `standby-bucket-changed` | App standby bucket changed |
| `shortcut-invocation` | App shortcut was used |

**Example JSON:**
```json
{
  "observed": 1704567890123,
  "package": "com.whatsapp",
  "event_type": "activity-resumed",
  "passive-data-metadata": {
    "timestamp": 1704567890,
    "generator-id": "pdk-usage-stats",
    "source": "participant-abc123"
  }
}
```

**Notes:**
- Unlike ForegroundApplication, this captures the **exact moment** an app opens/closes
- Calculate session duration by pairing `activity-resumed` with subsequent `activity-paused`
- Events are timestamped by Android, not by the polling interval

---

### 3. App Snooze (`app-snooze`)

**Type:** Event-driven

**Purpose:** Records when a user snoozes a usage limit warning.

**Data Fields:**

| Field | Type | Description |
|-------|------|-------------|
| `observed` | Long (ms) | When the snooze occurred |
| `app_package` | String | Package name of the app being snoozed |
| `duration` | Long (ms) | Duration of the snooze extension |
| `original_budget` | Double | Initial snooze budget for the day |
| `remaining_budget` | Double | Budget remaining after this snooze |

**Example JSON:**
```json
{
  "observed": 1704567890123,
  "app_package": "com.instagram.android",
  "duration": 300000,
  "original_budget": 10.0,
  "remaining_budget": 7.5,
  "passive-data-metadata": {
    "generator-id": "app-snooze",
    "source": "participant-abc123"
  }
}
```

---

### 4. Daily Budget (`daily-app-budget`)

**Type:** Event-driven (recorded when budgets are set/updated)

**Purpose:** Tracks the usage budgets configured for each app.

**Data Fields:**

| Field | Type | Description |
|-------|------|-------------|
| `observed` | Long (ms) | When the budget was set |
| `app_package` | String | Package name |
| `budget` | Long (ms) | Daily budget in milliseconds |

---

### 5. Snooze Delay (`snooze-delay`)

**Type:** Event-driven

**Purpose:** Records snooze delay events during the warning flow.

---

## Data Transmission

### Bundle Format

Data is transmitted to the server as JSON bundles via HTTP POST to `/data/add-bundle.json`:

```json
[
  { /* data point 1 */ },
  { /* data point 2 */ },
  { /* data point 3 */ }
]
```

### Metadata

Every data point includes a `passive-data-metadata` object:

```json
{
  "passive-data-metadata": {
    "timestamp": 1704567890,
    "generator-id": "pdk-foreground-application",
    "generator": "pdk-foreground-application: http-transmitter",
    "source": "participant-identifier",
    "timezone": "America/Bogota"
  }
}
```

| Field | Description |
|-------|-------------|
| `timestamp` | UTC timestamp in **seconds** |
| `generator-id` | Generator identifier |
| `generator` | Full generator string with transmitter |
| `source` | De-identified participant ID |
| `timezone` | Device timezone (if available) |

---

## Server Storage (Django)

On the server, data is stored in the `DataPoint` model:

| Field | Source |
|-------|--------|
| `source` | `passive-data-metadata.source` |
| `generator_identifier` | `passive-data-metadata.generator-id` |
| `created` | Derived from `observed` or `timestamp` |
| `recorded` | Server receive time |
| `properties` | Complete JSON payload |
| `secondary_identifier` | Extracted (e.g., app package name) |

---

## Calculating Usage

### From ForegroundApplication (polling data)

```python
# Sum durations where screen was active
total_usage_ms = sum(
    point.properties['duration']
    for point in data_points
    if point.properties.get('screen_active', False)
)
```

### From UsageStats (event data)

```python
# Pair activity-resumed with activity-paused events
sessions = []
resume_time = None

for event in sorted(events, key=lambda e: e['observed']):
    if event['event_type'] == 'activity-resumed':
        resume_time = event['observed']
    elif event['event_type'] == 'activity-paused' and resume_time:
        sessions.append({
            'start': resume_time,
            'end': event['observed'],
            'duration': event['observed'] - resume_time
        })
        resume_time = None
```

---

## Privacy Considerations

- **De-identification:** Participant identifiers are UUIDs, not emails
- **App obscuring:** Apps can be obscured (hashed) if not in the allowed list
- **No content:** Only metadata is collected (e.g., "WhatsApp was used" not message content)
- **Local storage:** Data is stored locally before transmission
- **Retention:** Default 60-day local retention, configurable

---

## Configuration

Generators can be configured remotely via the server's `AppConfiguration` system:

```json
{
  "pdk-foreground-application": {
    "sample-interval": 15000,
    "included-apps": ["com.instagram.android", "com.facebook.katana"],
    "excluded-apps": [],
    "included-categories": ["social", "game"],
    "excluded-categories": []
  },
  "pdk-usage-stats": {
    "enabled": true,
    "sample-interval": 300000
  }
}
```
