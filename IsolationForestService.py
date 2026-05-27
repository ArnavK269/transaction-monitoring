from flask import Flask
from flask import request

from sklearn.ensemble import IsolationForest

import numpy as np

import re

import requests

app = Flask(__name__)

# -------------------------------------------------
# MUCH BROADER TRAINING DATA
# -------------------------------------------------

X = np.array([

    # VERY LOW RISK

    [1000, 1, 1000, 1000, 1000, 0, 0.1, 0.1],
    [2000, 2, 1000, 1500, 500, 0, 0.2, 0.1],
    [5000, 3, 1666, 3000, 500, 1, 0.2, 0.2],
    [8000, 4, 2000, 4000, 500, 1, 0.3, 0.2],
    [12000, 5, 2400, 5000, 500, 1, 0.3, 0.2],

    # LOW RISK

    [20000, 5, 4000, 10000, 1000, 2, 0.4, 0.2],
    [30000, 6, 5000, 15000, 1000, 2, 0.5, 0.2],
    [50000, 8, 6250, 25000, 1000, 3, 0.5, 0.3],
    [70000, 10, 7000, 30000, 1000, 4, 0.6, 0.3],

    # MEDIUM RISK

    [100000, 12, 8333, 50000, 1000, 6, 0.7, 0.4],
    [150000, 15, 10000, 70000, 1000, 8, 0.75, 0.5],
    [250000, 20, 12500, 100000, 1000, 10, 0.8, 0.5],
    [400000, 25, 16000, 150000, 1000, 12, 0.82, 0.6],

    # HIGH RISK

    [700000, 30, 23000, 300000, 1000, 20, 0.9, 0.7],
    [1000000, 40, 25000, 500000, 1000, 25, 0.92, 0.75],
    [2000000, 60, 33333, 1000000, 1000, 40, 0.95, 0.85],

    # EXTREME

    [5000000, 80, 62500, 2500000, 1000, 60, 0.98, 0.95],
    [10000000, 120, 83333, 5000000, 1000, 90, 0.99, 0.99]

])

# -------------------------------------------------
# IMPROVED MODEL
# -------------------------------------------------

model = IsolationForest(

    contamination=0.08,

    n_estimators=300,

    max_samples='auto',

    random_state=42
)

model.fit(X)

# -------------------------------------------------
# HOME
# -------------------------------------------------

@app.route("/")
def home():

    return {

        "status": "RUNNING",

        "service": "AML ML Service"
    }

# -------------------------------------------------
# BETTER AML SCORING
# -------------------------------------------------

@app.route("/predict")
def predict():

    try:

        total = float(
            request.args.get("total", 0)
        )

        count = int(
            request.args.get("count", 1)
        )

        avg = float(
            request.args.get("avg", 0)
        )

        max_amt = float(
            request.args.get("max", 0)
        )

        min_amt = float(
            request.args.get("min", 0)
        )

        bank = int(
            request.args.get("bank", 0)
        )

        cashratio = float(
            request.args.get(
                "cashratio",
                0
            )
        )

        rapidratio = float(
            request.args.get(
                "rapidratio",
                0
            )
        )

        # -----------------------------------------
        # FEATURE VECTOR
        # -----------------------------------------

        sample = np.array([[

            total,
            count,
            avg,
            max_amt,
            min_amt,
            bank,
            cashratio,
            rapidratio

        ]])

        # -----------------------------------------
        # RAW ML SCORE
        # -----------------------------------------

        score = model.decision_function(
            sample
        )[0]

        # -----------------------------------------
        # SMART NORMALIZATION
        # -----------------------------------------

        normalized = (
            (0.4 - score) * 120
        )

        # -----------------------------------------
        # RULE-BASED BOOSTS
        # -----------------------------------------

        if total > 1000000:
            normalized += 8

        if total > 5000000:
            normalized += 12

        if count > 40:
            normalized += 10

        if avg > 50000:
            normalized += 8

        if max_amt > 1000000:
            normalized += 12

        if rapidratio > 0.7:
            normalized += 10

        if cashratio > 0.85:
            normalized += 10

        # -----------------------------------------
        # LOWER NORMAL ACTIVITY
        # -----------------------------------------

        if total < 50000:
            normalized -= 15

        if count < 5:
            normalized -= 10

        # -----------------------------------------
        # FINAL CLAMP
        # -----------------------------------------

        normalized = max(
            1,
            min(99, normalized)
        )

        return str(
            round(normalized, 2)
        )

    except Exception as e:

        print(e)

        return "0"

# -------------------------------------------------
# AI SEARCH PARSER
# -------------------------------------------------

@app.route(
    "/ai-search",
    methods=["POST"]
)
def ai_search():

    try:

        data = request.get_json()

        query = data.get(
            "message",
            ""
        )

        prompt = f"""

You are an AML search parser.

Extract search filters from the user query.

Return ONLY JSON.

Possible fields:

caseId
customerId
severity
rules
name

Severity must be:
HIGH
MEDIUM
LOW

Rules must be an array.

Example:

{{
  "caseId": null,
  "customerId": "1462",
  "severity": "HIGH",
  "rules": [],
  "name": null
}}

User query:
{query}

"""

        response = requests.post(

            "http://host.docker.internal:11434/api/generate",

            json={

                "model": "llama3",

                "prompt": prompt,

                "stream": False

            }

        )

        result = response.json()

        text = result["response"]

        # ---------------------------------
        # EXTRACT JSON SAFELY
        # ---------------------------------

        start = text.find("{")

        end = text.rfind("}") + 1

        json_text = text[start:end]

        import json

        parsed = json.loads(json_text)

        return parsed

    except Exception as e:

        print(e)

        return {

            "caseId": None,

            "customerId": None,

            "severity": None,

            "rules": [],

            "name": None

        }
def ai_search():

    try:

        data = request.get_json()

        query = data.get(
            "message",
            ""
        ).lower()

        result = {

            "caseId": None,

            "customerId": None,

            "severity": None,

            "rules": [],

            "name": None
        }

        case_match = re.search(
            r'aml[- ]?(\\d+[- ]?\\d+)',
            query
        )

        if case_match:

            value = case_match.group(1)

            value = value.replace(
                ' ',
                '-'
            )

            result["caseId"] = \
                f"AML-{value}"

        customer_match = re.search(
            r'\\b\\d{4}\\b',
            query
        )

        if customer_match:

            result["customerId"] = \
                customer_match.group(0)

        if "high" in query:
            result["severity"] = "HIGH"

        elif "medium" in query:
            result["severity"] = "MEDIUM"

        elif "low" in query:
            result["severity"] = "LOW"

        rules_map = {

            "rapid":
                "RAPID_MOVEMENT",

            "movement":
                "RAPID_MOVEMENT",

            "mismatch":
                "TRADE_TRANSACTION_MISMATCH",

            "structuring":
                "STRUCTURING_PATTERN",

            "high value":
                "MULTIPLE_HIGH_VALUE_TRANSACTIONS"
        }

        for key, value in \
                rules_map.items():

            if key in query:

                result["rules"].append(
                    value
                )

        name_match = re.search(
            r'name\\s+(\\w+)',
            query
        )

        if name_match:

            result["name"] = \
                name_match.group(1)

        return result

    except Exception as e:

        print(e)

        return {

            "error": str(e)

        }, 500

# -------------------------------------------------
# RUN
# -------------------------------------------------

if __name__ == "__main__":

    app.run(

        host="0.0.0.0",

        port=5000,

        debug=True
    )
