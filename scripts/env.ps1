# Dot-source this in PowerShell before running gcloud/firebase commands:
#   . .\scripts\env.ps1
# Keeps this repo pinned to the outagewatch GCP project without touching the
# machine-global gcloud config (other projects run on this machine too).
$env:CLOUDSDK_ACTIVE_CONFIG_NAME = "outagewatch"
$env:GOOGLE_CLOUD_PROJECT = "outagewatch"
Write-Host "gcloud config: outagewatch (project: outagewatch)"
