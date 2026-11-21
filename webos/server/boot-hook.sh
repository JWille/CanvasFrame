#!/bin/sh
# CanvasFrame boot hook. Installed as /var/lib/webosbrew/init.d/60-canvasframe (no dot in the name:
# BusyBox run-parts skips files with one). Remove that file to stop CanvasFrame starting at boot.
#
# Processes started from boot hooks with `nohup ... &` (or setsid) are killed when the Homebrew Channel's
# startup script finishes, so everything is started with start-stop-daemon -b (fully detached), as
# Glasshouse does. A small watchdog keeps the server running and restarts it if it ever stops.
#
#   60-canvasframe          start the watchdog (which starts the server with --boot)
#   60-canvasframe watch    the watchdog loop itself
APP=/media/developer/apps/usr/palm/applications/com.canvasframe.home
SERVER="$APP/server/server.js"
PIDFILE=/tmp/canvasframe.pid
WATCHPID=/tmp/canvasframe-watch.pid
[ -x /usr/bin/node ] || exit 0
[ -f "$SERVER" ] || exit 0

server_running() { [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; }
start_server() {
  # The server writes its own pid file and replaces any older copy.
  # Own pid file for start-stop-daemon (-m), so it never mistakes another /bin/sh for this one.
  start-stop-daemon -S -b -m -p /tmp/canvasframe-sd.pid -x /bin/sh -- -c "cd '$APP/server' && exec /usr/bin/node server.js $1 >/tmp/canvasframe.out 2>&1"
}

if [ "$1" = "watch" ]; then
  start_server --boot
  while true; do
    sleep 20
    [ -f "$SERVER" ] || exit 0                      # app uninstalled
    [ -f /tmp/canvasframe.stop ] && continue         # paused on purpose (deploys, restore)
    server_running || start_server
  done
fi

# Start the watchdog unless it's already running.
if [ -f "$WATCHPID" ] && kill -0 "$(cat "$WATCHPID")" 2>/dev/null; then exit 0; fi
start-stop-daemon -S -b -m -p "$WATCHPID" -x /bin/sh -- "$0" watch
exit 0
