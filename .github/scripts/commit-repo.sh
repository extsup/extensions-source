#!/bin/bash
set -e

git config --global user.email "239924903+extsup@users.noreply.github.com"
git config --global user.name "extsup-bot"
git status
if [ -n "$(git status --porcelain)" ]; then
    git add .
    git commit -m "Update extensions repo"
    git push

    # Hapus atau ubah baris ini sesuai repo kamu
    # curl https://purge.jsdelivr.net/gh/extsup/extensions@repo/index.min.json
else
    echo "No changes to commit"
fi