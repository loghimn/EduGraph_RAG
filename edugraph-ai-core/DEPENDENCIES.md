# Deployment dependency locks

`requirements.txt` remains the portable input for local development. CI and the
Docker image install the Linux x86_64 / Python 3.12 lockfiles instead:

- `requirements-cpu.lock`: PyTorch 2.5.1+cpu, used by Python CI and CPU images.
- `requirements-cu121.lock`: PyTorch 2.5.1+cu121 and its NVIDIA dependencies.

Both lockfiles include resolved transitive dependencies and SHA-256 hashes.
Install with `--require-hashes --only-binary=:all:` so missing pins, modified
artifacts, and source-only distributions fail installation. Do not remove these
checks to work around a dependency error.

Regenerate both files after changing `requirements.txt`, using uv 0.13.9 from this
directory (commands are single lines and work in PowerShell or Bash):

```text
uv pip compile requirements.txt --python-version 3.12 --python-platform x86_64-manylinux_2_28 --torch-backend cpu --only-binary :all: --generate-hashes --no-annotate --output-file requirements-cpu.lock
uv pip compile requirements.txt --python-version 3.12 --python-platform x86_64-manylinux_2_28 --torch-backend cu121 --only-binary :all: --generate-hashes --no-annotate --output-file requirements-cu121.lock
```

Review the generated diff, then run Python CI and both Docker image builds.
Changing a platform or Python version requires regenerating and testing the locks.

The Docker build arguments must agree:

| Image | `TORCH_INDEX_URL` | `REQUIREMENTS_LOCK` |
| --- | --- | --- |
| CPU (default) | `https://download.pytorch.org/whl/cpu` | `requirements-cpu.lock` |
| CUDA 12.1 | `https://download.pytorch.org/whl/cu121` | `requirements-cu121.lock` |

The package hashes and exact PyTorch local version constrain installation even
though both PyPI and the corresponding official PyTorch index are queried.
