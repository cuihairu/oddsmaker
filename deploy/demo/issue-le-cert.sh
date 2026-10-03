#!/usr/bin/env bash
# Cloudflare 代理的 origin 指到本机后，重签 Let's Encrypt 证书并把 nginx 切到 LE 路径。
# 背景：CF 代理未指到本机前，HTTP-01 验证会被 CF 以 530 拒绝（origin 不可达），
# nginx 一直用 /etc/nginx/ssl/oddsmaker 的自签占位（CF Full 非 strict 可正常回源）。
set -euo pipefail

DOMAIN=oddsmaker.cuihairu.site
SITE=/etc/nginx/sites-available/$DOMAIN

certbot certonly --webroot -w /var/www/html -d "$DOMAIN" --non-interactive \
  --agree-tos --register-unsafely-without-email

# 自签占位 → LE 路径，reload 后由 certbot 的 systemd timer 自动续期
sed -i "s|/etc/nginx/ssl/oddsmaker|/etc/letsencrypt/live/$DOMAIN|g" "$SITE"
nginx -t && systemctl reload nginx
echo "LE certificate active for $DOMAIN"
