<div align="center">

[![GitMCP](https://img.shields.io/endpoint?url=https://gitmcp.io/badge/Certora/CertoraProver)](https://gitmcp.io/Certora/CertoraProver)
[![Twitter Follow](https://img.shields.io/twitter/follow/certorainc?style=social)](https://x.com/certorainc)
</div>

# Certora Prover

The Certora Prover is a tool for formally verifying smart contracts.
This document is intended for those who would like to contribute to the tool.

If you are interested to use the tool on our cloud platform without having to locally build it,
we recommend following the documentation here: https://docs.certora.com/en/latest/docs/user-guide/install.html.

The instructions here are for users on Mac OS and Linux.

## Dependencies
* JDK 19+
* SMT solvers:
  * [required] Z3 -- https://github.com/Z3Prover/z3/releases
  * [required] CVC5 -- https://github.com/cvc5/cvc5/releases
  * [optional] CVC4 -- https://cvc4.github.io/downloads.html
  * [optional] Yices -- https://github.com/SRI-CSL/yices2/releases
  * [optional] Bitwuzla -- https://github.com/bitwuzla/bitwuzla/releases
  * _NOTE_ Whichever solvers you decide to install, remember to put the executables in a directory in your system's `PATH`.

* Python 3
    - We recommend downloading from here: https://www.python.org/downloads/
    - Make sure the version of pip matches with the python version

* Solidity compiler -- https://github.com/ethereum/solidity/releases.
  Pick the version(s) that is used by the contracts you want to verify.
  Since we often use many versions, it is recommended to rename each `solc` executable
  to, e.g., solc5.12, and place all versions into a directory in your systems `PATH` like so: `export PATH="/path/to/dir/with/executables:$PATH"`

* Rust (tested on Version 1.81.0+) -- https://www.rust-lang.org/tools/install

* [`llvm-symbolizer`](https://llvm.org/docs/CommandGuide/llvm-symbolizer.html) and [`llvm-dwarfdump`](https://llvm.org/docs/CommandGuide/llvm-dwarfdump.html),
  which are installed as part of LLVM.

* [`rustfilt`](https://github.com/luser/rustfilt)


## Optional Dependencies:
* [`Graphviz`](https://graphviz.org/download/):
    Graphviz is an optional dependency required for rendering visual elements, `dot` in particular.
    If not installed, some features may not work properly, such as [Tac Reports](https://docs.certora.com/en/latest/docs/prover/diagnosis/index.html#tac-reports).
    _NOTE_ Remember to put `dot` in your system's `PATH`, by running:
```
    export PATH="/usr/local/bin:$PATH".
```
* (Replace /usr/local/bin with the actual path where dot is installed.)

## Installation
* Create a directory anywhere to store build outputs.

    - Add an environment variable `CERTORA` whose value is the path to this directory.

    - Add this directory to `PATH` as well. For example if you are using a bash shell, you can edit your `~/.bashrc` file like so:
```
      export CERTORA="preferred/path/for/storing/build/outputs"
      export PATH="$CERTORA:$PATH"
```

* `cd` into a directory you want to store the CertoraProver source and clone the repo:
   ```
    git clone --recurse-submodules https://github.com/Certora/CertoraProver.git
   ```

* Compile the code by running: `./gradlew assemble`

* If you want to clean up all artifacts of the project, run: `./gradlew clean`

* Make sure the path you used to set the variable `CERTORA` has important jars, scripts, and binaries like `emv.jar`, `certoraRun.py`, `tac_optimizer`.

### Troubleshooting
- We recommend working from within a python virtual environment and installing all dependencies there:
```commandline
cd CertoraProver
python -m venv .venv
source .venv/bin/activate
pip install -r scripts/certora_cli_requirements.txt
```
- If you have `Crypto` installed, you may first need to uninstall (`pip uninstall crypto`) before installing `pycryptodome`
- You can make sure `tac_optimizer` builds correctly by `cd`ing in to the `fried-egg` directory and running `cargo build --release`. Also make sure `tac_optimizer` is in your path (set using `CERTORA`).

## Running

- You can run the tool by running `certoraRun.py -h` to see all the options.
    - There are several small examples for testing under `Public/TestEVM`. For example, you can run one of these like so:
  ```commandline
        cd Public/TestEVM/CVLCompilation/OptionalFunction
        certoraRun.py Default.conf
   ```
    - Please refer to the user guide for details on how to run the prover on real-world smart contracts: https://docs.certora.com/en/latest/docs/user-guide/index.html

- You can run unit tests directly from IDEs like IntelliJ, or from the command line with `./gradlew test --tests <name_of_test_with_wildcards>`
    - These tests are in `CertoraProver/src/test` (and also in the test directories of the various subprojects)

## Docker (EVM)

The repository includes a fully containerized EVM workflow using Docker Compose.
The image is built from source and runs as `linux/amd64` for broad Solidity compiler compatibility.
On Apple Silicon, Docker will run this image via emulation.
The image ships multiple preinstalled Solidity compilers such as `solc8.17`, `solc8.28`, and `solc8.30`.
Inside the container, the default `solc` executable is pinned to `0.8.30`.

- Build the image:
  ```commandline
  docker compose build certora
  ```

- Show CLI help:
  ```commandline
  docker compose run --rm certora certoraRun.py -h
  ```

When `CERTORAKEY` is not set, `certoraRun.py` runs in local mode.

- Run a local sample verification:
  ```commandline
  docker compose run --rm certora bash -lc 'cd Public/TestEVM/CVLCompilation/OptionalFunction && certoraRun.py Default.conf'
  ```

- Run a cloud verification (requires `CERTORAKEY`):
  ```commandline
  cp .env.example .env
  # edit .env and set CERTORAKEY
  docker compose run --rm certora bash -lc 'cd Public/TestEVM/CVLCompilation/OptionalFunction && certoraRun.py Default.conf --wait_for_results all'
  ```

### Choosing the Solidity compiler in Docker

Use the Certora CLI flags to select the compiler for each run.

- Switch one run to a specific compiler version:
  ```commandline
  docker compose run --rm certora bash -lc 'cd Public/TestEVM/CVLCompilation/OptionalFunction && certoraRun.py Default.conf --solc solc8.28'
  ```

- Compile different contracts with different compilers:
  ```commandline
  docker compose run --rm certora bash -lc 'cd /work && certoraRun.py path/to/Run.conf --compiler_map A=solc8.17,B=solc8.30'
  ```

- Change the Solidity target EVM version without changing the compiler binary:
  ```commandline
  docker compose run --rm certora bash -lc 'cd Public/TestEVM/CVLCompilation/OptionalFunction && certoraRun.py Default.conf --solc_evm_version cancun'
  ```

`--solc`, `--solc_map`, and `--compiler_map` choose which compiler executable runs.
`--solc_evm_version` and `--solc_evm_version_map` change the `evmVersion` setting passed to that compiler.

`solc-select use <version>` is not the reliable way to switch the default compiler in the current Docker image.
The image pins `/usr/local/bin/solc` to `solc8.30`, so plain `solc` continues to resolve to that default unless you pass an explicit compiler executable such as `--solc solc8.28`.

### Docker troubleshooting

If a container run appears to ignore a compiler change, check the resolved executable first:

```commandline
docker compose run --rm certora bash -lc 'which solc && solc --version'
```

Then compare that with an explicit per-run override:

```commandline
docker compose run --rm certora bash -lc 'cd Public/TestEVM/CVLCompilation/OptionalFunction && certoraRun.py Default.conf --solc solc8.28'
```

### Pure Docker (no Docker Compose)

If you prefer plain Docker commands, run these from the repository root.

- Build the image:
  ```commandline
  docker build --platform linux/amd64 -t certora-evm:local .
  ```

- Show CLI help:
  ```commandline
  docker run --rm --platform linux/amd64 -it -v "$PWD":/work -w /work certora-evm:local certoraRun.py -h
  ```

- Open an interactive shell:
  ```commandline
  docker run --rm --platform linux/amd64 -it -v "$PWD":/work -w /work certora-evm:local bash
  ```
  This opens an interactive shell in the container so you can run commands manually.

- Run a local sample verification:
  ```commandline
  docker run --rm --platform linux/amd64 -it -v "$PWD":/work -w /work certora-evm:local bash -lc 'cd Public/TestEVM/CVLCompilation/OptionalFunction && certoraRun.py Default.conf'
  ```

- Run a cloud verification:
  ```commandline
  docker run --rm --platform linux/amd64 -it -e CERTORAKEY="$CERTORAKEY" -v "$PWD":/work -w /work certora-evm:local bash -lc 'cd Public/TestEVM/CVLCompilation/OptionalFunction && certoraRun.py Default.conf --wait_for_results all'
  ```

`CERTORAKEY` is optional for local runs and required for cloud verification.

## Contributing
1. Fork the repo and open a pull request with your changes.
2. Contact Certora at devhelp@certora.com once your PR is ready.
3. Certora will assign a dev representative who will review and test the changes, and provide feedback directly in the PR.
4. Once the feature is approved and ready to be merged, Certora will merge it through its internal process and include the feature in a subsequent Prover release.

## LICENSE
Copyright (C) 2025 Certora Ltd. The Certora Prover is released under the GNU General Public License, Version 3, as published by the Free Software Foundation. For more information, see the file LICENSE.
