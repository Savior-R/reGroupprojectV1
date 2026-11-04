#!/usr/bin/env bash
set -euo pipefail

if ! command -v ffmpeg >/dev/null 2>&1; then
  echo "SKIP: ffmpeg not found; demo DB rows reference object keys but MinIO assets were not uploaded."
  exit 0
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

ffmpeg -hide_banner -loglevel error -f lavfi -i testsrc=size=640x360:rate=25 -t 2 -pix_fmt yuv420p "$tmp/camera.webm" -y
ffmpeg -hide_banner -loglevel error -f lavfi -i testsrc=size=640x360:rate=25 -t 2 -pix_fmt yuv420p "$tmp/screen.webm" -y
ffmpeg -hide_banner -loglevel error -f lavfi -i sine=frequency=440:sample_rate=16000 -t 2 -c:a libopus "$tmp/audio.webm" -y
ffmpeg -hide_banner -loglevel error -f lavfi -i color=c=white:s=640x360 -frames:v 1 "$tmp/frame.jpg" -y

minio_image="${MINIO_IMAGE:-swr.cn-north-4.myhuaweicloud.com/ddn-k8s/quay.io/minio/minio:latest}"
docker run --rm -v "$tmp":/assets \
  -e MINIO_ROOT_USER="${MINIO_ROOT_USER:-minioadmin}" \
  -e MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD:-minioadmin}" \
  --network shijiangtai_default --entrypoint sh "$minio_image" -c '
    mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
    mc mb --ignore-existing local/shijiangtai >/dev/null
    for i in 1 2 3 4 5; do
      sid=$(printf "70000000-0000-0000-0000-%012d" "$i")
      base="recordings/10000000-0000-0000-0000-000000000001/task/60000000-0000-0000-0000-000000000001/$sid"
      mc cp /assets/camera.webm "local/shijiangtai/$base/camera.webm" >/dev/null
      mc cp /assets/screen.webm "local/shijiangtai/$base/screen.webm" >/dev/null
      mc cp /assets/audio.webm "local/shijiangtai/$base/audio.webm" >/dev/null
      mc cp /assets/frame.jpg "local/shijiangtai/frames/session-$i.jpg" >/dev/null
    done
  '

echo "Demo media assets uploaded to MinIO bucket shijiangtai."
