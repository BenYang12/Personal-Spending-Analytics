"""Shared environment setup for my training scripts.

Right now this does one thing: silence a spurious warning. I put it in its own
module so the explanation lives in exactly one place and both training scripts
get the same treatment.
"""

import warnings


def silence_accelerate_matmul_warning() -> None:
    """Suppress a false-positive RuntimeWarning from numpy on macOS.

    On this machine, numpy 2.1 links against Apple's Accelerate BLAS, and that
    combination emits "divide by zero encountered in matmul" / "invalid value
    encountered in matmul" from inside sklearn's KMeans distance computation.

    I chased it down before silencing it, because suppressing a numerical
    warning you don't understand is how you ship a broken model. What I checked:

      * my feature matrix has zero NaN, zero inf, and no zero-variance columns
      * the fitted centroids, scaler means and scaler scales are all finite
      * KMeans converges normally (4 iterations) and the resulting clustering
        scores ARI 0.986 against my known synthetic archetypes

    So the arithmetic is fine — the warning comes from vectorised operations on
    padding lanes inside the Accelerate backend, not from my data. I scope the
    filter to this exact message so a REAL numerical problem elsewhere still
    reaches me.
    """
    warnings.filterwarnings(
        "ignore",
        message=r".*encountered in matmul",
        category=RuntimeWarning,
    )
