#!/bin/sh
set -eu

required_swap_bytes=2000000000
target_swappiness=10

if ! command -v docker >/dev/null 2>&1; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get update
    apt-get install -y ca-certificates curl gnupg
    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
        -o /etc/apt/keyrings/docker.asc
    chmod a+r /etc/apt/keyrings/docker.asc
    . /etc/os-release
    printf '%s\n' \
        "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${VERSION_CODENAME} stable" \
        > /etc/apt/sources.list.d/docker.list
    apt-get update
    apt-get install -y \
        docker-ce \
        docker-ce-cli \
        containerd.io \
        docker-buildx-plugin \
        docker-compose-plugin
fi

systemctl enable --now docker

current_swap_bytes=$(awk 'NR > 1 { total += $3 * 1024 } END { print total + 0 }' /proc/swaps)
if [ "$current_swap_bytes" -lt "$required_swap_bytes" ]; then
    if [ ! -f /swapfile ]; then
        fallocate -l 2G /swapfile
        chmod 600 /swapfile
        mkswap /swapfile
    fi
    swapon /swapfile
    if ! grep -Fq '/swapfile none swap sw 0 0' /etc/fstab; then
        printf '%s\n' '/swapfile none swap sw 0 0' >> /etc/fstab
    fi
fi

printf 'vm.swappiness=%s\n' "$target_swappiness" > /etc/sysctl.d/90-edutwin.conf
sysctl -w "vm.swappiness=$target_swappiness" >/dev/null

install -d -m 0750 /opt/edutwin/releases /opt/edutwin/shared

docker --version
docker compose version
swapon --show --bytes
sysctl vm.swappiness
