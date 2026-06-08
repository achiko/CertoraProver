/*
 *     The Certora Prover
 *     Copyright (C) 2025  Certora Ltd.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package sbf.cfg

import sbf.SolanaConfig
import sbf.analysis.*
import sbf.callgraph.*
import sbf.callgraph.SolanaSysVarId
import sbf.disassembler.GlobalVariables
import sbf.domains.*

/**
 * Replace calls to `sol_get_sysvar` with either `cvt_sol_get_rent_sysvar` or `cvt_sol_get_clock_sysvar`
 * based on the sysvar identity encoded in R1 at the call site.
 *
 * This transformation must run after global variable inference so that R1 can be resolved to a
 * known global variable containing the sysvar public key.
 */
fun resolveGetSysvarCalls(cfg: MutableSbfCFG, globals: GlobalVariables, memSummaries: MemorySummaries) {
    val hasSysvarCall = cfg.getBlocks().values.any { b ->
        b.getInstructions().any { it is SbfInstruction.Call && it.name == SolanaFunction.SOL_GET_SYSVAR.syscall.name }
    }
    if (!hasSysvarCall) {
        return
    }
    val sbfTypesFac = ConstantSetSbfTypeFactory(SolanaConfig.ScalarMaxVals.get().toULong())
    val scalarAnalysis = GenericScalarAnalysis(
        cfg,
        globals,
        memSummaries,
        sbfTypesFac,
        CFGTransformScalarDomFac()
    )
    resolveGetSysvarCalls(cfg, scalarAnalysis, globals)
}

private fun <D, TNum, TOffset> resolveGetSysvarCalls(
    cfg: MutableSbfCFG,
    scalarAnalysis: IAnalysis<D>,
    globals: GlobalVariables
)
where TNum: INumValue<TNum>,
      TOffset: IOffset<TOffset>,
      D: AbstractDomain<D>, D: ScalarValueProvider<TNum, TOffset> {
    val types = AnalysisRegisterTypes(scalarAnalysis)
    for (block in cfg.getMutableBlocks().values) {
        for (locInst in block.getLocatedInstructions()) {
            val inst = locInst.inst
            if (inst !is SbfInstruction.Call || inst.name != SolanaFunction.SOL_GET_SYSVAR.syscall.name) {
                continue
            }
            val id = SolGetSysvar.getSysvarId(locInst, types, globals) ?: continue
            val replacement = when (id) {
                SolanaSysVarId.RENT -> SolanaFunction.CVT_SOL_GET_RENT_SYSVAR
                SolanaSysVarId.CLOCK -> SolanaFunction.CVT_SOL_GET_CLOCK_SYSVAR
            }
            block.replaceInstruction(locInst, SolanaFunction.toCallInst(replacement, inst.metaData))
        }
    }
}
