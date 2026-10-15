#!/usr/bin/env bash
# Probe: playwright + chromium availability inside WSL Debian
export PATH=/opt/node-22/bin:$PATH

PWROOT=/opt/node-22/lib/node_modules/playwright

echo "node      : $(node -v 2>&1)"
echo "npm       : $(npm -v 2>&1)"
echo "pw root   : $PWROOT"
if [ -d "$PWROOT" ]; then
  echo "pw version: $(node -p "require('$PWROOT/package.json').version" 2>&1)"
else
  echo "pw root   : MISSING"
fi

echo "--- chromium executables in ms-playwright cache ---"
if [ -d "$HOME/.cache/ms-playwright" ]; then
  ls -1 "$HOME/.cache/ms-playwright" 2>&1
else
  echo "(no \$HOME/.cache/ms-playwright)"
fi

echo "--- root-owned cache (playwright ran as root before?) ---"
if [ -d /root/.cache/ms-playwright ]; then
  ls -1 /root/.cache/ms-playwright 2>&1
else
  echo "(no /root/.cache/ms-playwright)"
fi

echo "--- whoami / HOME ---"
echo "user: $(whoami)  HOME=$HOME"