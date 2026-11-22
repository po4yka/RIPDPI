# Design

Query retained scan history for a completed status and a completion timestamp. Order by completion time and use the start time and session ID to break ties. Keep the size guard in the Room projection. Use the selected ID to load saved probe results and session artifacts. Record a missing full report in archive completeness. Use the existing live-state archive when no completed session exists. The share screen supplies a session ID for an explicit selection.
