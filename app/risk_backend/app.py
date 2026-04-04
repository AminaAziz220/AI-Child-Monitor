from journal_generator import generate_journal
from flask import Flask, request, jsonify
import joblib
import numpy as np

app = Flask(__name__)

# Load trained model
model = joblib.load("risk_model.pkl")

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


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=True)