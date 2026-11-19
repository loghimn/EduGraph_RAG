"""Check that retry tests reject broken exit statuses and attempt counts."""
import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bash", default="bash")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    scripts = {
        "java-docker-retry-check.sh": ["core-service/Dockerfile", "gateway-service/Dockerfile"],
        "maven-bootstrap-tests.sh": ["outputs/maven-bootstrap-command.sh"],
    }
    for script, sources in scripts.items():
        for mutation in ("wrong-status", "wrong-count"):
            with tempfile.TemporaryDirectory(prefix="edugraph-retry-guards-") as directory:
                fixture = Path(directory)
                for name in [f"outputs/{script}", *sources]:
                    target = fixture / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(root / name, target)
                for name in sources:
                    target = fixture / name
                    original = target.read_text(encoding="utf-8")
                    before, after = (
                        ('if [ "$attempt" -eq 3 ]; then exit 1; fi', 'if [ "$attempt" -eq 3 ]; then exit 0; fi')
                        if mutation == "wrong-status" else
                        ("for attempt in 1 2 3; do", "for attempt in 1 2; do")
                    )
                    if original.count(before) != 1:
                        raise AssertionError(f"Cannot inject {mutation} into {name}")
                    target.write_text(original.replace(before, after), encoding="utf-8")
                result = subprocess.run(
                    [args.bash, f"outputs/{script}"], cwd=fixture,
                    capture_output=True, text=True, timeout=15,
                )
                if result.returncode == 0:
                    raise AssertionError(f"{script} incorrectly accepted {mutation}")
                print(f"{script}: rejected {mutation}")


if __name__ == "__main__":
    main()
