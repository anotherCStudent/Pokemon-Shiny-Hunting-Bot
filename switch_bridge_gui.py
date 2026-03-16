#!/usr/bin/env python3
import json
import threading
import tkinter as tk
from tkinter import ttk
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

import nxbt


HOST = "0.0.0.0"
PORT = 8000


class NxbtBridge:
    def __init__(self):
        self.lock = threading.RLock()
        self.nx = nxbt.Nxbt()
        self.controller_index = None

    def _resolve_button(self, logical_name: str):
        if logical_name is None:
            raise ValueError("button name is required")

        name = logical_name.strip().upper()

        candidates = {
            "A": ["A"],
            "B": ["B"],
            "X": ["X"],
            "Y": ["Y"],
            "L": ["L"],
            "R": ["R"],
            "ZL": ["ZL"],
            "ZR": ["ZR"],
            "PLUS": ["PLUS", "START"],
            "START": ["PLUS", "START"],
            "MINUS": ["MINUS", "SELECT"],
            "SELECT": ["MINUS", "SELECT"],
            "HOME": ["HOME"],
            "CAPTURE": ["CAPTURE"],
            "UP": ["DPAD_UP", "UP"],
            "DOWN": ["DPAD_DOWN", "DOWN"],
            "LEFT": ["DPAD_LEFT", "LEFT"],
            "RIGHT": ["DPAD_RIGHT", "RIGHT"],
        }.get(name, [name])

        for candidate in candidates:
            if hasattr(nxbt.Buttons, candidate):
                return getattr(nxbt.Buttons, candidate)

        available = [x for x in dir(nxbt.Buttons) if not x.startswith("_")]
        raise ValueError(
            f"Unsupported button '{logical_name}'. "
            f"Available nxbt.Buttons members: {available}"
        )

    def _require_controller(self):
        if self.controller_index is None:
            raise RuntimeError("No controller is connected. Call /connect first.")

    def connect(self, reconnect: bool = True):
        with self.lock:
            if self.controller_index is not None:
                return {
                    "ok": True,
                    "connected": True,
                    "controller_index": self.controller_index,
                    "message": "Controller already active"
                }

            kwargs = {}
            if reconnect:
                try:
                    known_switches = self.nx.get_switch_addresses()
                    if known_switches:
                        kwargs["reconnect_address"] = known_switches
                except Exception:
                    pass

            self.controller_index = self.nx.create_controller(
                nxbt.PRO_CONTROLLER,
                **kwargs
            )

        self.nx.wait_for_connection(self.controller_index)

        return {
            "ok": True,
            "connected": True,
            "controller_index": self.controller_index,
            "message": "Connected"
        }

    def disconnect(self):
        with self.lock:
            if self.controller_index is None:
                return {"ok": True, "connected": False, "message": "No controller active"}

            idx = self.controller_index
            self.nx.remove_controller(idx)
            self.controller_index = None

            return {"ok": True, "connected": False, "message": "Controller removed"}

    def status(self):
        with self.lock:
            if self.controller_index is None:
                return {
                    "ok": True,
                    "connected": False,
                    "controller_index": None,
                    "state": None
                }

            idx = self.controller_index
            state = self.nx.state.get(idx, {})
            return {
                "ok": True,
                "connected": True,
                "controller_index": idx,
                "state": state
            }

    def press_button(self, button: str, down: float = 0.08, up: float = 0.08):
        with self.lock:
            self._require_controller()
            btn = self._resolve_button(button)
            self.nx.press_buttons(self.controller_index, [btn], down=down, up=up)

        return {
            "ok": True,
            "action": "press_button",
            "button": button,
            "down": down,
            "up": up
        }

    def press_dpad(self, direction: str, down: float = 0.08, up: float = 0.08):
        return self.press_button(direction, down=down, up=up)

    def hold_direction(self, direction: str, hold: float = 0.25, up: float = 0.05):
        return self.press_dpad(direction, down=hold, up=up)

    def soft_reset(self, down: float = 1.00, up: float = 0.20):
        with self.lock:
            self._require_controller()
            buttons = [
                self._resolve_button("PLUS"),
                self._resolve_button("MINUS"),
                self._resolve_button("A"),
                self._resolve_button("B")
            ]
            self.nx.press_buttons(self.controller_index, buttons, down=down, up=up)

        return {
            "ok": True,
            "action": "soft_reset",
            "down": down,
            "up": up
        }

    def run_macro(self, macro: str, block: bool = True):
        with self.lock:
            self._require_controller()
            result = self.nx.macro(self.controller_index, macro, block=block)

        return {
            "ok": True,
            "action": "macro",
            "block": block,
            "result": result
        }

    def clear_macros(self):
        with self.lock:
            self._require_controller()
            self.nx.clear_macros(self.controller_index)

        return {"ok": True, "action": "clear_macros"}


bridge = NxbtBridge()


class Handler(BaseHTTPRequestHandler):
    server_version = "SwitchBridge/0.2"

    def _send_json(self, code: int, payload: dict):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json_body(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0:
            return {}
        raw = self.rfile.read(length)
        if not raw:
            return {}
        return json.loads(raw.decode("utf-8"))

    def log_message(self, fmt, *args):
        print(f"[switch_bridge] {self.address_string()} - {fmt % args}")

    def do_GET(self):
        parsed = urlparse(self.path)

        try:
            if parsed.path == "/status":
                self._send_json(200, bridge.status())
                return

            if parsed.path == "/":
                self._send_json(200, {
                    "ok": True,
                    "service": "switch_bridge",
                    "endpoints": [
                        "GET /status",
                        "POST /connect",
                        "POST /disconnect",
                        "POST /button",
                        "POST /dpad",
                        "POST /hold",
                        "POST /soft-reset",
                        "POST /macro",
                        "POST /clear-macros",
                    ]
                })
                return

            self._send_json(404, {"ok": False, "error": "Not found"})
        except Exception as e:
            self._send_json(500, {"ok": False, "error": str(e)})

    def do_POST(self):
        parsed = urlparse(self.path)

        try:
            data = self._read_json_body()

            if parsed.path == "/connect":
                reconnect = bool(data.get("reconnect", True))
                self._send_json(200, bridge.connect(reconnect=reconnect))
                return

            if parsed.path == "/disconnect":
                self._send_json(200, bridge.disconnect())
                return

            if parsed.path == "/button":
                button = data.get("button")
                down = float(data.get("down", 0.08))
                up = float(data.get("up", 0.08))
                self._send_json(200, bridge.press_button(button, down=down, up=up))
                return

            if parsed.path == "/dpad":
                direction = data.get("direction")
                down = float(data.get("down", 0.08))
                up = float(data.get("up", 0.08))
                self._send_json(200, bridge.press_dpad(direction, down=down, up=up))
                return

            if parsed.path == "/hold":
                direction = data.get("direction")
                hold = float(data.get("hold", 0.25))
                up = float(data.get("up", 0.05))
                self._send_json(200, bridge.hold_direction(direction, hold=hold, up=up))
                return

            if parsed.path == "/soft-reset":
                down = float(data.get("down", 1.00))
                up = float(data.get("up", 0.20))
                self._send_json(200, bridge.soft_reset(down=down, up=up))
                return

            if parsed.path == "/macro":
                macro = data.get("macro", "")
                block = bool(data.get("block", True))
                self._send_json(200, bridge.run_macro(macro, block=block))
                return

            if parsed.path == "/clear-macros":
                self._send_json(200, bridge.clear_macros())
                return

            self._send_json(404, {"ok": False, "error": "Not found"})
        except Exception as e:
            self._send_json(500, {"ok": False, "error": str(e)})


class BridgeServer:
    def __init__(self, host: str, port: int):
        self.host = host
        self.port = port
        self.httpd = None
        self.thread = None
        self.running = False
        self.lock = threading.Lock()

    def start(self):
        with self.lock:
            if self.running:
                return {"ok": True, "running": True, "message": "Bridge already running"}

            self.httpd = ThreadingHTTPServer((self.host, self.port), Handler)
            self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
            self.thread.start()
            self.running = True

            print(f"switch_bridge listening on http://{self.host}:{self.port}")
            return {"ok": True, "running": True, "message": f"Bridge running on {self.host}:{self.port}"}

    def stop(self):
        with self.lock:
            if not self.running or self.httpd is None:
                return {"ok": True, "running": False, "message": "Bridge not running"}

            self.httpd.shutdown()
            self.httpd.server_close()
            self.httpd = None
            self.thread = None
            self.running = False
            return {"ok": True, "running": False, "message": "Bridge stopped"}


server = BridgeServer(HOST, PORT)


class SwitchBridgeGui:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.root.title("Switch Bridge Control")
        self.root.geometry("470x260")
        self.root.resizable(False, False)

        self.status_text = tk.StringVar(value="Bridge not started")
        self.bridge_check = tk.StringVar(value="✗ Script not running")
        self.controller_check = tk.StringVar(value="✗ Controller not connected")

        self.build_ui()
        self.refresh_status_labels()

    def build_ui(self):
        outer = ttk.Frame(self.root, padding=16)
        outer.pack(fill="both", expand=True)

        title = ttk.Label(outer, text="NXBT Switch Bridge", font=("TkDefaultFont", 13, "bold"))
        title.pack(anchor="w", pady=(0, 10))

        desc = ttk.Label(
            outer,
            text="1) Start Bridge\n2) Put Switch on Controllers → Change Grip/Order\n3) Click Connect Controller",
            justify="left"
        )
        desc.pack(anchor="w", pady=(0, 12))

        button_row = ttk.Frame(outer)
        button_row.pack(fill="x", pady=(0, 12))

        self.start_btn = ttk.Button(button_row, text="Start Bridge", command=self.on_start_bridge)
        self.start_btn.pack(side="left", padx=(0, 8))

        self.connect_btn = ttk.Button(button_row, text="Connect Controller", command=self.on_connect)
        self.connect_btn.pack(side="left", padx=(0, 8))

        self.disconnect_btn = ttk.Button(button_row, text="Disconnect Controller", command=self.on_disconnect)
        self.disconnect_btn.pack(side="left")

        status_frame = ttk.LabelFrame(outer, text="Status", padding=12)
        status_frame.pack(fill="both", expand=True)

        ttk.Label(status_frame, textvariable=self.bridge_check).pack(anchor="w", pady=(0, 6))
        ttk.Label(status_frame, textvariable=self.controller_check).pack(anchor="w", pady=(0, 6))
        ttk.Label(status_frame, textvariable=self.status_text, wraplength=400, justify="left").pack(anchor="w")

        self.root.protocol("WM_DELETE_WINDOW", self.on_close)

    def set_status(self, message: str):
        self.status_text.set(message)
        self.refresh_status_labels()

    def refresh_status_labels(self):
        if server.running:
            self.bridge_check.set(f"✓ Script running on http://{HOST}:{PORT}")
        else:
            self.bridge_check.set("✗ Script not running")

        try:
            s = bridge.status()
            if s.get("connected"):
                idx = s.get("controller_index")
                self.controller_check.set(f"✓ Controller connected (index {idx})")
            else:
                self.controller_check.set("✗ Controller not connected")
        except Exception as e:
            self.controller_check.set(f"✗ Controller status unknown: {e}")

    def _run_background(self, fn, success_prefix: str):
        def worker():
            try:
                result = fn()
                message = result.get("message", success_prefix)
                self.root.after(0, lambda: self.set_status(message))
            except Exception as e:
                self.root.after(0, lambda: self.set_status(f"Error: {e}"))
            finally:
                self.root.after(0, self.refresh_status_labels)

        threading.Thread(target=worker, daemon=True).start()

    def on_start_bridge(self):
        self._run_background(server.start, "Bridge started")

    def on_connect(self):
        self._run_background(lambda: bridge.connect(reconnect=True), "Controller connected")

    def on_disconnect(self):
        self._run_background(bridge.disconnect, "Controller disconnected")

    def on_close(self):
        try:
            bridge.disconnect()
        except Exception:
            pass
        try:
            server.stop()
        except Exception:
            pass
        self.root.destroy()


def main():
    root = tk.Tk()
    app = SwitchBridgeGui(root)
    root.mainloop()


if __name__ == "__main__":
    main()
