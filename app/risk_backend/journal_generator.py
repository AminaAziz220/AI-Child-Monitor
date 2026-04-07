from dotenv import load_dotenv
load_dotenv()  # add this at the top
import os
from google import genai

# Load API key from environment variable (never hardcode secrets!)
api_key = os.environ.get("GEMINI_API_KEY")
if not api_key:
    raise ValueError("GEMINI_API_KEY environment variable is not set.")

client = genai.Client(api_key=api_key)

def generate_journal(data):
    raw_summary = f"""
    Screen time: {data['avg_screen_time']} hours
    Risk level: {data['risk_level']}
    Gaming hours: {data['gaming_hours']}
    Social media hours: {data['social_media_hours']}
    Phone checks per day: {data['phone_checks_per_day']}
    """

    prompt = f"""
    You are an empathetic child psychologist and digital wellness coach.
    Review the child's daily device usage data below and write a short, supportive "Daily Digital Wellness Journal" for their parent.

    Today's Data:
    {raw_summary}

    Strict Guidelines:
    1. Tone: Warm, calm, non-judgmental, and encouraging. Avoid alarming language, even if the risk level is 'High'.
    2. Narrative Flow: Write 2-3 short, cohesive paragraphs. Do NOT use bullet points, bold text, or rigid headers. Weave the raw numbers naturally into sentences.
    3. Content Structure:
       - Summarize the day's digital footprint in a friendly opening.
       - Gently explain what the numbers mean for their wellbeing (e.g., instead of "70 pickups," say "They reached for their phone quite often today").
       - Always include a positive observation or "silver lining."
       - Conclude with one gentle, open-ended conversation starter the parent can use tonight to discuss screen time without it feeling like an interrogation.
    4. Constraints: Keep it strictly under 150 words. Output ONLY the final journal entry text with no introductory or concluding remarks.
    """

    try:
        response = client.models.generate_content(
            model="gemini-2.5-flash",
            contents=prompt,
        )
        return response.text

    except Exception as e:
        print("Journal generation failed:", e)
        return (
            "Today's usage summary shows moderate digital activity. "
            "Consider encouraging balanced screen habits and regular breaks."
        )