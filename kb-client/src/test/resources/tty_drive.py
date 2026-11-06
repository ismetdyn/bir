"""Gives kb a real terminal for a test: the JDK cannot open a pseudo-terminal, Python's pty can.

Usage: python3 -I tty_drive.py TEXT KEYS SCREEN_SECONDS EXIT_SECONDS -- COMMAND...

Starts COMMAND on a new 80x24 pseudo-terminal, waits until TEXT appears in its output, types KEYS,
then waits for COMMAND to end. Writes everything COMMAND wrote to the terminal to stdout and one
status line to stderr: "exit=<code>", "timeout:screen" (TEXT never came), "timeout:exit" (still
running EXIT_SECONDS after KEYS; then killed) or "ended-before-keys exit=<code>".
"""

import fcntl
import os
import pty
import select
import signal
import struct
import sys
import termios
import time

ROWS, COLUMNS = 24, 80
POLL_SECONDS = 0.1
CHUNK = 65536


def main():
    text, keys = sys.argv[1].encode(), sys.argv[2].encode()
    screen_seconds, exit_seconds = float(sys.argv[3]), float(sys.argv[4])
    command = sys.argv[sys.argv.index("--") + 1 :]
    pid, fd = pty.fork()
    if pid == 0:
        os.execvp(command[0], command)
    fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", ROWS, COLUMNS, 0, 0))
    output = bytearray()

    def finish(status):
        sys.stdout.buffer.write(output)
        sys.stdout.flush()
        sys.stderr.write(status + "\n")
        sys.exit(0)

    def kill_and_finish(status):
        # The child leads its own session, so its group holds everything it started.
        os.killpg(pid, signal.SIGKILL)
        os.waitpid(pid, 0)
        finish(status)

    def exit_status():
        _, status = os.waitpid(pid, 0)
        return os.waitstatus_to_exitcode(status)

    def read_until(done, seconds):
        """True when done() held; False when the terminal closed; None on timeout."""
        deadline = time.monotonic() + seconds
        while not done():
            left = deadline - time.monotonic()
            if left <= 0:
                return None
            ready, _, _ = select.select([fd], [], [], min(left, POLL_SECONDS))
            if not ready:
                continue
            try:
                chunk = os.read(fd, CHUNK)
            except OSError:  # EIO: every process holding the terminal has ended.
                return False
            if not chunk:
                return False
            output.extend(chunk)
        return True

    seen = read_until(lambda: text in output, screen_seconds)
    if seen is None:
        kill_and_finish("timeout:screen")
    if seen is False:
        finish("ended-before-keys exit=%d" % exit_status())
    os.write(fd, keys)
    if read_until(lambda: False, exit_seconds) is None:
        kill_and_finish("timeout:exit")
    finish("exit=%d" % exit_status())


main()
