#!/usr/bin/env python3
"""Tiny HTTP bridge: TV app -> Hermes agent (full tools/memory + image/video gen).
Run on the mini PC:  python3 hermes_bridge.py
TV app points at http://<mini-pc-ip>:8765/v1 with any bearer token.
"""
import json, re, subprocess, http.server, socketserver

PORT = 8765

class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != "/v1/chat/completions":
            self.send_error(404); return
        n = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(n))
        user_msg = body["messages"][-1]["content"]

        prompt = (
            "You are Hermes on the user's TV. Keep replies to 1-3 sentences. "
            "If the user asks to draw/image/picture, generate an image and end your reply with "
            "'MEDIA_URL: <url>' on its own line. If they ask for a video/clip, generate a video "
            "and end with 'MEDIA_URL: <url>'.\n\nUser: " + user_msg
        )

        try:
            out = subprocess.run(
                ["hermes", "-z", prompt, "--provider", "nous"],
                capture_output=True, text=True, timeout=300
            )
            reply = (out.stdout or out.stderr or "No response.").strip()
        except Exception as e:
            reply = f"Bridge error: {e}"

        media_url = None
        m = re.search(r"MEDIA_URL:\s*(\S+)", reply)
        if m:
            media_url = m.group(1).rstrip(".,)")
        clean = re.sub(r"MEDIA_URL:\s*\S+", "", reply).strip() or "Done."

        media_type = None
        if media_url:
            media_type = "video" if (".mp4" in media_url or "video" in media_url.lower()) else "image"

        resp = {
            "choices": [{"message": {"role": "assistant", "content": clean}}],
            "media_url": media_url,
            "media_type": media_type,
        }
        data = json.dumps(resp).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *a):
        pass

class ReusableTCPServer(socketserver.TCPServer):
    allow_reuse_address = True

with ReusableTCPServer(("0.0.0.0", PORT), Handler) as httpd:
    print(f"Hermes bridge listening on :{PORT}")
    httpd.serve_forever()