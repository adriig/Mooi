"""Narrow command policy for read-only software-factory definition sessions."""

import ast
import re
import shlex
from pathlib import Path
from typing import Any


def definition_command_denial(api_prefix: str | None, workspace: Path | None) -> str:
    """Explain the same execution boundary for every adapter, including missing session context."""
    message = (
        "Define command denied. Use one read-only command with checkout-relative paths and no '..': "
        "rg, sed -n '1,20p', ls, cat, head, tail, pwd or file. "
        "Use python -c with urllib only for GET definition, PUT definition/proposal "
        "or PATCH an existing task of this issue through this session's scoped API. "
        "File writes, shell composition, paths outside the checkout, other HTTP destinations, deployments, "
        "proposal acceptance and GitHub Issues operations are not allowed."
    )
    if workspace is None:
        message += " The checkout path is unavailable; reopen Define before reading repository files."
    if not api_prefix:
        message += " The definition API scope is unavailable; reopen Define before calling the API."
    return message


def definition_api_command(command: Any, api_prefix: str | None, workspace: Path | None = None) -> bool:
    """Allow bounded repository reads and one-line Python calls to scoped definition API routes.

    Define sessions cannot rely on bwrap: some supported hosts disable unprivileged user namespaces.
    This predicate is therefore the execution boundary for shell access when the provider sandbox is
    disabled. Keep it deliberately narrow: no shell composition, interpreters other than the API
    client form below, or commands that can write or execute repository content.
    """
    if not isinstance(command, str) or not command.strip() or "\n" in command or "\r" in command:
        return False
    try:
        lexer = shlex.shlex(command, posix=True, punctuation_chars=";&|<>$`()")
        lexer.whitespace_split = True
        tokens = list(lexer)
    except ValueError:
        return False
    if not tokens or any(token and all(char in ";&|<>$`()" for char in token) for token in tokens):
        return False
    executable = tokens[0]
    if executable in {"sed", "rg", "ls", "pwd", "head", "tail", "cat", "file"}:
        args = tokens[1:]
        if executable == "sed":
            # Only the common print/range form is accepted; sed's r/e/w commands can read
            # outside the checkout, execute programs, or write files despite appearing read-only.
            args = [item for item in args if item not in {"-n", "--quiet", "--silent"}]
            if not args or not re.fullmatch(r"\d*(?:,\d*)?p", args[0]):
                return False
            args = args[1:]
        elif executable == "rg":
            if any(token in {"--pre", "--file", "-f", "-L", "--follow"}
                   or token.startswith(("--pre=", "--file="))
                   for token in args):
                return False
            files_only = "--files" in args or "--files-with-matches" in args
            args = [item for item in args if not item.startswith("-")]
            if not files_only:
                args = args[1:]
        elif executable in {"head", "tail"}:
            filtered = []
            skip_value = False
            for item in args:
                if skip_value:
                    skip_value = False
                elif item in {"-n", "--lines", "-c", "--bytes"}:
                    skip_value = True
                elif not item.startswith("-"):
                    filtered.append(item)
            args = filtered
        if any(item.startswith("/") or ".." in Path(item).parts for item in args):
            return False
        if workspace is None:
            return False
        root = workspace.resolve()
        return not any(item != "-" and not (root / item).resolve().is_relative_to(root) for item in args)
    if executable not in ("python", "python3") or not api_prefix:
        return False
    if len(tokens) != 3 or tokens[1] != "-c":
        return False
    source = tokens[2]
    try:
        tree = ast.parse(source, mode="exec")
    except SyntaxError:
        return False

    allowed_nodes = {
        ast.Module, ast.Import, ast.ImportFrom, ast.alias, ast.Assign, ast.Name, ast.Load, ast.Store,
        ast.Call, ast.Attribute, ast.Constant, ast.keyword, ast.Dict, ast.List, ast.Tuple, ast.Expr,
        ast.With, ast.withitem, ast.Subscript, ast.BinOp, ast.Add,
    }
    if any(type(node) not in allowed_nodes for node in ast.walk(tree)):
        return False
    allowed_attributes = {"request", "Request", "urlopen", "dumps", "loads", "encode", "read", "decode"}
    if any(isinstance(node, ast.Attribute) and node.attr not in allowed_attributes for node in ast.walk(tree)):
        return False
    for node in ast.walk(tree):
        if isinstance(node, ast.Import) and any(alias.name not in ("urllib.request", "json")
                                                or alias.asname for alias in node.names):
            return False
        if isinstance(node, ast.ImportFrom) and (
            node.module != "urllib.request" or any(alias.name not in ("Request", "urlopen") for alias in node.names)
        ):
            return False

    def dotted(node: ast.expr) -> str:
        if isinstance(node, ast.Name):
            return node.id
        if isinstance(node, ast.Attribute):
            parent = dotted(node.value)
            return f"{parent}.{node.attr}" if parent else ""
        return ""

    def literal(node: ast.expr | None) -> Any:
        return node.value if isinstance(node, ast.Constant) else None

    requests: list[tuple[ast.Call, str, str]] = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call):
            continue
        function = dotted(node.func)
        if function in ("urllib.request.Request", "Request"):
            url = literal(node.args[0]) if node.args else None
            method = next((literal(item.value) for item in node.keywords if item.arg == "method"), "GET")
            if not isinstance(url, str) or not isinstance(method, str):
                return False
            issue_prefix = api_prefix.removesuffix("/definition")
            task_route = (re.fullmatch(re.escape(f"{issue_prefix}/tasks/") + r"[0-9a-fA-F-]{36}", url)
                          if "/issues/" in api_prefix else None)
            allowed = ((url == api_prefix and method == "GET")
                       or (url == f"{api_prefix}/proposal" and method == "PUT")
                       or (task_route is not None and method == "PATCH"))
            if not allowed:
                return False
            requests.append((node, url, method))
        elif function not in (
            "urllib.request.urlopen", "urlopen", "json.dumps", "json.loads", "print", "encode", "read", "decode",
        ) and not (isinstance(node.func, ast.Attribute) and node.func.attr in {"encode", "read", "decode"}):
            return False
    if len(requests) != 1:
        return False
    request_call = requests[0][0]
    allowed_names = {target.id for statement in ast.walk(tree)
                     if isinstance(statement, ast.Assign) and statement.value is request_call
                     for target in statement.targets if isinstance(target, ast.Name)}
    open_calls = [node for node in ast.walk(tree)
                  if isinstance(node, ast.Call) and dotted(node.func) in ("urllib.request.urlopen", "urlopen")]
    if not open_calls:
        return False
    for node in open_calls:
        if not node.args or not (
            (isinstance(node.args[0], ast.Name) and node.args[0].id in allowed_names)
            or node.args[0] is request_call
        ):
            return False
    return True


def definition_api_prefix(instructions: str) -> str | None:
    match = re.search(
        r"GET (http://127\.0\.0\.1:\d+/sessions/[^/\s]+/work/agent/(?:issues/\d+/)?definition)",
        instructions,
    )
    return match.group(1) if match else None
