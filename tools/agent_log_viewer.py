#!/usr/bin/env python3
"""
OFBiz Agent Log Viewer
Version: 0.1.0

Convert OFBiz AgentTrace log output into human-readable structured evidence.

Primary outputs:

    *.yaml
        Complete structured representation of the selected agent run.

    *.md
        Human-oriented walkthrough suitable for reading in VS Code.

    *.raw.txt
        Exact original AgentTrace log lines for audit and comparison.

The parser deliberately preserves the lexical representation of JSON numbers
where possible. This is important for the OFBiz Agent semantic boundary because
values such as:

    2E+1

must remain distinguishable from canonical model-facing strings such as:

    "20.00"

No external Python packages are required.

Typical usage:

    python tools/agent_log_viewer.py runtime/logs/agent.log

Select a particular run:

    python tools/agent_log_viewer.py runtime/logs/agent.log \
        --run-id 0f70d26b-7af1-4b6a-9ab0-aec97864aaf3

List the runs present in the log:

    python tools/agent_log_viewer.py runtime/logs/agent.log \
        --list-runs

Write all runs:

    python tools/agent_log_viewer.py runtime/logs/agent.log \
        --all-runs
"""

from __future__ import annotations

import argparse
import json
import logging
import re
import sys

from dataclasses import dataclass, field
from datetime import datetime
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any, Iterable


VERSION = "0.1.0"

LOGGER = logging.getLogger("agent_log_viewer")


LOG_LINE_SEPARATOR = "|"

CONTEXT_PATTERN = re.compile(
    r"\[(?P<key>[A-Z][A-Z0-9-]*):(?P<value>[^\]]*)\]"
)

ATTRIBUTE_PATTERN = re.compile(
    r"""
    (?P<key>[A-Za-z][A-Za-z0-9_]*)
    =
    (?:
        "
        (?P<quoted>
            (?:
                \\.
                |
                [^"\\]
            )*
        )
        "
        |
        (?P<bare>[^\s]+)
    )
    """,
    re.VERBOSE,
)

PLAIN_YAML_KEY_PATTERN = re.compile(
    r"^[A-Za-z_][A-Za-z0-9_.-]*$"
)

INTEGER_PATTERN = re.compile(
    r"^-?[0-9]+$"
)

NUMBER_PATTERN = re.compile(
    r"""
    ^-?
    (?:
        [0-9]+
        (?:
            \.[0-9]+
        )?
        |
        \.[0-9]+
    )
    (?:
        [eE][+-]?[0-9]+
    )?
    $
    """,
    re.VERBOSE,
)


@dataclass
class AgentEvent:
    """One parsed AgentTrace event."""

    timestamp: str
    timestamp_value: datetime | None
    thread: str
    logger: str
    level: str

    request_id: str | None
    run_id: str
    agent: str | None
    service: str | None
    test: str | None

    event: str
    transaction: str | None

    attributes: dict[str, Any] = field(default_factory=dict)

    payload_text: str | None = None
    payload: Any = None

    raw_line: str = ""


@dataclass
class AgentRun:
    """All AgentTrace events belonging to one run identifier."""

    run_id: str
    events: list[AgentEvent] = field(default_factory=list)

    def add(self, event: AgentEvent) -> None:
        self.events.append(event)

    @property
    def first_event(self) -> AgentEvent | None:
        if not self.events:
            return None
        return self.events[0]

    @property
    def last_event(self) -> AgentEvent | None:
        if not self.events:
            return None
        return self.events[-1]

    @property
    def request_id(self) -> str | None:
        for event in self.events:
            if event.request_id:
                return event.request_id
        return None

    @property
    def agent(self) -> str | None:
        for event in self.events:
            if event.agent:
                return event.agent
        return None

    @property
    def service(self) -> str | None:
        for event in self.events:
            if event.service:
                return event.service
        return None

    @property
    def first_timestamp(self) -> str | None:
        event = self.first_event
        if event is None:
            return None
        return event.timestamp

    @property
    def last_timestamp(self) -> str | None:
        event = self.last_event
        if event is None:
            return None
        return event.timestamp

    @property
    def status(self) -> str:
        event_names = [event.event for event in self.events]

        if "AGENT_FAILURE" in event_names:
            return "FAILURE"

        if "AGENT_SUCCESS" in event_names:
            return "SUCCESS"

        return "INCOMPLETE"

    @property
    def duration_ms(self) -> int | Decimal | None:
        for event in reversed(self.events):
            if event.event not in {"AGENT_SUCCESS", "AGENT_FAILURE"}:
                continue

            value = event.attributes.get("durationMs")

            if isinstance(value, (int, Decimal)):
                return value

        return None


def configure_logging(verbose: bool) -> None:
    """Configure utility diagnostic logging."""

    level = logging.DEBUG if verbose else logging.INFO

    logging.basicConfig(
        level=level,
        format="%(levelname)s %(name)s: %(message)s",
    )


def parse_timestamp(value: str) -> datetime | None:
    """
    Parse an OFBiz log timestamp.

    OFBiz normally emits:

        2026-09-29 16:32:34,006
    """

    try:
        return datetime.strptime(
            value,
            "%Y-%m-%d %H:%M:%S,%f",
        )
    except ValueError:
        return None


def decode_quoted_attribute(value: str) -> str:
    """
    Decode the contents of a double-quoted log attribute.

    AgentTrace escapes payload values in a JSON-compatible form, so wrapping
    the captured contents in quotes and letting the JSON decoder process the
    escape sequences gives us the exact logical string.
    """

    encoded = f'"{value}"'

    try:
        decoded = json.loads(encoded)
    except json.JSONDecodeError:
        LOGGER.debug(
            "Could not JSON-decode quoted attribute; preserving raw value"
        )
        return value

    if not isinstance(decoded, str):
        return value

    return decoded


def parse_bare_value(value: str) -> Any:
    """Convert simple unquoted attribute values to useful Python types."""

    if value == "true":
        return True

    if value == "false":
        return False

    if value == "null":
        return None

    if INTEGER_PATTERN.fullmatch(value):
        try:
            return int(value)
        except ValueError:
            return value

    if NUMBER_PATTERN.fullmatch(value):
        try:
            return Decimal(value)
        except InvalidOperation:
            return value

    return value


def parse_attributes(body: str) -> dict[str, Any]:
    """Parse key=value attributes from an AgentTrace body."""

    attributes: dict[str, Any] = {}

    for match in ATTRIBUTE_PATTERN.finditer(body):
        key = match.group("key")
        quoted = match.group("quoted")
        bare = match.group("bare")

        if quoted is not None:
            attributes[key] = decode_quoted_attribute(quoted)
        elif bare is not None:
            attributes[key] = parse_bare_value(bare)

    return attributes


def parse_json_payload(payload_text: str) -> Any:
    """
    Parse a JSON payload while preserving numeric lexical information.

    Decimal is used for non-integral JSON numbers. For example:

        2E+1

    becomes:

        Decimal("2E+1")

    instead of a float 20.0.

    This allows the viewer to show the distinction between an OFBiz raw
    monetary representation and the canonical model-facing string "20.00".
    """

    stripped = payload_text.strip()

    if not stripped:
        return payload_text

    if stripped[0] not in "[{":
        return payload_text

    try:
        return json.loads(
            stripped,
            parse_float=Decimal,
            parse_int=int,
        )
    except json.JSONDecodeError:
        return payload_text


def parse_log_line(line: str) -> AgentEvent | None:
    """Parse one AgentTrace line. Non-AgentTrace lines are ignored."""

    if "[RUN:" not in line:
        return None

    if "AgentTrace" not in line:
        return None

    parts = line.rstrip("\n").split(
        LOG_LINE_SEPARATOR,
        4,
    )

    if len(parts) != 5:
        return None

    timestamp = parts[0].strip()
    thread = parts[1].strip()
    logger_name = parts[2].strip()
    level = parts[3].strip()
    body = parts[4].strip()

    contexts: dict[str, str] = {}

    for match in CONTEXT_PATTERN.finditer(body):
        contexts[match.group("key")] = match.group("value")

    run_id = contexts.get("RUN")

    if not run_id:
        return None

    attributes = parse_attributes(body)

    event_name = attributes.pop(
        "event",
        None,
    )

    if not isinstance(event_name, str):
        return None

    transaction = attributes.pop(
        "tx",
        None,
    )

    payload_marker = object()

    payload_value = attributes.pop(
        "payload",
        payload_marker,
    )

    payload_text: str | None = None
    payload: Any = None

    if payload_value is not payload_marker:
        if isinstance(payload_value, str):
            payload_text = payload_value
            payload = parse_json_payload(payload_value)
        else:
            payload = payload_value
            payload_text = str(payload_value)

    service = (
        contexts.get("SERVICE")
        or contexts.get("AGENT-SVC")
    )

    return AgentEvent(
        timestamp=timestamp,
        timestamp_value=parse_timestamp(timestamp),
        thread=thread,
        logger=logger_name,
        level=level,
        request_id=contexts.get("REQ"),
        run_id=run_id,
        agent=contexts.get("AGENT"),
        service=service,
        test=contexts.get("TEST"),
        event=event_name,
        transaction=(
            str(transaction)
            if transaction is not None
            else None
        ),
        attributes=attributes,
        payload_text=payload_text,
        payload=payload,
        raw_line=line.rstrip("\n"),
    )


def read_agent_runs(log_path: Path) -> dict[str, AgentRun]:
    """Read an agent log and group AgentTrace events by RUN identifier."""

    runs: dict[str, AgentRun] = {}

    with log_path.open(
        "r",
        encoding="utf-8",
        errors="replace",
    ) as handle:
        for line_number, line in enumerate(
            handle,
            start=1,
        ):
            event = parse_log_line(line)

            if event is None:
                continue

            run = runs.setdefault(
                event.run_id,
                AgentRun(
                    run_id=event.run_id,
                ),
            )

            run.add(event)

            LOGGER.debug(
                "Parsed line %d: run=%s event=%s",
                line_number,
                event.run_id,
                event.event,
            )

    for run in runs.values():
        run.events.sort(
            key=event_sort_key,
        )

    return runs


def event_sort_key(event: AgentEvent) -> tuple[Any, str]:
    """Stable event ordering key."""

    timestamp = event.timestamp_value

    if timestamp is None:
        return (
            datetime.min,
            event.timestamp,
        )

    return (
        timestamp,
        event.timestamp,
    )


def run_sort_key(run: AgentRun) -> tuple[Any, str]:
    """Sort runs by their final event timestamp."""

    event = run.last_event

    if event is None:
        return (
            datetime.min,
            run.run_id,
        )

    timestamp = event.timestamp_value

    if timestamp is None:
        return (
            datetime.min,
            run.run_id,
        )

    return (
        timestamp,
        run.run_id,
    )


def select_runs(
    runs: dict[str, AgentRun],
    run_id: str | None,
    request_id: str | None,
    all_runs: bool,
) -> list[AgentRun]:
    """Select the run or runs requested by the CLI."""

    available = list(runs.values())

    if not available:
        return []

    if run_id:
        run = runs.get(run_id)

        if run is None:
            return []

        return [run]

    if request_id:
        matching = [
            run
            for run in available
            if run.request_id == request_id
        ]

        return sorted(
            matching,
            key=run_sort_key,
        )

    ordered = sorted(
        available,
        key=run_sort_key,
    )

    if all_runs:
        return ordered

    return [
        ordered[-1]
    ]


def yaml_key(value: str) -> str:
    """Render a mapping key safely."""

    if PLAIN_YAML_KEY_PATTERN.fullmatch(value):
        return value

    return json.dumps(
        value,
        ensure_ascii=False,
    )


def yaml_scalar(value: Any) -> str:
    """Render a scalar YAML value."""

    if value is None:
        return "null"

    if value is True:
        return "true"

    if value is False:
        return "false"

    if isinstance(value, int):
        return str(value)

    if isinstance(value, Decimal):
        return str(value)

    if isinstance(value, float):
        return repr(value)

    if isinstance(value, str):
        return json.dumps(
            value,
            ensure_ascii=False,
        )

    return json.dumps(
        str(value),
        ensure_ascii=False,
    )


def yaml_dump(
    value: Any,
    indent: int = 0,
) -> str:
    """
    Produce readable YAML without requiring PyYAML.

    The supported structures are deliberately limited to the built-in
    collection/scalar types generated by this utility.
    """

    prefix = " " * indent

    if isinstance(value, dict):
        if not value:
            return "{}"

        lines: list[str] = []

        for key, child in value.items():
            rendered_key = yaml_key(
                str(key)
            )

            if isinstance(child, str) and "\n" in child:
                lines.append(
                    f"{prefix}{rendered_key}: |"
                )

                for text_line in child.splitlines():
                    lines.append(
                        " " * (indent + 2)
                        + text_line
                    )

                if child.endswith("\n"):
                    lines.append(
                        " " * (indent + 2)
                    )

                continue

            if isinstance(child, dict):
                if child:
                    lines.append(
                        f"{prefix}{rendered_key}:"
                    )
                    lines.append(
                        yaml_dump(
                            child,
                            indent + 2,
                        )
                    )
                else:
                    lines.append(
                        f"{prefix}{rendered_key}: {{}}"
                    )

                continue

            if isinstance(child, list):
                if child:
                    lines.append(
                        f"{prefix}{rendered_key}:"
                    )
                    lines.append(
                        yaml_dump(
                            child,
                            indent + 2,
                        )
                    )
                else:
                    lines.append(
                        f"{prefix}{rendered_key}: []"
                    )

                continue

            lines.append(
                f"{prefix}{rendered_key}: "
                f"{yaml_scalar(child)}"
            )

        return "\n".join(lines)

    if isinstance(value, list):
        if not value:
            return "[]"

        lines = []

        for child in value:
            if isinstance(child, str) and "\n" in child:
                lines.append(
                    f"{prefix}- |"
                )

                for text_line in child.splitlines():
                    lines.append(
                        " " * (indent + 2)
                        + text_line
                    )

                continue

            if isinstance(child, dict):
                if not child:
                    lines.append(
                        f"{prefix}- {{}}"
                    )
                    continue

                first = True

                for key, item in child.items():
                    rendered_key = yaml_key(
                        str(key)
                    )

                    item_prefix = (
                        f"{prefix}- "
                        if first
                        else " " * (indent + 2)
                    )

                    first = False

                    if isinstance(item, str) and "\n" in item:
                        lines.append(
                            f"{item_prefix}{rendered_key}: |"
                        )

                        for text_line in item.splitlines():
                            lines.append(
                                " " * (indent + 4)
                                + text_line
                            )

                    elif isinstance(item, dict):
                        if item:
                            lines.append(
                                f"{item_prefix}{rendered_key}:"
                            )
                            lines.append(
                                yaml_dump(
                                    item,
                                    indent + 4,
                                )
                            )
                        else:
                            lines.append(
                                f"{item_prefix}{rendered_key}: {{}}"
                            )

                    elif isinstance(item, list):
                        if item:
                            lines.append(
                                f"{item_prefix}{rendered_key}:"
                            )
                            lines.append(
                                yaml_dump(
                                    item,
                                    indent + 4,
                                )
                            )
                        else:
                            lines.append(
                                f"{item_prefix}{rendered_key}: []"
                            )

                    else:
                        lines.append(
                            f"{item_prefix}{rendered_key}: "
                            f"{yaml_scalar(item)}"
                        )

                continue

            if isinstance(child, list):
                lines.append(
                    f"{prefix}-"
                )
                lines.append(
                    yaml_dump(
                        child,
                        indent + 2,
                    )
                )
                continue

            lines.append(
                f"{prefix}- {yaml_scalar(child)}"
            )

        return "\n".join(lines)

    if isinstance(value, str) and "\n" in value:
        lines = [
            f"{prefix}|"
        ]

        for text_line in value.splitlines():
            lines.append(
                " " * (indent + 2)
                + text_line
            )

        return "\n".join(lines)

    return (
        prefix
        + yaml_scalar(value)
    )


def display_value(value: Any) -> str:
    """Compact human-readable value for Markdown tables."""

    if value is None:
        return "`null`"

    if isinstance(value, str):
        text = value.replace(
            "\n",
            "\\n",
        )

        if len(text) > 120:
            text = text[:117] + "..."

        return f"`{text}`"

    if isinstance(value, Decimal):
        return f"`{value}`"

    if isinstance(value, (int, float, bool)):
        return f"`{value}`"

    text = yaml_dump(value)

    text = text.replace(
        "\n",
        " ",
    )

    if len(text) > 120:
        text = text[:117] + "..."

    return f"`{text}`"


def compare_values(
    raw_value: Any,
    model_value: Any,
    path: str = "",
) -> dict[str, list[dict[str, Any]]]:
    """Recursively compare raw and model-facing tool payloads."""

    result: dict[str, list[dict[str, Any]]] = {
        "removed": [],
        "added": [],
        "transformed": [],
    }

    if isinstance(raw_value, dict) and isinstance(model_value, dict):
        raw_keys = set(raw_value)
        model_keys = set(model_value)

        for key in sorted(
            raw_keys - model_keys
        ):
            child_path = join_path(
                path,
                key,
            )

            result["removed"].append(
                {
                    "path": child_path,
                    "value": raw_value[key],
                }
            )

        for key in sorted(
            model_keys - raw_keys
        ):
            child_path = join_path(
                path,
                key,
            )

            result["added"].append(
                {
                    "path": child_path,
                    "value": model_value[key],
                }
            )

        for key in sorted(
            raw_keys & model_keys
        ):
            child_path = join_path(
                path,
                key,
            )

            merge_comparison(
                result,
                compare_values(
                    raw_value[key],
                    model_value[key],
                    child_path,
                ),
            )

        return result

    if isinstance(raw_value, list) and isinstance(model_value, list):
        common_length = min(
            len(raw_value),
            len(model_value),
        )

        for index in range(common_length):
            child_path = (
                f"{path}[{index}]"
                if path
                else f"[{index}]"
            )

            merge_comparison(
                result,
                compare_values(
                    raw_value[index],
                    model_value[index],
                    child_path,
                ),
            )

        for index in range(
            common_length,
            len(raw_value),
        ):
            child_path = (
                f"{path}[{index}]"
                if path
                else f"[{index}]"
            )

            result["removed"].append(
                {
                    "path": child_path,
                    "value": raw_value[index],
                }
            )

        for index in range(
            common_length,
            len(model_value),
        ):
            child_path = (
                f"{path}[{index}]"
                if path
                else f"[{index}]"
            )

            result["added"].append(
                {
                    "path": child_path,
                    "value": model_value[index],
                }
            )

        return result

    if (
        type(raw_value) is not type(model_value)
        or raw_value != model_value
    ):
        result["transformed"].append(
            {
                "path": path or "$",
                "from": raw_value,
                "to": model_value,
            }
        )

    return result


def merge_comparison(
    target: dict[str, list[dict[str, Any]]],
    source: dict[str, list[dict[str, Any]]],
) -> None:
    """Merge one semantic comparison into another."""

    for key in (
        "removed",
        "added",
        "transformed",
    ):
        target[key].extend(
            source[key]
        )


def join_path(
    parent: str,
    child: str,
) -> str:
    """Construct a dotted semantic field path."""

    if not parent:
        return child

    return f"{parent}.{child}"


def find_semantic_boundaries(
    run: AgentRun,
) -> list[dict[str, Any]]:
    """
    Pair TOOL_RESULT_RAW and TOOL_RESULT_FOR_MODEL events.

    toolCallId is used where available. Event ordering acts as a fallback.
    """

    raw_events = [
        event
        for event in run.events
        if event.event == "TOOL_RESULT_RAW"
    ]

    model_events = [
        event
        for event in run.events
        if event.event == "TOOL_RESULT_FOR_MODEL"
    ]

    boundaries: list[dict[str, Any]] = []

    used_model_indexes: set[int] = set()

    for raw_event in raw_events:
        raw_call_id = raw_event.attributes.get(
            "toolCallId"
        )

        matched_index: int | None = None

        if raw_call_id is not None:
            for index, model_event in enumerate(model_events):
                if index in used_model_indexes:
                    continue

                model_call_id = model_event.attributes.get(
                    "toolCallId"
                )

                if model_call_id == raw_call_id:
                    matched_index = index
                    break

        if matched_index is None:
            for index, model_event in enumerate(model_events):
                if index in used_model_indexes:
                    continue

                if (
                    model_event.timestamp_value is None
                    or raw_event.timestamp_value is None
                    or model_event.timestamp_value >= raw_event.timestamp_value
                ):
                    matched_index = index
                    break

        if matched_index is None:
            continue

        used_model_indexes.add(
            matched_index
        )

        model_event = model_events[
            matched_index
        ]

        comparison = compare_values(
            raw_event.payload,
            model_event.payload,
        )

        boundaries.append(
            {
                "service": (
                    raw_event.attributes.get("service")
                    or model_event.attributes.get("service")
                ),
                "tool_call_id": (
                    raw_event.attributes.get("toolCallId")
                    or model_event.attributes.get("toolCallId")
                ),
                "raw_timestamp": raw_event.timestamp,
                "model_timestamp": model_event.timestamp,
                "raw_payload_text": raw_event.payload_text,
                "raw_payload": raw_event.payload,
                "model_payload_text": model_event.payload_text,
                "model_payload": model_event.payload,
                "removed_fields": comparison["removed"],
                "added_fields": comparison["added"],
                "transformed_fields": comparison["transformed"],
            }
        )

    return boundaries


def event_to_document(
    event: AgentEvent,
) -> dict[str, Any]:
    """Convert an AgentEvent to an ordered serializable document."""

    document: dict[str, Any] = {
        "timestamp": event.timestamp,
        "event": event.event,
        "transaction": event.transaction,
        "thread": event.thread,
    }

    if event.request_id:
        document["request_id"] = event.request_id

    if event.agent:
        document["agent"] = event.agent

    if event.service:
        document["service"] = event.service

    if event.test:
        document["test"] = event.test

    if event.attributes:
        document["attributes"] = event.attributes

    if event.payload_text is not None:
        document["payload_text"] = event.payload_text
        document["payload"] = event.payload

    return document


def transaction_summary(
    run: AgentRun,
) -> list[dict[str, Any]]:
    """Return the transaction state associated with each major event."""

    result: list[dict[str, Any]] = []

    for event in run.events:
        if event.transaction is None:
            continue

        result.append(
            {
                "timestamp": event.timestamp,
                "event": event.event,
                "transaction": event.transaction,
            }
        )

    return result


def run_to_document(
    run: AgentRun,
    source_log: Path,
) -> dict[str, Any]:
    """Construct the complete YAML document for one run."""

    boundaries = find_semantic_boundaries(
        run
    )

    return {
        "viewer": {
            "name": "OFBiz Agent Log Viewer",
            "version": VERSION,
        },
        "source": {
            "log": str(source_log),
        },
        "run": {
            "request_id": run.request_id,
            "run_id": run.run_id,
            "agent": run.agent,
            "service": run.service,
            "status": run.status,
            "first_timestamp": run.first_timestamp,
            "last_timestamp": run.last_timestamp,
            "duration_ms": run.duration_ms,
            "event_count": len(run.events),
        },
        "semantic_boundaries": boundaries,
        "transaction_timeline": transaction_summary(
            run
        ),
        "events": [
            event_to_document(event)
            for event in run.events
        ],
    }


def event_details(
    event: AgentEvent,
) -> str:
    """Produce compact event detail text for the Markdown timeline."""

    parts: list[str] = []

    for key in (
        "sequence",
        "model",
        "service",
        "toolCallId",
        "durationMs",
        "toolCount",
        "output",
    ):
        value = event.attributes.get(
            key
        )

        if value is None:
            continue

        parts.append(
            f"{key}={value}"
        )

    return ", ".join(
        parts
    )


def markdown_escape_table(value: str) -> str:
    """Escape Markdown table separators."""

    return value.replace(
        "|",
        "\\|",
    )


def render_markdown(
    run: AgentRun,
    source_log: Path,
) -> str:
    """Render a concise human-readable execution walkthrough."""

    lines: list[str] = []

    lines.append(
        f"# Agent Run `{run.run_id}`"
    )
    lines.append("")

    lines.append(
        "## Overview"
    )
    lines.append("")

    lines.append(
        "| Field | Value |"
    )
    lines.append(
        "|---|---|"
    )

    overview = [
        ("Status", run.status),
        ("Agent", run.agent or ""),
        ("Service", run.service or ""),
        ("Request ID", run.request_id or ""),
        ("Run ID", run.run_id),
        ("Started", run.first_timestamp or ""),
        ("Finished", run.last_timestamp or ""),
        (
            "Duration",
            (
                f"{run.duration_ms} ms"
                if run.duration_ms is not None
                else ""
            ),
        ),
        ("Source", str(source_log)),
    ]

    for key, value in overview:
        lines.append(
            f"| {key} | "
            f"{markdown_escape_table(str(value))} |"
        )

    lines.append("")
    lines.append(
        "## Execution Timeline"
    )
    lines.append("")

    lines.append(
        "| # | Timestamp | Event | Transaction | Details |"
    )
    lines.append(
        "|---:|---|---|---|---|"
    )

    for index, event in enumerate(
        run.events,
        start=1,
    ):
        transaction = (
            event.transaction
            or ""
        )

        details = event_details(
            event
        )

        lines.append(
            "| "
            f"{index} | "
            f"{markdown_escape_table(event.timestamp)} | "
            f"`{markdown_escape_table(event.event)}` | "
            f"{markdown_escape_table(transaction)} | "
            f"{markdown_escape_table(details)} |"
        )

    boundaries = find_semantic_boundaries(
        run
    )

    if boundaries:
        lines.append("")
        lines.append(
            "## Semantic Boundary"
        )
        lines.append("")

        for index, boundary in enumerate(
            boundaries,
            start=1,
        ):
            if len(boundaries) > 1:
                lines.append(
                    f"### Tool Result {index}"
                )
                lines.append("")

            service = boundary.get(
                "service"
            )

            tool_call_id = boundary.get(
                "tool_call_id"
            )

            if service:
                lines.append(
                    f"Service: `{service}`"
                )
                lines.append("")

            if tool_call_id:
                lines.append(
                    f"Tool call: `{tool_call_id}`"
                )
                lines.append("")

            lines.append(
                "### Raw OFBiz Result"
            )
            lines.append("")
            lines.append(
                "```yaml"
            )
            lines.append(
                yaml_dump(
                    boundary["raw_payload"]
                )
            )
            lines.append(
                "```"
            )
            lines.append("")

            lines.append(
                "### Model-Facing Result"
            )
            lines.append("")
            lines.append(
                "```yaml"
            )
            lines.append(
                yaml_dump(
                    boundary["model_payload"]
                )
            )
            lines.append(
                "```"
            )
            lines.append("")

            removed = boundary[
                "removed_fields"
            ]

            added = boundary[
                "added_fields"
            ]

            transformed = boundary[
                "transformed_fields"
            ]

            lines.append(
                "### Boundary Changes"
            )
            lines.append("")

            if not removed and not added and not transformed:
                lines.append(
                    "No semantic changes were detected."
                )
                lines.append("")
            else:
                if removed:
                    lines.append(
                        "**Removed fields**"
                    )
                    lines.append("")

                    lines.append(
                        "| Path | Raw value |"
                    )
                    lines.append(
                        "|---|---|"
                    )

                    for item in removed:
                        lines.append(
                            f"| `{item['path']}` | "
                            f"{display_value(item['value'])} |"
                        )

                    lines.append("")

                if added:
                    lines.append(
                        "**Added fields**"
                    )
                    lines.append("")

                    lines.append(
                        "| Path | Model value |"
                    )
                    lines.append(
                        "|---|---|"
                    )

                    for item in added:
                        lines.append(
                            f"| `{item['path']}` | "
                            f"{display_value(item['value'])} |"
                        )

                    lines.append("")

                if transformed:
                    lines.append(
                        "**Transformed fields**"
                    )
                    lines.append("")

                    lines.append(
                        "| Path | Raw | Model-facing |"
                    )
                    lines.append(
                        "|---|---|---|"
                    )

                    for item in transformed:
                        lines.append(
                            f"| `{item['path']}` | "
                            f"{display_value(item['from'])} | "
                            f"{display_value(item['to'])} |"
                        )

                    lines.append("")

    render_llm_interactions(
        lines,
        run,
    )

    render_final_summary(
        lines,
        run,
    )

    render_transaction_section(
        lines,
        run,
    )

    lines.append("")
    lines.append(
        "## Result"
    )
    lines.append("")

    if run.status == "SUCCESS":
        lines.append(
            "**SUCCESS**"
        )
    elif run.status == "FAILURE":
        lines.append(
            "**FAILURE**"
        )
    else:
        lines.append(
            "**INCOMPLETE**"
        )

    lines.append("")

    return "\n".join(
        lines
    )


def render_llm_interactions(
    lines: list[str],
    run: AgentRun,
) -> None:
    """Append a concise LLM interaction section to Markdown output."""

    requests = [
        event
        for event in run.events
        if event.event == "LLM_REQUEST_PAYLOAD"
    ]

    responses = [
        event
        for event in run.events
        if event.event == "LLM_RESPONSE_PAYLOAD"
    ]

    if not requests and not responses:
        return

    lines.append("")
    lines.append(
        "## LLM Interactions"
    )
    lines.append("")

    sequences: set[Any] = set()

    for event in requests + responses:
        sequence = event.attributes.get(
            "sequence"
        )

        if sequence is not None:
            sequences.add(
                sequence
            )

    for sequence in sorted(
        sequences,
        key=lambda value: str(value),
    ):
        request = find_event_by_sequence(
            requests,
            sequence,
        )

        response = find_event_by_sequence(
            responses,
            sequence,
        )

        lines.append(
            f"### Model Call {sequence}"
        )
        lines.append("")

        if request is not None:
            model = request.attributes.get(
                "model"
            )

            if model:
                lines.append(
                    f"Model: `{model}`"
                )
                lines.append("")

            if request.transaction:
                lines.append(
                    f"Transaction: `{request.transaction}`"
                )
                lines.append("")

            render_request_messages(
                lines,
                request.payload,
            )

        if response is not None:
            render_response_summary(
                lines,
                response.payload,
            )


def find_event_by_sequence(
    events: Iterable[AgentEvent],
    sequence: Any,
) -> AgentEvent | None:
    """Find an LLM event by sequence attribute."""

    for event in events:
        if event.attributes.get(
            "sequence"
        ) == sequence:
            return event

    return None


def render_request_messages(
    lines: list[str],
    payload: Any,
) -> None:
    """Render request messages without dumping the entire request envelope."""

    if not isinstance(payload, dict):
        return

    messages = payload.get(
        "messages"
    )

    if not isinstance(messages, list):
        return

    lines.append(
        "**Messages**"
    )
    lines.append("")

    for message in messages:
        if not isinstance(message, dict):
            continue

        role = message.get(
            "role",
            "unknown",
        )

        lines.append(
            f"- `{role}`"
        )

        content = message.get(
            "content"
        )

        if isinstance(content, str) and content:
            if role == "tool":
                decoded = parse_json_payload(
                    content
                )

                if isinstance(
                    decoded,
                    (dict, list),
                ):
                    lines.append("")
                    lines.append(
                        "  ```yaml"
                    )

                    rendered = yaml_dump(
                        decoded
                    )

                    for rendered_line in rendered.splitlines():
                        lines.append(
                            "  " + rendered_line
                        )

                    lines.append(
                        "  ```"
                    )
                else:
                    lines.append(
                        f"  {content}"
                    )
            else:
                compact = content.replace(
                    "\n",
                    " ",
                )

                lines.append(
                    f"  {compact}"
                )

        tool_calls = message.get(
            "tool_calls"
        )

        if isinstance(tool_calls, list):
            for tool_call in tool_calls:
                if not isinstance(tool_call, dict):
                    continue

                function = tool_call.get(
                    "function"
                )

                if not isinstance(
                    function,
                    dict,
                ):
                    continue

                name = function.get(
                    "name"
                )

                arguments = function.get(
                    "arguments"
                )

                lines.append(
                    f"  Tool call: `{name}`"
                )

                if arguments is not None:
                    lines.append(
                        f"  Arguments: `{arguments}`"
                    )

    lines.append("")


def render_response_summary(
    lines: list[str],
    payload: Any,
) -> None:
    """Render the assistant content from one Chat Completions response."""

    content = extract_response_content(
        payload
    )

    if content is None:
        return

    lines.append(
        "**Response**"
    )
    lines.append("")
    lines.append(
        "```text"
    )
    lines.append(
        content
    )
    lines.append(
        "```"
    )
    lines.append("")


def extract_response_content(
    payload: Any,
) -> str | None:
    """Extract assistant content from an OpenAI-compatible response."""

    if not isinstance(payload, dict):
        return None

    choices = payload.get(
        "choices"
    )

    if not isinstance(choices, list):
        return None

    if not choices:
        return None

    choice = choices[0]

    if not isinstance(choice, dict):
        return None

    message = choice.get(
        "message"
    )

    if not isinstance(message, dict):
        return None

    content = message.get(
        "content"
    )

    if not isinstance(content, str):
        return None

    return content


def render_final_summary(
    lines: list[str],
    run: AgentRun,
) -> None:
    """Append final agent summary to Markdown."""

    summaries = [
        event
        for event in run.events
        if event.event == "FINAL_SUMMARY"
    ]

    if not summaries:
        return

    summary = summaries[-1]

    if not isinstance(
        summary.payload,
        str,
    ):
        return

    lines.append("")
    lines.append(
        "## Final Summary"
    )
    lines.append("")
    lines.append(
        summary.payload
    )
    lines.append("")


def render_transaction_section(
    lines: list[str],
    run: AgentRun,
) -> None:
    """Append transaction-state evidence to Markdown."""

    transaction_events = [
        event
        for event in run.events
        if event.transaction is not None
    ]

    if not transaction_events:
        return

    lines.append("")
    lines.append(
        "## Transaction Evidence"
    )
    lines.append("")

    lines.append(
        "| Event | Transaction |"
    )
    lines.append(
        "|---|---|"
    )

    important_events = {
        "AGENT_START",
        "LLM_REQUEST",
        "LLM_REQUEST_PAYLOAD",
        "LLM_RESPONSE",
        "LLM_RESPONSE_PAYLOAD",
        "TOOL_CALL_START",
        "TOOL_CALL_RESULT",
        "TOOL_RESULT_RAW",
        "TOOL_RESULT_FOR_MODEL",
        "FINAL_SUMMARY",
        "AGENT_SUCCESS",
        "AGENT_FAILURE",
    }

    for event in transaction_events:
        if event.event not in important_events:
            continue

        lines.append(
            f"| `{event.event}` | "
            f"{event.transaction} |"
        )

    lines.append("")


def safe_file_component(value: str) -> str:
    """Convert an identifier to a safe filename component."""

    return re.sub(
        r"[^A-Za-z0-9_.-]+",
        "_",
        value,
    )


def write_run_outputs(
    run: AgentRun,
    source_log: Path,
    output_dir: Path,
) -> tuple[Path, Path, Path]:
    """Write YAML, Markdown and raw evidence for one run."""

    output_dir.mkdir(
        parents=True,
        exist_ok=True,
    )

    run_component = safe_file_component(
        run.run_id
    )

    base_name = (
        f"agent-run-{run_component}"
    )

    yaml_path = output_dir / (
        base_name + ".yaml"
    )

    markdown_path = output_dir / (
        base_name + ".md"
    )

    raw_path = output_dir / (
        base_name + ".raw.txt"
    )

    document = run_to_document(
        run,
        source_log,
    )

    yaml_text = (
        yaml_dump(
            document
        )
        + "\n"
    )

    markdown_text = render_markdown(
        run,
        source_log,
    )

    raw_text = "\n".join(
        event.raw_line
        for event in run.events
    )

    if raw_text:
        raw_text += "\n"

    yaml_path.write_text(
        yaml_text,
        encoding="utf-8",
    )

    markdown_path.write_text(
        markdown_text,
        encoding="utf-8",
    )

    raw_path.write_text(
        raw_text,
        encoding="utf-8",
    )

    return (
        yaml_path,
        markdown_path,
        raw_path,
    )


def print_run_list(
    runs: dict[str, AgentRun],
) -> None:
    """Print a compact list of discovered runs."""

    ordered = sorted(
        runs.values(),
        key=run_sort_key,
    )

    if not ordered:
        print(
            "No AgentTrace runs found."
        )
        return

    print(
        "Timestamp                  Status      Agent"
        "                            Run ID"
    )

    print(
        "-" * 120
    )

    for run in ordered:
        timestamp = (
            run.last_timestamp
            or ""
        )

        agent = (
            run.agent
            or ""
        )

        print(
            f"{timestamp:<26}"
            f"{run.status:<12}"
            f"{agent:<33}"
            f"{run.run_id}"
        )


def build_argument_parser() -> argparse.ArgumentParser:
    """Construct CLI parser."""

    parser = argparse.ArgumentParser(
        description=(
            "Parse OFBiz AgentTrace output into readable "
            "YAML and Markdown."
        )
    )

    parser.add_argument(
        "log_file",
        type=Path,
        help=(
            "Path to runtime/logs/agent.log"
        ),
    )

    selector = parser.add_mutually_exclusive_group()

    selector.add_argument(
        "--run-id",
        help=(
            "Render the specified RUN identifier."
        ),
    )

    selector.add_argument(
        "--request-id",
        help=(
            "Render runs associated with the specified REQ identifier."
        ),
    )

    selector.add_argument(
        "--all-runs",
        action="store_true",
        help=(
            "Render every AgentTrace run found in the log."
        ),
    )

    parser.add_argument(
        "--list-runs",
        action="store_true",
        help=(
            "List discovered runs without creating output files."
        ),
    )

    parser.add_argument(
        "--output-dir",
        type=Path,
        help=(
            "Output directory. Defaults to "
            "<log-directory>/agent-viewer."
        ),
    )

    parser.add_argument(
        "--verbose",
        action="store_true",
        help=(
            "Enable parser diagnostic logging."
        ),
    )

    parser.add_argument(
        "--version",
        action="version",
        version=(
            f"%(prog)s {VERSION}"
        ),
    )

    return parser


def main() -> int:
    """Application entry point."""

    parser = build_argument_parser()

    args = parser.parse_args()

    configure_logging(
        args.verbose
    )

    log_path: Path = args.log_file

    try:
        if not log_path.exists():
            LOGGER.error(
                "Agent log does not exist: %s",
                log_path,
            )
            return 2

        if not log_path.is_file():
            LOGGER.error(
                "Agent log is not a file: %s",
                log_path,
            )
            return 2

        runs = read_agent_runs(
            log_path
        )

        if not runs:
            LOGGER.error(
                "No AgentTrace runs found in %s",
                log_path,
            )
            return 2

        if args.list_runs:
            print_run_list(
                runs
            )
            return 0

        selected = select_runs(
            runs=runs,
            run_id=args.run_id,
            request_id=args.request_id,
            all_runs=args.all_runs,
        )

        if not selected:
            if args.run_id:
                LOGGER.error(
                    "Run ID not found: %s",
                    args.run_id,
                )
            elif args.request_id:
                LOGGER.error(
                    "Request ID not found: %s",
                    args.request_id,
                )
            else:
                LOGGER.error(
                    "No matching agent run found."
                )

            return 2

        output_dir = (
            args.output_dir
            if args.output_dir is not None
            else log_path.parent / "agent-viewer"
        )

        for run in selected:
            (
                yaml_path,
                markdown_path,
                raw_path,
            ) = write_run_outputs(
                run=run,
                source_log=log_path,
                output_dir=output_dir,
            )

            print(
                f"Run:      {run.run_id}"
            )
            print(
                f"Status:   {run.status}"
            )
            print(
                f"YAML:     {yaml_path}"
            )
            print(
                f"Markdown: {markdown_path}"
            )
            print(
                f"Raw:      {raw_path}"
            )

            if run.duration_ms is not None:
                print(
                    f"Duration: {run.duration_ms} ms"
                )

            print()

        return 0

    except Exception as exc:
        LOGGER.error(
            "Agent log viewer failed: %s",
            exc,
            exc_info=args.verbose,
        )
        return 1


if __name__ == "__main__":
    sys.exit(
        main()
    )
    