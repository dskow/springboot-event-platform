#!/bin/bash
set -e
echo "creating S3 archive bucket..."
awslocal s3 mb s3://event-archive
echo "S3 bucket ready"
