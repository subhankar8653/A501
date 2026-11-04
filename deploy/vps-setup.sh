#!/usr/bin/env bash
# One-time VPS prep (Ubuntu/Debian). Run as root:  bash vps-setup.sh
# Installs Docker + firewall (SSH + Cloudflare on port 80). NOTE: Docker-published ports bypass ufw,
# so the real guard is ORIGIN_KEY (direct hits get 404).
set -euo pipefail
command -v docker >/dev/null 2>&1 || curl -fsSL https://get.docker.com | sh
apt-get update -y && apt-get install -y ufw rsync curl
SSH_PORT="${SSH_PORT:-22}"
ufw default deny incoming
ufw default allow outgoing
ufw allow "${SSH_PORT}/tcp"
for ip in $(curl -fsS https://www.cloudflare.com/ips-v4); do ufw allow from "$ip" to any port 80 proto tcp; done
for ip in $(curl -fsS https://www.cloudflare.com/ips-v6); do ufw allow from "$ip" to any port 80 proto tcp; done
ufw --force enable
systemctl enable --now docker
echo "Done. Docker ready."
