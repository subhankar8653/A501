#!/usr/bin/env bash
# One-time VPS prep (Ubuntu/Debian). Run as root:  bash vps-setup.sh
# Installs Docker and closes every inbound port except SSH. The backend needs
# NO inbound port at all (Cloudflare Tunnel connects outwards).
set -euo pipefail

if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sh
fi

apt-get update -y
apt-get install -y ufw rsync

SSH_PORT="${SSH_PORT:-22}"
ufw default deny incoming
ufw default allow outgoing
ufw allow "${SSH_PORT}/tcp"
ufw --force enable

systemctl enable --now docker
echo "Done. Docker installed, firewall: only SSH (${SSH_PORT}) open."
