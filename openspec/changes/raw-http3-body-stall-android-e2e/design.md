# Design

Use the fixture HTTPS listener and certificate from the existing RAW transfer tests. Serve the verified two-byte object over TCP. On the same UDP port, send HTTP/3 response headers and one body byte and hold the stream open until the comparison reports a timeout.

Run the manual profile through the Android scan controller. Load the completed session from history. Check the verified TCP result, HTTP/3 body timeout, the body difference stage, and both byte counts.
