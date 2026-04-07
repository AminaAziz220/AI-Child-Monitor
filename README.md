# AI Child Monitor

An AI-powered Android application that monitors and guides children's digital consumption in real time. The system pairs a parent's device with a child's device, tracks app usage, and uses multi-layered AI analysis to detect unsafe YouTube content — alerting parents instantly.

## Features

### YouTube Content Safety (Two-Layer AI Analysis)

The app monitors YouTube activity on the child's device using an Android Accessibility Service and runs two independent layers of AI analysis on every video:

**Layer 1 — Title Classification**
Uses a DeBERTa zero-shot classification model (via HuggingFace Inference API) to classify the video title against five content categories: educational, entertainment, violent, sexual, and harmful/addictive. If any unsafe category scores above 0.6, the title is flagged.

**Layer 2 — Subtitle Sentiment and Toxicity Analysis**
Fetches the video's subtitle/caption track (manual, auto-generated, or translated) and runs two NLP models on the actual spoken content:
- DistilBERT for sentiment analysis (positive vs negative tone)
- Toxic-BERT for toxicity detection (profanity, hate speech, threats)

Long subtitle texts are chunked to respect model token limits, and scores are averaged across all chunks.

**Combined Verdict**
If either layer flags the video as unsafe, the final verdict is `unsafe`. If subtitles are unavailable (common for music videos and shorts), the system gracefully falls back to the title-only verdict.

### Screen Time Tracking

- Tracks per-app usage using Android's UsageStats API
- Uploads daily usage data to Firebase Firestore every 15 minutes
- Breaks down usage by app with top-15 ranking
- Tracks night usage (12 AM – 6 AM) separately
- Counts phone unlock events (phone checks per day)
- Maintains 7 days of rolling history with automatic cleanup

### Risk Assessment

- ML-based risk prediction using a Random Forest model
- Considers: total screen time, social media hours, gaming hours, night usage ratio, phone checks, and engagement intensity
- Produces a Low / Medium / High risk level per day
- Also computes a weekly aggregated risk score

### Parent Dashboard

- Real-time child monitoring with Firestore snapshot listeners
- Daily app usage bar chart (MPAndroidChart)
- Weekly screen time trend line chart
- Per-day navigation (today, yesterday, past 7 days)
- Configurable daily screen time limits
- Emotional avatar that reflects risk level (happy/neutral/sad)
- Daily journal entries from the child
- Risk level display (daily and weekly)

### Parent Notifications

- Push notification when unsafe YouTube content is detected
- Push notification when daily screen time limit is exceeded
- Notifications are sent only once per event to avoid spam

### Device Pairing

- Parent generates a 4-digit pairing key
- Child enters the key to pair their device
- Pairing persists across app restarts via SharedPreferences
- Paired devices skip the role selection screen on launch

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                    Child's Phone                     │
│                                                      │
│  YouTubeAccessibilityService                         │
│       ↓ detects video title + ID                     │
│  YouTubeClassifierTrigger                            │
│       ├── Layer 1: VideoClassifier (HuggingFace API) │
│       └── Layer 2: SubtitleAnalyzer (Flask backend)  │
│              ↓ combined verdict                      │
│  Firebase Firestore  ←── UsageStatsWorker            │
└─────────────────────────────────────────────────────┘
           ↕ real-time sync
┌─────────────────────────────────────────────────────┐
│                   Parent's Phone                     │
│                                                      │
│  MainActivity (child list + notifications)           │
│  ChildDashboardActivity (charts, risk, limits)       │
└─────────────────────────────────────────────────────┘
           ↕ HTTP
┌─────────────────────────────────────────────────────┐
│               Flask Backend (Python)                 │
│                                                      │
│  /predict          → Risk level (Random Forest)      │
│  /analyze-subtitles → Sentiment + Toxicity analysis  │
│  /health           → Health check                    │
└─────────────────────────────────────────────────────┘
```

## Tech Stack

| Component | Technology |
|-----------|------------|
| Android App | Kotlin, Android SDK 36 (min SDK 24) |
| UI | XML Layouts, ViewBinding, MPAndroidChart |
| Backend | Firebase Auth, Firestore, Python Flask |
| AI (Layer 1) | DeBERTa v3 zero-shot (HuggingFace Inference API) |
| AI (Layer 2) | DistilBERT sentiment + Toxic-BERT (local via Transformers) |
| Risk Model | scikit-learn Random Forest |
| Subtitle Fetching | youtube-transcript-api |
| Networking | OkHttp 4, WorkManager |
| Background Processing | Android Accessibility Service, WorkManager |

## Prerequisites

- Android Studio (Arctic Fox or later)
- Python 3.10+
- A Firebase project (free Spark plan is sufficient)
- A HuggingFace account (free) for API token
- A Google Cloud project with YouTube Data API v3 enabled (free tier)

## Setup

### 1. Clone the repository

```bash
git clone https://github.com/AminaAziz220/AI-Child-Monitor.git
cd AI-Child-Monitor
```

### 2. Firebase setup

1. Go to [Firebase Console](https://console.firebase.google.com) and create a new project
2. Add an Android app with package name: `com.sigmacoders.aichildmonitor`
3. Download `google-services.json` and place it in the `app/` directory
4. Enable **Authentication** → Sign-in method:
   - Email/Password → Enable
   - Google → Enable (note the Web client ID for step 5)
5. Enable **Firestore Database** → Create database → Start in test mode
6. Add API keys to Firestore:
   - Create collection: `appConfig`
   - Add document `huggingface` with field: `apiKey` (string) → your [HuggingFace token](https://huggingface.co/settings/tokens)
   - Add document `youtube` with field: `apiKey` (string) → your [YouTube Data API key](https://console.cloud.google.com/apis/credentials)

### 3. Update the Google Sign-In client ID

Open `app/src/main/java/.../LoginActivity.kt` and replace the `requestIdToken(...)` value with your own Web client ID from Firebase Console → Authentication → Sign-in method → Google → Web SDK configuration.

### 4. Python backend setup

```bash
cd app/risk_backend
pip install -r requirements.txt
```

### 5. Configure backend URL for physical devices

If testing on a physical device (not an emulator), update the backend IP in two files:

**`SubtitleAnalyzer.kt`** (line 28):
```kotlin
private const val BACKEND_URL = "http://YOUR_PC_IP:5000/analyze-subtitles"
```

**`ChildDashboardActivity.kt`** (in `sendRiskRequest`):
```kotlin
val request = Request.Builder().url("http://YOUR_PC_IP:5000/predict").post(body).build()
```

Find your PC's IP with `ifconfig` (Linux/Mac) or `ipconfig` (Windows). Both devices must be on the same WiFi network. For Android emulator, use `10.0.2.2` (default).

### 6. Build the APK

**Option A — Android Studio:**

1. Open the project in Android Studio
2. Wait for Gradle sync to complete
3. Build → Build Bundle(s) / APK(s) → Build APK(s)
4. APK output: `app/build/outputs/apk/debug/app-debug.apk`

**Option B — Command line:**

```bash
./gradlew assembleDebug
```

### 7. Install on device

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Testing

### Step 1: Start the backend

```bash
cd app/risk_backend
python app.py
```

You should see:
```
 * Running on http://0.0.0.0:5000
```

The first request will download ML models (~400 MB, cached after first download).

### Step 2: Test backend endpoints

```bash
# Health check
curl http://localhost:5000/health

# Subtitle analysis
curl -X POST http://localhost:5000/analyze-subtitles \
  -H "Content-Type: application/json" \
  -d '{"video_id": "rfscVS0vtbw"}'

# Or run the test script
python test.py
```

### Step 3: Set up the parent device

1. Open the app → select **Parent**
2. Sign up with email/password or Google
3. Tap **Add Child** → note the 4-digit pairing key

### Step 4: Set up the child device

1. Open the app → select **Child**
2. Enter the 4-digit pairing key → fill in child details → Confirm
3. Grant **Usage Access** permission when prompted
4. Enable the **Accessibility Service**:
   - Settings → Accessibility → AI Child Monitor → Enable

### Step 5: Trigger YouTube analysis

1. Open YouTube on the child's device
2. Play any video with subtitles (tutorials, TED talks, news clips work best)
3. Monitor real-time logs:

```bash
adb logcat | grep -E "YT_MONITOR|YT_CLASSIFIER|SUBTITLE_ANALYZER"
```

Expected output:
```
D/YT_MONITOR: VIDEO TITLE DETECTED: How Computers Work
D/YT_CLASSIFIER: Layer 1 — Classifying title: How Computers Work
I/YT_CLASSIFIER: Layer 1 RESULT → educational content suitable for children | safe
D/YT_CLASSIFIER: Layer 2 — Analyzing subtitles for video: abc123xyz
I/SUBTITLE_ANALYZER: Video: abc123xyz | Safety: safe | Sentiment: +0.85 / -0.15 | Toxicity: 0.03
I/YT_CLASSIFIER: COMBINED VERDICT → title=safe + subtitle=safe = safe
```

### Step 6: Check the parent dashboard

Open the app on the parent device → tap the child's name → view:
- Daily and weekly screen time charts
- Risk level assessment
- Last video verdict and unsafe content alerts

## Firestore Data Structure

```
users/{parentId}/
  ├── email: "parent@example.com"
  ├── createdAt: 1711612800000
  └── children/{childId}/
      ├── name: "Alex"
      ├── age: 10
      ├── gender: "Boy"
      ├── isPaired: true
      ├── riskLevel: "Low"
      ├── weeklyRiskLevel: "Low"
      ├── screenTimeLimit: 120
      ├── journalText: "..."
      ├── usageByDate/
      │   ├── 2026-03-29/
      │   │   ├── totalMinutes: 95
      │   │   ├── phoneChecks: 12
      │   │   ├── nightUsageMinutes: 15
      │   │   ├── nightUsageRatio: 0.16
      │   │   └── topApps: [...]
      │   └── lastUpdated: <timestamp>
      ├── lastVideoVerdict/
      │   ├── title: "How Computers Work"
      │   ├── titleSafety: "safe"
      │   ├── subtitleSafety: "safe"
      │   ├── combinedSafety: "safe"
      │   └── timestamp: 1711612800000
      └── lastSubtitleAnalysis/
          ├── videoId: "abc123xyz"
          ├── sentimentPositive: 0.85
          ├── sentimentNegative: 0.15
          ├── toxicityScore: 0.03
          └── chunksAnalyzed: 4

appConfig/
  ├── huggingface/
  │   └── apiKey: "hf_..."
  └── youtube/
      └── apiKey: "AIza..."

pairingKeys/{4-digit-key}/
  └── parentId: "uid..."
```

## Project Structure

```
AI-Child-Monitor/
├── app/
│   ├── build.gradle.kts
│   ├── google-services.json              # Your Firebase config (not in repo)
│   ├── risk_backend/
│   │   ├── app.py                        # Flask backend (risk + subtitle analysis)
│   │   ├── requirements.txt              # Python dependencies
│   │   ├── risk_model.pkl                # Trained Random Forest model
│   │   └── test.py                       # Backend test script
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/sigmacoders/aichildmonitor/
│       │   ├── RoleSelectionActivity.kt  # Entry point: parent or child
│       │   ├── LoginActivity.kt          # Parent login (email + Google)
│       │   ├── SignUpActivity.kt         # Parent registration
│       │   ├── MainActivity.kt           # Parent home (child list + alerts)
│       │   ├── ChildDashboardActivity.kt # Parent dashboard (charts + risk)
│       │   ├── ChildLoginActivity.kt     # Child pairing via 4-digit key
│       │   ├── ChildHomeActivity.kt      # Child home (permission setup)
│       │   ├── YouTubeAccessibilityService.kt  # Detects YouTube titles
│       │   ├── YouTubeClassifierTrigger.kt     # Orchestrates both AI layers
│       │   ├── ApiKeyProvider.kt         # Fetches API keys from Firestore
│       │   ├── ai/
│       │   │   ├── VideoClassifier.kt    # Layer 1: title classification (HF)
│       │   │   ├── SubtitleAnalyzer.kt   # Layer 2: subtitle analysis (Flask)
│       │   │   ├── YouTubeSearchFetcher.kt    # Looks up video ID by title
│       │   │   └── YouTubeCommentsFetcher.kt  # Fetches top comments
│       │   ├── adapter/
│       │   │   └── ChildrenAdapter.kt    # RecyclerView adapter
│       │   ├── model/
│       │   │   └── Child.kt              # Child data model
│       │   ├── utils/
│       │   │   └── NotificationHelper.kt # Push notification builder
│       │   └── worker/
│       │       └── UsageStatsWorker.kt   # Background usage stats uploader
│       └── res/
│           ├── layout/                   # XML layouts for all activities
│           ├── drawable/                 # Avatars and icons
│           └── xml/
│               ├── youtube_service_config.xml  # Accessibility service config
│               └── network_security_config.xml # Allows HTTP for backend
├── build.gradle.kts
├── settings.gradle.kts
└── gradle/
```

## API Keys Summary

| Service | Free Tier | How to Get |
|---------|-----------|------------|
| HuggingFace | Unlimited inference | [huggingface.co/settings/tokens](https://huggingface.co/settings/tokens) |
| YouTube Data API | 10,000 units/day (~100 lookups) | [Google Cloud Console](https://console.cloud.google.com) → APIs → YouTube Data API v3 |
| Firebase | 50K reads/day, 20K writes/day | [Firebase Console](https://console.firebase.google.com) |

All services are free for development and demo purposes. No credit card required.

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Child login says "Authentication failed" | This bug has been fixed — update to the latest `ChildLoginActivity.kt` |
| `Connection refused` in logcat | Flask backend not running, or wrong IP in `SubtitleAnalyzer.kt` |
| `No subtitles available` | Video has no captions (music videos, shorts). Title-only verdict is used. |
| First subtitle request takes 30+ seconds | ML models downloading for first time. Subsequent requests: 2-5 seconds. |
| UsageStatsWorker returns FAILURE | Grant Usage Access permission: Settings → Apps → Special access → Usage access |
| Google Sign-In fails | Replace the OAuth client ID in `LoginActivity.kt` with your own |
| Accessibility service not detecting titles | Ensure it's enabled in Settings → Accessibility → AI Child Monitor |
| `sklearn version warning` on backend | Harmless. Fix with `pip install scikit-learn==1.6.1` |

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.
