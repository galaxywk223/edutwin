"""Independent command line for knowledge training and one-shot test evaluation."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from edutwin_modeling.config import ProjectPaths, load_yaml
from edutwin_modeling.errors import EduTwinError
from edutwin_modeling.knowledge.pipeline import (
    finalize_knowledge_test,
    train_knowledge_models,
    verify_knowledge_delivery,
)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="python -m edutwin_modeling.knowledge")
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("train", "finalize-test", "verify"):
        command = commands.add_parser(name)
        command.add_argument(
            "--config",
            type=Path,
            default=Path("configs/knowledge/training.yaml"),
        )
    return parser


def main(argv: list[str] | None = None) -> int:
    arguments = _parser().parse_args(argv)
    try:
        paths = ProjectPaths.discover()
        config_path = arguments.config
        if not config_path.is_absolute():
            config_path = (paths.modeling_root / config_path).resolve()
        config = load_yaml(config_path)
        if arguments.command == "train":
            result = train_knowledge_models(config, paths)
        elif arguments.command == "finalize-test":
            result = finalize_knowledge_test(config, paths)
        else:
            result = verify_knowledge_delivery(config, paths)
    except EduTwinError as exc:
        print(f"edutwin-knowledge: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=True, sort_keys=True, default=str))
    return 0
