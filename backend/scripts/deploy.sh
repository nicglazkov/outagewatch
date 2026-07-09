#!/usr/bin/env bash
# Deploy the OutageWatch backend to Cloud Run.
#
# Two services from one image:
#   outagewatch-api    - public API for the apps and web page
#   outagewatch-poller - private; Cloud Scheduler hits /internal/poll every 5 min
#
# Requires: gcloud authed with access to the outagewatch project.
# Always runs against the outagewatch gcloud config; never touches the global one.
set -euo pipefail

export CLOUDSDK_ACTIVE_CONFIG_NAME=outagewatch
PROJECT=outagewatch
REGION=us-west1
SA="outagewatch-runtime@${PROJECT}.iam.gserviceaccount.com"
SCHEDULER_SA="outagewatch-scheduler@${PROJECT}.iam.gserviceaccount.com"

cd "$(dirname "$0")/.."

gcloud run deploy outagewatch-poller \
  --source . --project "$PROJECT" --region "$REGION" \
  --service-account "$SA" \
  --no-allow-unauthenticated \
  --set-env-vars "GOOGLE_CLOUD_PROJECT=${PROJECT},DATA_BUCKET=outagewatch-data,ENABLE_POLL=1" \
  --memory 512Mi --max-instances 1

gcloud run deploy outagewatch-api \
  --source . --project "$PROJECT" --region "$REGION" \
  --service-account "$SA" \
  --allow-unauthenticated \
  --set-env-vars "GOOGLE_CLOUD_PROJECT=${PROJECT},DATA_BUCKET=outagewatch-data,ENABLE_POLL=0" \
  --memory 512Mi --max-instances 4

POLLER_URL=$(gcloud run services describe outagewatch-poller \
  --project "$PROJECT" --region "$REGION" --format='value(status.url)')

gcloud scheduler jobs create http outagewatch-poll \
  --project "$PROJECT" --location "$REGION" \
  --schedule "*/5 * * * *" \
  --uri "${POLLER_URL}/internal/poll" \
  --http-method POST \
  --oidc-service-account-email "$SCHEDULER_SA" \
  --attempt-deadline 180s \
  2>/dev/null || gcloud scheduler jobs update http outagewatch-poll \
  --project "$PROJECT" --location "$REGION" \
  --schedule "*/5 * * * *" \
  --uri "${POLLER_URL}/internal/poll" \
  --http-method POST \
  --oidc-service-account-email "$SCHEDULER_SA" \
  --attempt-deadline 180s

echo "Deployed. API: $(gcloud run services describe outagewatch-api \
  --project "$PROJECT" --region "$REGION" --format='value(status.url)')"
