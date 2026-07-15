import subprocess
import re
import time
import sys
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

# Global variable to store the generated Cloudflare URL
TUNNEL_URL = None

class RedirectHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        global TUNNEL_URL
        host = self.headers.get('Host', '')

        # If the request comes from localhost/127.0.0.1, redirect to Cloudflare
        if "localhost" in host or "127.0.0.1" in host:
            if TUNNEL_URL:
                self.send_response(302)
                self.send_header('Location', TUNNEL_URL)
                self.end_headers()
                print(f"[Redirect] Redirected local request to {TUNNEL_URL}")
            else:
                self.send_response(503)
                self.end_headers()
                self.wfile.write(b"Tunnel is starting up, please refresh in a moment...")
        else:
            # If the request comes from the tunnel itself, show a success page
            self.send_response(200)
            self.send_header('Content-type', 'text/html')
            self.end_headers()
            html = f"""
            <html>
                <body style="font-family: Arial, sans-serif; text-align: center; margin-top: 100px;">
                    <h1 style="color: #F6821F;">☁️ Cloudflare Tunnel Active!</h1>
                    <p>Your local server is successfully exposed to the internet.</p>
                    <p><b>Public URL:</b> <a href="{TUNNEL_URL}">{TUNNEL_URL}</a></p>
                </body>
            </html>
            """
            self.wfile.write(html.encode('utf-8'))

    # Suppress normal ping logs to keep terminal clean
    def log_message(self, format, *args):
        return

def start_local_server():
    server_address = ('', 80)
    try:
        httpd = HTTPServer(server_address, RedirectHandler)
        print("🖥️  Local redirect server started on http://localhost:80")
        httpd.serve_forever()
    except PermissionError:
        print("❌ Error: Port 80 requires administrator/root privileges.")
        print("👉 On Linux/macOS, run with: sudo python3 tunnel_redirect.py")
        print("👉 On Windows, open PowerShell as Administrator and run: python tunnel_redirect.py")
        sys.exit(1)
    except Exception as e:
        print(f"❌ Failed to start server: {e}")
        sys.exit(1)

def run_tunnel():
    global TUNNEL_URL
    print("☁️  Starting Cloudflare Tunnel...")
    
    # Run cloudflared and capture output
    process = subprocess.Popen(
        ["cloudflared", "tunnel", "--url", "http://localhost:32766"],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1
    )

    # Search the logs in real-time for the trycloudflare URL
    for line in iter(process.stdout.readline, ''):
        match = re.search(r'https://[a-zA-Z0-9-]+\.trycloudflare\.com', line)
        if match:
            TUNNEL_URL = match.group(0)
            print("\n" + "="*50)
            print(f"🚀 TUNNEL IS LIVE!")
            print(f"🔗 Cloudflare URL: {TUNNEL_URL}")
            print(f"👉 Go to http://localhost to test the redirect!")
            print("="*50 + "\n")
            break

    # Keep tunnel process alive
    process.wait()

if __name__ == "__main__":
    # 1. Start the local Redirect Web Server in a background thread
    server_thread = threading.Thread(target=start_local_server, daemon=True)
    server_thread.start()

    # Give the server a split second to bind to port 80
    time.sleep(1)

    # 2. Start the Cloudflare Tunnel in the main thread
    try:
        run_tunnel()
    except KeyboardInterrupt:
        print("\nStopping tunnel and local server. Goodbye!")
        sys.exit(0)
