# Sync local source trees up to the LineageOS build VM.
# Usage:  .\sync-to-vm.ps1
$ErrorActionPreference = "Stop"

$GCLOUD   = "$env:LOCALAPPDATA\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
$INSTANCE = "instance-20260731-135250"
$ZONE     = "asia-southeast1-b"
$ROOT     = $PSScriptRoot
$ARCHIVE  = Join-Path $env:TEMP "lineage-code-sync.tgz"

if (-not (Test-Path $GCLOUD)) { throw "gcloud not found at $GCLOUD" }

# Top-level trees to sync. These must match the PATHS array in vm-apply-code.sh
# on the VM, otherwise files arrive on the VM but are never applied to the tree.
#
# `patches` is not one of those PATHS and is deliberately absent from it: it is not
# rsynced into the AOSP tree, it is staged and then applied to it. ADR-011 - the
# checkout consumes patches, it does not contain them.
$TREES = @("device", "frameworks", "patches")

Write-Host "==> Packing $($TREES -join ', ') (excluding .git)" -ForegroundColor Cyan
if (Test-Path $ARCHIVE) { Remove-Item $ARCHIVE -Force }
$present = @($TREES | Where-Object { Test-Path (Join-Path $ROOT $_) })
if ($present.Count -eq 0) { throw "none of the expected trees exist under $ROOT" }
tar -czf $ARCHIVE -C $ROOT --exclude=.git @present
if ($LASTEXITCODE -ne 0) { throw "tar failed (exit $LASTEXITCODE)" }
$sizeKB = [math]::Round((Get-Item $ARCHIVE).Length / 1KB, 1)
Write-Host "    archive: $sizeKB KB"

Write-Host "==> Uploading to the VM" -ForegroundColor Cyan
& $GCLOUD compute scp $ARCHIVE "${INSTANCE}:code-sync.tgz" --zone $ZONE --quiet
if ($LASTEXITCODE -ne 0) { throw "scp failed (exit $LASTEXITCODE)" }

Write-Host "==> Unpacking and rsyncing into the tree on the VM" -ForegroundColor Cyan
& $GCLOUD compute ssh $INSTANCE --zone $ZONE --quiet --command 'bash 05-apply-code.sh'
if ($LASTEXITCODE -ne 0) { throw "apply failed (exit $LASTEXITCODE)" }

Write-Host "==> DONE" -ForegroundColor Green
