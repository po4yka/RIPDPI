# Design

Use the first configured control on an in-path strategy scan. Use the existing route and bounded HTTPS reader. Keep the last confirmed stage even when the body times out. The reader requires HTTP 200, explicit framing, and a complete nonempty identity body that passes the block-page check. The report carries only the stage. Kotlin still requires the owned route lease at scan completion before it can use the stage.

Reports without transfer evidence can still show the measured candidate outcome. They cannot confirm a working active path.
