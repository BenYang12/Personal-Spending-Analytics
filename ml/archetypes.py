"""Turn KMeans centroids into archetype names a human can read.

KMeans hands me back "cluster 3", which is useless to a user and nearly useless
to me. This module is the bridge from a centroid to "Weekend Spender" — and I
want to be precise about why it's written the way it is.

The naming is RULE-BASED, not an LLM and not hand-labelled. That matters for
three reasons:

  1. It's deterministic. The same centroid always produces the same name, so a
     user's archetype doesn't flicker between page loads.
  2. It's explainable. Every name comes with the specific features that earned
     it, so I can always answer "why am I a Weekend Spender?" with numbers.
  3. It survives retraining. If I retrain and cluster 3 becomes cluster 1, the
     names follow the BEHAVIOUR rather than the cluster index. Hand-labelling
     would silently break the moment the label ordering changed.

The trick that makes this work: I read centroids in STANDARDISED space, where
each coordinate is already "how many standard deviations from the average
month". So a centroid value of +1.4 on weekend_ratio means "this group spends a
lot more on weekends than typical" without me needing to know what a typical
weekend ratio is in dollars.
"""

import numpy as np

# How far from average (in standard deviations) a feature must sit before I'll
# describe it. 0.6 is a judgement call: lower and every cluster gets six
# adjectives, higher and bland-but-real clusters get no description at all.
NOTABLE = 0.6

# My rules, checked in order. Each is (name, predicate over the standardised
# centroid). First match wins, so I put the most specific patterns first.
#
# I derived these from the archetypes I built into synthesize.py, which is
# honest but worth stating plainly: I designed the generator and the readout
# against the same behavioural axes. The independent check that this isn't
# circular is the Adjusted Rand Index in train_clusters.py — that measures
# whether the CLUSTERING recovered the groups, which the naming can't fake.
RULES = [
    (
        "Subscription Creep",
        lambda f: f["recurring_share"] > 0.8 and f["share_subscriptions"] > 0.5,
    ),
    (
        "Weekend Spender",
        lambda f: f["weekend_ratio"] > 0.7 and f["share_dining"] > 0.2,
    ),
    (
        "Big-Ticket Buyer",
        lambda f: f["avg_ticket"] > 0.8 and f["txn_count"] < -0.3,
    ),
    (
        "Frequent Small Spender",
        lambda f: f["txn_count"] > 0.8 and f["avg_ticket"] < -0.4,
    ),
    (
        "Essentials Focused",
        lambda f: f["share_groceries"] > 0.5 and f["weekend_ratio"] < 0.1,
    ),
    (
        "Retail Heavy",
        lambda f: f["share_shopping"] > 0.7,
    ),
    (
        "Dining Led",
        lambda f: f["share_dining"] > 0.6,
    ),
    (
        "Commuter",
        lambda f: f["share_transport"] > 0.7,
    ),
    (
        "High Fixed Costs",
        lambda f: f["fixed_share"] > 0.8,
    ),
]

# Plain-English phrasing for each feature when it's notably high or low. This is
# what lets me explain a name instead of just asserting it.
PHRASES = {
    "txn_count": ("makes many small purchases", "transacts rarely"),
    "avg_ticket": ("has large typical purchases", "has small typical purchases"),
    "ticket_variability": ("purchase sizes vary a lot", "purchase sizes are consistent"),
    "merchant_diversity": ("shops at many different merchants", "sticks to a few merchants"),
    "weekend_ratio": ("spends mostly on weekends", "spends mostly on weekdays"),
    "recurring_share": ("carries a lot of subscriptions", "has few subscriptions"),
    "fixed_share": ("has high fixed costs", "has low fixed costs"),
    "spend_to_income": ("spends most of their income", "saves a large share of income"),
    "share_dining": ("eats out often", "rarely eats out"),
    "share_groceries": ("spends heavily on groceries", "spends little on groceries"),
    "share_subscriptions": ("is subscription-heavy", "has minimal subscriptions"),
    "share_transport": ("spends heavily on transport", "spends little on transport"),
    "share_shopping": ("shops a lot", "shops rarely"),
    "share_entertainment": ("spends on entertainment", "spends little on entertainment"),
}


def _describe(centroid: dict[str, float], limit: int = 3) -> tuple[str, list[dict]]:
    """Explain a centroid via its most extreme features."""
    ranked = sorted(centroid.items(), key=lambda kv: abs(kv[1]), reverse=True)

    phrases, evidence = [], []
    for feature, value in ranked:
        if abs(value) < NOTABLE or feature not in PHRASES:
            continue
        high, low = PHRASES[feature]
        phrases.append(high if value > 0 else low)
        evidence.append({
            "feature": feature,
            # I round for display but keep the sign, since direction is the
            # whole message.
            "std_devs_from_average": round(float(value), 2),
        })
        if len(phrases) == limit:
            break

    if not phrases:
        # A cluster with nothing extreme is genuinely the middle of my data. I
        # say so rather than inventing a personality for it.
        return "Close to average on every behaviour I measure.", []

    return "This group " + ", ".join(phrases) + ".", evidence


def name_clusters(kmeans, scaler, feature_names: list[str]) -> dict[int, dict]:
    """Give every cluster a name, a description, and its supporting numbers.

    `kmeans.cluster_centers_` is already in standardised space, which is exactly
    where I want to read it — see the module docstring. I use `scaler` only to
    recover the real-world values for display.
    """
    names: dict[int, dict] = {}
    used: set[str] = set()

    for cluster_id, centroid_scaled in enumerate(kmeans.cluster_centers_):
        centroid = dict(zip(feature_names, centroid_scaled))

        label = None
        for candidate, rule in RULES:
            # Two clusters matching the same rule would be confusing to a user,
            # so the first claim wins and the runner-up falls through to the
            # descriptive fallback below.
            if candidate not in used and rule(centroid):
                label = candidate
                break

        if label is None:
            # Fallback: name the cluster after its single most extreme feature.
            # An honest generic name beats a wrong specific one.
            top_feature, top_value = max(centroid.items(), key=lambda kv: abs(kv[1]))
            if abs(top_value) < NOTABLE:
                label = "Balanced Spender"
            else:
                direction = "High" if top_value > 0 else "Low"
                pretty = top_feature.replace("share_", "").replace("_", " ").title()
                label = f"{direction} {pretty}"

        used.add(label)
        description, evidence = _describe(centroid)

        # I also record the centroid in ORIGINAL units, because "median ticket
        # $187" is far more convincing in a UI than "+1.4 standard deviations".
        real_units = scaler.inverse_transform(centroid_scaled.reshape(1, -1))[0]

        names[cluster_id] = {
            "name": label,
            "description": description,
            "evidence": evidence,
            "centroid_real_units": {
                feature: round(float(value), 3)
                for feature, value in zip(feature_names, real_units)
            },
        }

    return names


def explain_month(features_row: dict[str, float], scaler, feature_names: list[str],
                  limit: int = 3) -> list[dict]:
    """Explain one specific month against the population average.

    My dashboard needs to say "you're a Weekend Spender BECAUSE 78% of your
    spending was Fri-Sun, versus 42% typical". This produces that comparison,
    reusing the fitted scaler so "typical" means the training population rather
    than something I made up.
    """
    values = np.array([[features_row[name] for name in feature_names]], dtype=float)
    scaled = scaler.transform(values)[0]

    ranked = sorted(zip(feature_names, scaled, values[0]),
                    key=lambda item: abs(item[1]), reverse=True)

    out = []
    for feature, z, raw in ranked[:limit]:
        index = feature_names.index(feature)
        out.append({
            "feature": feature,
            "your_value": round(float(raw), 3),
            "population_average": round(float(scaler.mean_[index]), 3),
            "std_devs_from_average": round(float(z), 2),
        })
    return out
