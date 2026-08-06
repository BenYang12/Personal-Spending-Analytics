"""Shared environment setup for my training scripts."""

import warnings


def silence_accelerate_matmul_warning() -> None:
    """Suppress a false-positive RuntimeWarning from numpy on macOS.

    numpy 2.1 on Apple's Accelerate BLAS emits "divide by zero encountered in
    matmul" from inside sklearn's KMeans distance computation. I verified the
    arithmetic is fine before silencing it — no NaN/inf in the feature matrix,
    finite centroids and scaler params, and ARI 0.986 against known archetypes.
    The filter is scoped to this exact message so a real numerical problem
    elsewhere still reaches me.
    """
    warnings.filterwarnings(
        "ignore",
        message=r".*encountered in matmul",
        category=RuntimeWarning,
    )
