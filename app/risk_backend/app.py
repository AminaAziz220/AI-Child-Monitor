from flask import Flask, request, jsonify
import joblib
import numpy as np

app = Flask(__name__)

# Load trained model
model = joblib.load("risk_model.pkl")

@app.route("/predict", methods=["POST"])
def predict():
    data = request.json

    features = np.array([[ 
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

    prediction = model.predict(features)

    return jsonify({
        "risk_level": int(prediction[0])
    })

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=True)

