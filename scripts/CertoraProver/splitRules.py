#     The Certora Prover
#     Copyright (C) 2025  Certora Ltd.
#
#     This program is free software: you can redistribute it and/or modify
#     it under the terms of the GNU General Public License as published by
#     the Free Software Foundation, version 3 of the License.
#
#     This program is distributed in the hope that it will be useful,
#     but WITHOUT ANY WARRANTY; without even the implied warranty of
#     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
#     GNU General Public License for more details.
#
#     You should have received a copy of the GNU General Public License
#     along with this program.  If not, see <https://www.gnu.org/licenses/>.

import logging
import sys
import re
from pathlib import Path
import subprocess
import tempfile
import uuid

from typing import List, Set, Optional

import CertoraProver.certoraContext as Ctx
import CertoraProver.certoraContextAttributes as Attrs
import CertoraProver.certoraApp as App
from CertoraProver.certoraContextClass import CertoraContext
from Shared import certoraUtils as Util

scripts_dir_path = Path(__file__).parent.resolve()
sys.path.insert(0, str(scripts_dir_path))

split_rules_logger = logging.getLogger("split_rules")

def update_msg(msg: str, rule_str: str) -> str:
    pattern = r"\(Rule\(s\): .*?\)$"  # Matches "(Rule(s): some text )" at the end of msg

    if re.search(pattern, msg):
        return re.sub(pattern, f" (Rule(s): {rule_str})", msg)
    else:
        return f"{msg} (Rule(s): {rule_str})"

class SplitRulesHandler():
    context: CertoraContext
    all_rules: Optional[Set[str]] = None  # all rules in the spec (from Typechecker.jar for EVM, from conf file for Rust)
    split_rules: Optional[Set[str]] = None  # all rules that should be run separately (based on --split_rules)
    rest_rules: Optional[Set[str]] = None  # all rules that should not be run separately ( all_rules - split_rules)

    def __init__(self, context: CertoraContext):
        if not context:
            raise ValueError("SplitRulesHandler: context must be set")
        SplitRulesHandler.context = context

    def generate_runs(self) -> int:
        """
        get the split rules and the rest rules, call certoraRun with the appropriate --rule
        :return: 1 if some runs failed 0 if all runs succeeded
        """
        self.all_rules = self.get_cvl_rules()
        assert len(self.all_rules) > 0, "generate_runs: all rules were filtered out"
        self.split_rules = self.get_cvl_rules(True)
        self.rest_rules = self.all_rules - self.split_rules
        return self.run_commands()

    def get_cvl_rules(self, split_rules: bool = False) -> Set[str]:
        """
        getting cvl rules. For EVM, calls Typechecker.jar with the -listRules option.
        For Rust-based apps (Solana, Soroban), reads rules from the conf file (context.rule).
        :param split_rules:
        :return:
        """
        if issubclass(self.context.app, App.RustAppClass):
            return self._get_rules_from_conf(split_rules)
        return self._get_rules_from_typechecker(split_rules)

    def _get_rules_from_conf(self, split_rules: bool = False) -> Set[str]:
        all_rules = set(self.context.rule) if self.context.rule else set()
        if split_rules:
            return all_rules & set(self.context.split_rules) if self.context.split_rules else set()
        return all_rules

    def _get_rules_from_typechecker(self, split_rules: bool = False) -> Set[str]:
        def jar_list_value(list_attr: List[str]) -> str:
            return ','.join(list_attr)

        with tempfile.NamedTemporaryFile("r", dir=Util.get_build_dir()) as tmp_file:
            args = ["-listRules", tmp_file.name]

            if self.context.exclude_rule:
                args += ['-excludeRule', jar_list_value(self.context.exclude_rule)]

            if not split_rules and self.context.rule:
                args += ['-rule',  jar_list_value(self.context.rule)]
            elif split_rules and self.context.split_rules:
                args += ['-rule', jar_list_value(self.context.split_rules)]

            try:
                Ctx.run_local_spec_check(False, self.context, args, print_errors=False)
                lines = tmp_file.read().split("\n")
                return set(lines)

            except Exception as e:
                raise Util.CertoraUserInputError(f"Failed to get {'split ' if split_rules else ''}rules\n{e}")

    def run_commands(self) -> int:
        attr_class = self.context.app.attr_class
        rule_flag = attr_class.RULE.get_flag()
        split_rules_flag = attr_class.SPLIT_RULES.get_flag()
        msg_flag = Attrs.CommonAttributes.MSG.get_flag()

        group_id_flag = attr_class.GROUP_ID.get_flag()

        def remove_rule_flags_from_cli() -> List[str]:
            # any --rule flag should be removed from CLI during splitting, since it is set during the split
            new_cli = []
            skip = False
            for item in self.context.args_list:
                if item.startswith(rule_flag) or item.startswith(split_rules_flag) or item.startswith(msg_flag):
                    skip = True
                elif item.startswith('--') and skip:
                    skip = False
                if not skip:
                    new_cli.append(item)
            return new_cli

        def get_cmd() -> str:
            """
            set executable for the split, if called from command line then it is the first string in argv (prover_cmd)
            if called as library then if running in local mode we use the script otherwise the installed package command
            :return:
            """
            if hasattr(self.context, 'prover_cmd'):
                return self.context.prover_cmd
            if issubclass(self.context.app, App.SolanaApp):
                return "certoraSolanaProver.py" if self.context.local else "certoraSolanaProver"
            if self.context.local:
                return Util.CERTORA_RUN_SCRIPT
            return Util.CERTORA_RUN_APP

        def generate_prover_calls() -> List[List[str]]:
            # generate the command line for the runs: a run for each split rule, and another run collecting the rest
            # of the rules
            cli_commands = []
            args = remove_rule_flags_from_cli()
            if not getattr(self.context, 'group_id', None):
                self.context.group_id = str(uuid.uuid4())

            if not getattr(self.context, 'msg', None):
                self.context.msg = ''

            cmd = [get_cmd()] + args + [group_id_flag, self.context.group_id, split_rules_flag]

            # EVM-specific flags
            if hasattr(attr_class, 'BUILD_CACHE'):
                cmd.append(attr_class.BUILD_CACHE.get_flag())
            if hasattr(attr_class, 'DISABLE_LOCAL_TYPECHECKING'):
                cmd.append(attr_class.DISABLE_LOCAL_TYPECHECKING.get_flag())

            if self.split_rules:
                for rule in self.split_rules:
                    cli_commands.append(cmd + [rule_flag, rule, msg_flag, update_msg(self.context.msg, rule)])
            if self.rest_rules:
                cli_commands.append(cmd + [rule_flag] + list(self.rest_rules) +
                                    [msg_flag, update_msg(self.context.msg, "rest of the rules")])
            return cli_commands
        # end of run_commands() nested functions

        prover_calls = generate_prover_calls()
        if getattr(self.context, 'test', None) == str(Util.TestValue.AFTER_RULE_SPLIT):
            raise Util.TestResultsReady(prover_calls)

        processes = []
        # Start all processes
        for command in prover_calls:
            split_rules_logger.debug(f"Running {' '.join(command)}")
            processes.append(subprocess.Popen(command))

        # Wait for all processes to complete and collect return codes
        return_codes = [p.wait() for p in processes]

        return_value = 0
        for i, return_code in enumerate(return_codes):
            if return_code != 0:
                split_rules_logger.debug(f"Process {i} failed with exit code {return_code}")
                return_value = 1

        return return_value
