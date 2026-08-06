# The scoring service.
#
# IMPORTANT: this image must be built from the REPOSITORY ROOT, not from
# scoring/ — see the `context: .` in docker-compose.yml. The service imports
# feature-engineering code from ../ml so that training and serving share one
# implementation, which means the build needs both directories in scope.
#
# That import is the deployment cost of avoiding training/serving skew, and it's
# the strongest argument for eventually publishing the feature code as a
# versioned package both sides depend on. Documented rather than hidden.

FROM python:3.12-slim
WORKDIR /app

# Dependencies before source, so a code edit doesn't re-install scikit-learn.
COPY scoring/requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

# The service itself.
COPY scoring/*.py ./

# The shared feature-engineering modules. Only the modules the service actually
# imports — the training scripts (train_clusters.py, evaluate.py) have no place
# in a serving image.
COPY ml/features.py ml/anomaly_features.py ml/categories.py /ml/

# The trained models, loaded once at startup.
COPY ml/models/ /ml/models/

# feature_bridge.py resolves ../ml relative to its own location, so /app/../ml
# is /ml — which is exactly where the modules above landed.
ENV LEDGERLENS_MODEL_DIR=/ml/models \
    PYTHONUNBUFFERED=1

RUN useradd --create-home scoring && chown -R scoring /app /ml
USER scoring

EXPOSE 8000

# /health reports models_loaded, so this checks the service is USEFUL, not just
# that the process is alive — a container serving HTTP with no models is worse
# than one that's down, because nothing fails over.
HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=5 \
    CMD python -c "import urllib.request,json,sys; \
        d=json.load(urllib.request.urlopen('http://localhost:8000/health')); \
        sys.exit(0 if d['models_loaded'] else 1)"

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
