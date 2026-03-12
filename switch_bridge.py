#!/usr/bin/env python3
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

import nxbt


HOST = "0.0.0.0"
PORT = 8000


class NxbtBridge:
    def __init__(self):
        self.lock = threading.RLock()
        self.nx = nxbt.Nxbt()
        self.controller_index = None

    def _resolve_button(self, logical_name: str):
        """
        Resolve a logical button name to the matching nxbt.Buttons enum member.
        Tries a few common aliases so the Java side can stay simple.
        """
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
        # D-pad is handled as a button press through the same resolver.
        return self.press_button(direction, down=down, up=up)

    def hold_direction(self, direction: str, hold: float = 0.25, up: float = 0.05):
        return self.press_dpad(direction, down=hold, up=up)

    def soft_reset(self, down: float = 0.15, up: float = 0.10):
        """
        Preserves your project rule: soft reset stays a shared concept on both backends.
        Mapped here as PLUS + MINUS + A together.
        """
        with self.lock:
            self._require_controller()
            buttons = [
                self._resolve_button("PLUS"),
                self._resolve_button("MINUS"),
                self._resolve_button("A"),
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
    server_version = "SwitchBridge/0.1"

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
                down = float(data.get("down", 0.15))
                up = float(data.get("up", 0.10))
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


def main():
    print(f"switch_bridge listening on http://{HOST}:{PORT}")
    httpd = ThreadingHTTPServer((HOST, PORT), Handler)
    httpd.serve_forever()


if __name__ == "__main__":
    main()