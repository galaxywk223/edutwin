"""Command-line entrypoint for reproducible EduTwin modeling workflows."""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Any

from edutwin_modeling.config import ProjectPaths, load_yaml
from edutwin_modeling.data.bootstrap import (
    generate_initial_states,
    verify_initial_states,
)
from edutwin_modeling.data.pipeline import prepare_data, verify_processed_data
from edutwin_modeling.errors import EduTwinError
from edutwin_modeling.knowledge.pipeline import (
    finalize_knowledge_test,
    train_knowledge_models,
    verify_knowledge_delivery,
)
from edutwin_modeling.risk.pipeline import (
    finalize_risk_test,
    train_risk_models,
    verify_risk_delivery,
)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="edutwin-modeling")
    commands = parser.add_subparsers(dest="command", required=True)
    data = commands.add_parser("data")
    data_commands = data.add_subparsers(dest="data_command", required=True)
    for name in ("prepare", "verify"):
        command = data_commands.add_parser(name)
        command.add_argument(
            "--config",
            type=Path,
            default=Path("configs/data/pipeline.yaml"),
        )
    knowledge = commands.add_parser("knowledge")
    knowledge_commands = knowledge.add_subparsers(
        dest="knowledge_command", required=True
    )
    for name in ("train", "finalize-test", "verify"):
        command = knowledge_commands.add_parser(name)
        command.add_argument(
            "--config",
            type=Path,
            default=Path("configs/knowledge/training.yaml"),
        )
    risk = commands.add_parser("risk")
    risk_commands = risk.add_subparsers(dest="risk_command", required=True)
    for name in ("train", "finalize-test", "verify"):
        command = risk_commands.add_parser(name)
        command.add_argument(
            "--config",
            type=Path,
            default=Path("configs/risk/training.yaml"),
        )
    demo = commands.add_parser("demo")
    demo_commands = demo.add_subparsers(dest="demo_command", required=True)
    for name in ("bootstrap", "verify-bootstrap"):
        command = demo_commands.add_parser(name)
        command.add_argument(
            "--config",
            type=Path,
            default=Path("configs/demo/bootstrap.yaml"),
        )
    serve = commands.add_parser("serve")
    serve.add_argument(
        "--config",
        type=Path,
        default=Path("configs/serving/service.yaml"),
    )
    serve.add_argument("--host", default="127.0.0.1")
    serve.add_argument("--port", type=int, default=8000)
    return parser


def _run(arguments: argparse.Namespace) -> dict[str, Any]:
    paths = ProjectPaths.discover()
    config_path = arguments.config
    if not config_path.is_absolute():
        config_path = (paths.modeling_root / config_path).resolve()
    config = load_yaml(config_path)
    if arguments.command == "serve":
        if not 1 <= arguments.port <= 65_535:
            raise EduTwinError("serve port must be between 1 and 65535")
        os.environ["EDUTWIN_MODEL_CONFIG"] = str(config_path)
        import uvicorn

        uvicorn.run(
            "edutwin_modeling.serving.app:app",
            host=str(arguments.host),
            port=int(arguments.port),
            workers=1,
            proxy_headers=True,
            forwarded_allow_ips="*",
        )
        return {
            "status": "STOPPED",
            "serviceVersion": config.get("service_version"),
        }
    if arguments.command == "data" and arguments.data_command == "prepare":
        return prepare_data(config, paths)
    if arguments.command == "data" and arguments.data_command == "verify":
        return verify_processed_data(config, paths)
    if arguments.command == "knowledge" and arguments.knowledge_command == "train":
        return train_knowledge_models(config, paths)
    if (
        arguments.command == "knowledge"
        and arguments.knowledge_command == "finalize-test"
    ):
        return finalize_knowledge_test(config, paths)
    if arguments.command == "knowledge" and arguments.knowledge_command == "verify":
        return verify_knowledge_delivery(config, paths)
    if arguments.command == "risk" and arguments.risk_command == "train":
        return train_risk_models(config, paths)
    if arguments.command == "risk" and arguments.risk_command == "finalize-test":
        return finalize_risk_test(config, paths)
    if arguments.command == "risk" and arguments.risk_command == "verify":
        return verify_risk_delivery(config, paths)
    if arguments.command == "demo" and arguments.demo_command == "bootstrap":
        return generate_initial_states(config, paths)
    if arguments.command == "demo" and arguments.demo_command == "verify-bootstrap":
        return verify_initial_states(config, paths)
    raise AssertionError("argparse accepted an unsupported command")


def main(argv: list[str] | None = None) -> int:
    try:
        result = _run(_parser().parse_args(argv))
    except EduTwinError as exc:
        print(f"edutwin-modeling: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=True, sort_keys=True, default=str))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
