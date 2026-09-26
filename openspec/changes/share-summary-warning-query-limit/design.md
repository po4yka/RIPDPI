# Design

Task ID: `DGN-1790431550325964`

Add dedicated warning-event methods to the existing artifact read and query stores. Room applies `lower(level) IN ('warn', 'error')` before `ORDER BY createdAt DESC LIMIT :limit`; the selected-session variant also filters by session ID. The share builder uses these methods with its existing limit of 50. The fake implements the same order of filtering and limiting. A Room test proves the SQL behavior, and share summary tests cover both selected and live paths.

The change adds queries only. It does not change the database schema, migrations, or other native event readers.
