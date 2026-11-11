# Local prover artifacts for an AI agent harness

Use this guide when an agent runs the Certora Prover locally and needs to decide
what passed, diagnose a failure, or explain a verification result. Read the JSON
reports first and load detailed traces only when needed.

These recommendations were checked against this checkout's report writers and
the successful local Counter run:

```text
Public/TestEVM/Counter/emv-5-certora-11-Nov--12-12/
```

That run directory is generated and ignored by Git. A fresh checkout will need a
new run. File paths below are relative to a run directory unless stated otherwise.
Numeric artifact IDs and schemas should be checked again when upgrading the prover.

## Files to read

| File or pattern | Contents | How an agent should use it |
| --- | --- | --- |
| `Reports/output.json` | Compact final results grouped by rule and, where relevant, method and status. | Primary verdict input. Handle nested results; do not expect every rule value to be a string. Generated sanity children are excluded, so read the tree as well. |
| `Reports/treeView/treeViewStatus_*.json` | Rule hierarchy, semantic statuses, running flags, errors, source ranges, and references to per-rule reports. | Select the newest snapshot by its embedded `timestamp`. Inspect completion and sanity results, and follow each node's `output` references. |
| `Reports/alertReport.json` | Alerts with `type`, `severity`, `message`, and `jumpToDefinition` source locations. | Report actionable warnings separately from property failures. This run contains two CVL declaration warnings. |
| `Reports/treeView/rule_output_*.json` | Source ranges, assertion messages, call-resolution details, and optional `callTrace` and `variables` trees. | Read reports referenced by relevant tree nodes. Use structured values and source locations for explanations or repair suggestions. A verified rule may have no trace or report. |
| `Reports/splitStatsdata.json` | Per-rule and per-split `finalResult`, solver configurations, timeouts, solving time, and TAC statistics. | Diagnose timeouts and solver behavior. Interpret `SAT` and `UNSAT` according to the kind of rule, not as universal pass/fail values. |
| `Reports/statsdata.json` | Analysis timings, resource samples, solver versions, run timestamps, and configuration statistics. | Load selected fields for performance analysis and reproducibility. `used_solvers` records the selected configurations; the broader `available_solvers` string is not proof every listed executable was installed or used. |
| `Reports/treeView/rule_live_statistics_*.json` | Verification phase and rule complexity information. | Optional progress and stall diagnostics. Follow `LiveCheckInfo` references from the tree rather than guessing IDs. Text labels are presentation data. |
| `Reports/Results.txt` | Execution log, warnings, result tables, and diagnostic messages. | Fallback when execution fails or structured artifacts are missing. Do not determine verdicts by searching for words such as `Failed` or `Violated`. |

For the inspected run, `output.json`, the newest tree snapshot, `alertReport.json`,
and `splitStatsdata.json` together are about 10 KB. Start with these rather than
sending every artifact to the model.

## Completion and verdict rules

1. Capture the exact invocation, exit code, stdout, stderr, and the directory
   created by that invocation. Do not accidentally read a previous successful run.
2. Wait for the process to finish before deciding the final result. While running,
   snapshot files can be incomplete; retry reads rather than treating them as final.
3. Parse `output.json` and require the expected rule and method set. A missing or
   skipped check is not evidence that it passed. Preserve `UNKNOWN`, `TIMEOUT`,
   failures, and incomplete execution as distinct outcomes.
4. Parse the tree snapshots and select the highest embedded `timestamp`. Check
   `status`, `isRunning`, and `errors` throughout the relevant hierarchy, including
   generated sanity children. Do not assume `_0` or `_1` is always the final file.
5. Follow `output` references to `rule_output_*.json` for diagnostics. These paths
   are relative to `Reports/treeView/`. Numeric suffixes are run-specific.
6. Read alerts and keep warnings separate from proof verdicts. Missing optional
   statistics or traces do not by themselves mean verification failed; missing
   required verdict or completion evidence means the harness cannot report success.
7. Treat exit code zero as necessary execution evidence, not a sufficient proof
   verdict. The CLI can compare against expected regression results that include
   failures or `UNKNOWN`; a matching expectation can produce a successful CLI exit.

`output.json` deliberately excludes generated sanity children and expanded failing
assertion nodes. The detailed tree is therefore needed alongside the compact result.

### Solver outcomes depend on the rule type

| Check type | `UNSAT` | `SAT` |
| --- | --- | --- |
| Ordinary assertion rule | No violating execution found: verified. | A candidate violating execution: inspect the semantic result and trace. |
| Non-vacuity sanity check | No feasible witness: sanity failure. | Feasible witness found: sanity verified. |
| `satisfy` rule | Requested witness not found: violated. | Requested witness found: verified. |

Prefer the tree's semantic status, such as `VERIFIED`, `VIOLATED`, or
`SANITY_FAILED`, over reinterpreting raw solver output. In particular, do not treat
the existence of a trace or a node called `VIOLATED_ASSERT` as a failure by itself.

The Counter log contains `Violated: ...-rule_not_vacuous` and a textual
`Failed on rule_not_vacuous` summary. Both sanity checks nevertheless have semantic
status `VERIFIED` in the final tree: their feasible witnesses are the desired result.

## Counter example mapping

The inspected run's final `output.json` contains:

```json
{
  "rules": {
    "decrementDecreasesCountByOne": "SUCCESS",
    "envfreeFuncsStaticCheck": {"SUCCESS": ["getCount()"]},
    "incrementIncreasesCountByOne": "SUCCESS"
  }
}
```

Its newest snapshot is `Reports/treeView/treeViewStatus_1.json`. The older `_0`
snapshot still reports a sanity check as running; selecting it would misclassify
the completed run.

| Per-rule artifact in `Reports/treeView/` | Meaning in this run |
| --- | --- |
| `rule_output_1.json` | Increment property and its specification location. |
| `rule_output_2.json` | Increment non-vacuity witness: `countBefore = 2^256 - 2`, `countAfter = MAX_UINT256`. Includes structured trace and variables. |
| `rule_output_3.json` | Decrement property and its specification location. |
| `rule_output_4.json` | Decrement non-vacuity witness: `countBefore = 0`, `countAfter = 0`. Includes structured trace and variables. |
| `rule_output_5.json` | `getCount()` environment check and its specification location. |

These two witness traces are not counterexamples to the arithmetic properties.
This run contains no genuine failed-property example; test a harness's failure
classification and repair behavior separately before relying on that behavior.

## Inputs and reproducibility

Keep these supporting artifacts with the reports:

| Artifact | Purpose |
| --- | --- |
| `inputs/.certora_sources/Counter.sol` and `Counter.spec` | Source and specification snapshots corresponding to the verification. |
| `inputs/.certora_sources/run.conf` | Resolved configuration, including CLI overrides. Prefer this over assuming the original `counter.conf` describes the actual run. |
| `inputs/.certora_metadata.json` | CLI arguments, runtime versions, configuration metadata, and provenance fields when populated. |
| `inputs/.certora_build.json` | Compiled contract build information for deeper diagnostics. |
| `inputs/.certora_verify.json` | Contract/specification selection and import mapping. |
| `inputs/rerun.sh` | Recorded backend command; useful context, but not a self-contained reproduction script. |

In this run, the metadata's Git revision is empty and its dirty flag is true.
The rerun script references an original `.certora_internal/...` build directory
and does not preserve all container or JVM settings. A harness should separately
record source/specification/configuration hashes, prover JAR hash, Docker image
digest and platform, Java options, solver versions, and network mode. Never store
credentials in the manifest.

## Reproduce a local sample without a key or cloud access

From the repository root, with the previously built `certora-evm:local` image:

```sh
docker run --rm --platform linux/amd64 --network none \
  -e CERTORAKEY= \
  -e 'JAVA_TOOL_OPTIONS=-Djdk.lang.Process.launchMechanism=FORK -XX:ActiveProcessorCount=1' \
  -v "$PWD:/work" -w /work/Public/TestEVM/Counter \
  certora-evm:local certoraRun.py counter.conf \
  --jar /opt/certora/emv.jar \
  --max_concurrent_rules 1 \
  --prover_args='-solvers z3:def' \
  --smt_timeout 15 --global_timeout 90
```

This command completed with exit code zero and basic sanity checks enabled.
`CERTORAKEY` is explicitly empty, the JAR is local, and networking is disabled.
The JVM and solver settings avoided subprocess stalls observed with this machine's
amd64 Docker image on ARM. They are a tested configuration for this environment,
not a universal requirement. The configured global timeout did not reliably stop
earlier stalled subprocess launches; an unattended harness should also enforce
an external wall-clock deadline and terminate only its own run container.

## Files to keep outside the default model context

| Files | Treatment |
| --- | --- |
| `Reports/progress.json` | Not valid JSON in this checkout's inspected run. It contains textual `RegisteredRule(...)` keys. Do not use it as the structured progress or verdict interface. |
| `Reports/FinalResults.html` | Link for human review; use JSON for programmatic verdicts. |
| `Reports/PresolverRule-*.html` | Intermediate-program visualizations for specialized debugging. |
| `Reports/Report-*-example*.html` | Human-readable example visualizations; prefer corresponding structured trace JSON. |
| `Reports/ctpp_*.txt` | Verbose debug traces; use only when the structured trace is insufficient. |
| `outputs/*.tac` | Intermediate verification programs for prover/compiler investigation. They are not independent proof certificates. |
| `debugs/Cmd_dot_*.txt` | Graphviz subprocess diagnostics. This run has 48 such files containing only command labels, with no useful property-result detail. |
| `.DS_Store` | macOS metadata; ignore. |

## Implementation references

- [TreeViewReporter.kt](src/main/kotlin/report/TreeViewReporter.kt): semantic status mapping, per-assert reports, and compact output generation.
- [StatusReporter.kt](src/main/kotlin/report/StatusReporter.kt): progress-file generation.
- [ArtifactManager.kt](lib/Shared/src/main/kotlin/log/ArtifactManager.kt): per-rule artifact registration and filenames.
- [certoraRun.py](scripts/certoraRun.py): local execution and expected-result comparison.
- [Counter specification](Public/TestEVM/Counter/Counter.spec): properties verified by this example.
