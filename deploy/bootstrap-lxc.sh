#!/usr/bin/env bash
# One-time setup inside the new Debian LXC (as root). Idempotent. Prints no secrets.
# Machine-identity credentials are NOT created here: put them in
#   /root/.config/book-management/infisical.env  (INFISICAL_CLIENT_ID / INFISICAL_CLIENT_SECRET, mode 600)
#   /root/.config/book-management/project_id
set -euo pipefail
apt-get update -qq
apt-get install -y -qq ca-certificates curl gnupg postgresql-client-common git
# Docker CE
if ! command -v docker >/dev/null; then
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc
  . /etc/os-release
  echo "deb [signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian $VERSION_CODENAME stable" > /etc/apt/sources.list.d/docker.list
  apt-get update -qq && apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-compose-plugin
fi
# Infisical CLI
if ! command -v infisical >/dev/null; then
  curl -1sLf 'https://artifacts-cli.infisical.com/setup.deb.sh' | bash
  apt-get install -y -qq infisical
fi
install -d -m 700 /root/.config/book-management
install -d -m 755 /opt/book-management/deploy /var/lib/book-management
echo "bootstrap done"
