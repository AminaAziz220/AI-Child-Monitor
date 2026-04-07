from journal_generator import generate_journal
from flask import Flask, request, jsonify
import joblib
import numpy as np
from youtube_transcript_api import YouTubeTranscriptApi
from transformers import pipeline
import logging
import re

app = Flask(__name__)
logging.basicConfig(level=logging.INFO)

# Load trained model
model = joblib.load("risk_model.pkl")

# ──────────────────────────────────────────────
# Lazy-loaded NLP pipelines for subtitle analysis
# ──────────────────────────────────────────────
_sentiment_pipeline = None
_toxicity_pipeline = None


def get_sentiment_pipeline():
    global _sentiment_pipeline
    if _sentiment_pipeline is None:
        logging.info("Loading sentiment analysis model (first call)...")
        _sentiment_pipeline = pipeline(
            "sentiment-analysis",
            model="distilbert-base-uncased-finetuned-sst-2-english",
            truncation=True,
            max_length=512,
        )
    return _sentiment_pipeline


def get_toxicity_pipeline():
    global _toxicity_pipeline
    if _toxicity_pipeline is None:
        logging.info("Loading toxicity detection model (first call)...")
        _toxicity_pipeline = pipeline(
            "text-classification",
            model="unitary/toxic-bert",
            truncation=True,
            max_length=512,
        )
    return _toxicity_pipeline


def fetch_subtitles(video_id: str) -> str | None:
    try:
        ytt_api = YouTubeTranscriptApi()

        # Try English first, then any language
        try:
            transcript = ytt_api.fetch(video_id, languages=["en"])
            return " ".join([entry.text for entry in transcript])
        except Exception:
            pass

        # Fallback: try without language filter
        try:
            transcript = ytt_api.fetch(video_id)
            return " ".join([entry.text for entry in transcript])
        except Exception:
            pass

        return None

    except Exception as e:
        logging.warning(f"Could not fetch subtitles for {video_id}: {e}")
        return None


def chunk_text(text: str, max_words: int = 400) -> list[str]:
    words = text.split()
    chunks = []
    for i in range(0, len(words), max_words):
        chunk = " ".join(words[i : i + max_words])
        if chunk.strip():
            chunks.append(chunk)
    return chunks if chunks else [text[:2000]]


def analyze_text(text: str) -> dict:
    """Run sentiment + toxicity analysis on subtitle text."""
    chunks = chunk_text(text)
    sentiment_pipe = get_sentiment_pipeline()
    toxicity_pipe = get_toxicity_pipeline()

    sentiment_results = sentiment_pipe(chunks)
    neg_scores, pos_scores = [], []
    for r in sentiment_results:
        if r["label"] == "NEGATIVE":
            neg_scores.append(r["score"])
            pos_scores.append(1.0 - r["score"])
        else:
            pos_scores.append(r["score"])
            neg_scores.append(1.0 - r["score"])

    avg_negative = sum(neg_scores) / len(neg_scores)
    avg_positive = sum(pos_scores) / len(pos_scores)

    toxicity_results = toxicity_pipe(chunks)
    toxic_scores = []
    for r in toxicity_results:
        toxic_scores.append(r["score"] if r["label"] == "toxic" else 1.0 - r["score"])

    avg_toxicity = sum(toxic_scores) / len(toxic_scores) if toxic_scores else 0.0

    if avg_toxicity > 0.55:
        safety = "unsafe"
    elif avg_negative > 0.75 and avg_toxicity > 0.35:
        safety = "unsafe"
    else:
        safety = "safe"

    return {
        "sentiment_positive": round(avg_positive, 4),
        "sentiment_negative": round(avg_negative, 4),
        "toxicity_score": round(avg_toxicity, 4),
        "chunks_analyzed": len(chunks),
        "safety": safety,
    }


def extract_features(data):
    """Extract feature array from request data."""
    return np.array([[
        data["avg_screen_time"],
        data["social_media_hours"],
        data["gaming_hours"],
        data["night_usage"],
        data["phone_checks_per_day"],
        data["entertainment_ratio"],
        data["night_usage_ratio"],
        data["engagement_intensity"],
        data["gaming_ratio"],
        data["social_ratio"]
    ]])


# ══════════════════════════════════════════════
# ROUTES
# ══════════════════════════════════════════════

@app.route("/predict", methods=["POST"])
def predict():
    """Endpoint 1: Returns only the risk level prediction."""
    data = request.json

    required_fields = [
        "avg_screen_time", "social_media_hours", "gaming_hours", "night_usage",
        "phone_checks_per_day", "entertainment_ratio", "night_usage_ratio",
        "engagement_intensity", "gaming_ratio", "social_ratio"
    ]
    missing = [f for f in required_fields if f not in data]
    if missing:
        return jsonify({"error": f"Missing fields: {missing}"}), 400

    features = extract_features(data)
    prediction = model.predict(features)

    return jsonify({
        "risk_level": int(prediction[0])
    })


@app.route("/journal", methods=["POST"])
def journal():
    """Endpoint 2: Generates a wellness journal entry.
    Expects the same usage fields PLUS risk_level (from /predict).
    """
    data = request.json

    required_fields = [
        "avg_screen_time", "social_media_hours", "gaming_hours",
        "phone_checks_per_day", "risk_level"
    ]
    missing = [f for f in required_fields if f not in data]
    if missing:
        return jsonify({"error": f"Missing fields: {missing}"}), 400

    journal_text = generate_journal(data)

    return jsonify({
        "journal": journal_text
    })


@app.route("/analyze-subtitles", methods=["POST"])
def analyze_subtitles():
    data = request.json
    video_id = data.get("video_id")
    title = data.get("title")

    if not video_id and not title:
        return jsonify({"error": "video_id or title is required"}), 400

    if video_id:
        match = re.search(r"(?:v=|youtu\.be/)([a-zA-Z0-9_-]{11})", video_id)
        if match:
            video_id = match.group(1)

    if not video_id or len(video_id) != 11:
        if title:
            logging.info(f"Invalid or missing video_id. Searching YouTube for: {title}")
            import urllib.request
            import urllib.parse
            url = "https://www.youtube.com/results?search_query=" + urllib.parse.quote(title)
            try:
                html = urllib.request.urlopen(url).read().decode()
                video_ids = re.findall(r"watch\?v=([a-zA-Z0-9_-]{11})", html)
                if video_ids:
                    video_id = video_ids[0]
                    logging.info(f"Found fallback video ID: {video_id}")
            except Exception as e:
                logging.warning(f"YouTube search failed: {e}")

    if not video_id:
        return jsonify({"video_id": None, "subtitles_found": False, "safety": "unknown"})

    logging.info(f"Analyzing subtitles for video: {video_id}")
    subtitle_text = fetch_subtitles(video_id)

    if not subtitle_text or len(subtitle_text.strip()) < 30:
        return jsonify({
            "video_id": video_id,
            "subtitles_found": False,
            "safety": "unknown",
            "message": "No subtitles available for this video",
        })

    result = analyze_text(subtitle_text)
    result["video_id"] = video_id
    result["subtitles_found"] = True

    logging.info(f"Analysis complete for {video_id}: {result['safety']}")
    return jsonify(result)


@app.route("/health", methods=["GET"])
def health():
    return jsonify({"status": "ok"})


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=True, use_reloader=False)