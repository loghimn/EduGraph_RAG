"""Check that retry tests reject broken exit statuses and attempt counts."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parent.parent
SCRIPTS = {
    "java-docker-retry-check.sh": ["core-service/Dockerfile", "gateway-service/Dockerfile"],
    "maven-bootstrap-tests.sh": ["outputs/maven-bootstrap-command.sh"],
}
MUTATIONS = {
    "wrong-status": ('if [ "$attempt" -eq 3 ]; then exit 1; fi', 'if [ "$attempt" -eq 3 ]; then exit 0; fi'),
    "wrong-count": ("for attempt in 1 2 3; do", "for attempt in 1 2; do"),
}


def prepare_fixture(fixture, script, mutation):
    sources = SCRIPTS[script]
    for name in [f"outputs/{script}", *sources]:
        target = fixture / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / name, target)
    before, after = MUTATIONS[mutation]
    for name in sources:
        target = fixture / name
        original = target.read_text(encoding="utf-8")
        if original.count(before) != 1:
            raise AssertionError(f"Cannot inject {mutation} into {name}")
        target.write_text(original.replace(before, after), encoding="utf-8")


def run_fixture(fixture, script):
    # Neither executable nor script arguments come from CLI input or PATH.
    if script not in SCRIPTS:
        raise ValueError("Unknown retry test script")
    if os.name == "nt":
        command = ["C:/Program Files/Git/bin/bash.exe", f"outputs/{script}"]
    else:
        command = ["/bin/bash", f"outputs/{script}"]
    return subprocess.run(command, cwd=fixture, capture_output=True, text=True, timeout=15, check=False)


def main():
    for script in SCRIPTS:
        for mutation in MUTATIONS:
            with tempfile.TemporaryDirectory(prefix="edugraph-retry-guards-") as directory:
                fixture = Path(directory)
                prepare_fixture(fixture, script, mutation)
                result = run_fixture(fixture, script)
                if result.returncode == 0:
                    raise AssertionError(f"{script} incorrectly accepted {mutation}")
                print(f"{script}: rejected {mutation}")


if __name__ == "__main__":
    main()
