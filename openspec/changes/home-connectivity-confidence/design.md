# Design

Use the domain and service configurations from the path-comparison plan to match RAW observations to the IN session. Use only complete stage reports from the expected path. Count distinct scan sessions per target and stage. Record omitted, incomplete, and contradictory observations separately.

Keep the direct diagnosis and the HTTP recovery result independent. A recovery requires a direct HTTP failure and a complete successful HTTP check on the same configured target on the active path. Give partial or contradictory evidence low confidence. A relevant matched stage can give medium confidence. High confidence requires relevant matched stages on multiple targets and repeated direct observations. Retain the existing diagnosis categories and apply the same limits to the evidence exported from Home.
