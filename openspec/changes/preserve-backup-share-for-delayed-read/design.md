# Design: Delayed backup share reads

Task ID: `AND-1790432927304098`

## Context

Android's chooser result does not signal that the selected recipient has
finished reading a shared URI. The current temporary-file owner deletes its
file when the chooser returns or the ViewModel clears. A second share also
deletes every file in the share cache directory.

## Decision

- Keep ownership of a file during generation. Delete it immediately if
  generation or chooser launch fails.
- After chooser launch succeeds, release the owner's deletion claim. The
  FileProvider URI remains backed by a private cache file even if the screen
  closes or the chooser returns.
- Create unique filenames. Prune regular files older than 24 hours or dated
  after the current device time from the dedicated `backup-share` cache
  directory when the app starts, the screen opens, or a new share starts.
  Keep other recent files for delayed reads.
- Continue to grant read-only URI access through the existing FileProvider.
  Do not treat chooser cancellation or result delivery as recipient completion.

## Trade-off

The redacted backup can remain in the app cache for up to the next cleanup
after 24 hours; the operating system may evict cache earlier. The app cannot
know when an external recipient has finished reading. A wall-clock change can
expire the retention early, so the 24-hour period is best-effort.
