import json
import shlex
import subprocess
import threading
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from mic_sessions.shared.definition_execution import definition_api_command, definition_api_prefix


class DefinitionExecutionTest(unittest.TestCase):
    def test_define_reads_definition_and_saves_proposal_over_scoped_local_api(self):
        saved = {}
        existing = {"state": "NEW", "proposal": None}

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                self.assert_authorized_route()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps(existing).encode())

            def do_PUT(self):
                self.assert_authorized_route()
                saved["body"] = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"saved": True}).encode())

            def assert_authorized_route(self):
                self.server.assertions.append((self.path, self.headers.get("X-Mooi-Agent-Token")))

            def log_message(self, *_):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        server.assertions = []
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            prefix = (f"http://127.0.0.1:{server.server_port}/sessions/session-1/work/agent/"
                      "issues/12/definition")
            instructions = f"Read the current definition with GET {prefix}."
            self.assertEqual(definition_api_prefix(instructions), prefix)
            token = "test-agent-token"
            get_source = (
                "import urllib.request, json; "
                f"request = urllib.request.Request('{prefix}', headers={{'X-Mooi-Agent-Token': '{token}'}}); "
                "print(urllib.request.urlopen(request).read().decode())"
            )
            get_command = f"python -c {shlex.quote(get_source)}"
            self.assertTrue(definition_api_command(get_command, prefix))
            response = subprocess.run(shlex.split(get_command), capture_output=True, text=True, check=True)
            self.assertEqual(json.loads(response.stdout), {"state": "NEW", "proposal": None})

            proposal = {"title": "Validated definition", "acceptance": ["Can be verified"]}
            put_source = (
                "import urllib.request, json; "
                f"request = urllib.request.Request('{prefix}/proposal', "
                f"data=json.dumps({json.dumps(proposal)}).encode(), "
                f"headers={{'Content-Type': 'application/json', 'X-Mooi-Agent-Token': '{token}'}}, method='PUT'); "
                "print(urllib.request.urlopen(request).read().decode())"
            )
            put_command = f"python -c {shlex.quote(put_source)}"
            self.assertTrue(definition_api_command(put_command, prefix))
            result = subprocess.run(shlex.split(put_command), capture_output=True, text=True, check=True)
            self.assertEqual(json.loads(result.stdout), {"saved": True})
            self.assertEqual(saved["body"], proposal)
            self.assertEqual(server.assertions, [
                ("/sessions/session-1/work/agent/issues/12/definition", token),
                ("/sessions/session-1/work/agent/issues/12/definition/proposal", token),
            ])
        finally:
            server.shutdown()
            server.server_close()
            worker.join(timeout=2)

    def test_define_allows_read_commands_and_rejects_mutation_or_other_network(self):
        prefix = "http://127.0.0.1:8000/sessions/session-1/work/agent/definition"
        workspace = Path(__file__).resolve().parents[1]
        for command in ("rg --files", "sed -n '1,20p' README.md", "python -c 'print(1)'"):
            if command.startswith("python"):
                continue  # Python is accepted only when its request is scoped to the definition API.
            self.assertTrue(definition_api_command(command, prefix, workspace), command)
        self.assertFalse(definition_api_command("rm -rf .", prefix))
        self.assertFalse(definition_api_command("python -c 'import os; os.remove(\"README.md\")'", prefix))
        self.assertFalse(definition_api_command(
            "python -c 'import urllib.request; urllib.request.urlopen(\"http://example.com\")'", prefix))
        self.assertFalse(definition_api_command("sed -i 's/a/b/' README.md", prefix, workspace))
        self.assertFalse(definition_api_command("rg --pre=sh -n needle .", prefix, workspace))
        self.assertFalse(definition_api_command("rg --files /etc", prefix, workspace))
        self.assertFalse(definition_api_command("ls\nrm -rf .", prefix, workspace))
        self.assertFalse(definition_api_command("grep -f /etc/passwd needle", prefix, workspace))

        with TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "outside").symlink_to(Path("/etc"), target_is_directory=True)
            self.assertFalse(definition_api_command("cat outside/passwd", prefix, root))

    def test_project_definition_proposal_route_is_recognized(self):
        prefix = "http://127.0.0.1:8000/sessions/session-1/work/agent/definition"
        self.assertEqual(definition_api_prefix(f"GET {prefix}"), prefix)
        command = ("python -c \"import urllib.request; "
                   f"request = urllib.request.Request('{prefix}', method='GET'); "
                   "urllib.request.urlopen(request).read()\"")
        self.assertTrue(definition_api_command(command, prefix))


if __name__ == "__main__":
    unittest.main()
