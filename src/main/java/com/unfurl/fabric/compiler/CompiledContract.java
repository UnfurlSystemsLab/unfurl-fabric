package com.unfurl.fabric.compiler;

import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.deployment.plan.BindingPlan;

import java.util.List;

public record CompiledContract(
        CompositionContract contract,
        List<SelectionRecord> selections,
        DecisionAudit audit,
        String substrateProfileHash,
        BindingPlan bindingPlan,
        byte[] signature
) {
    public CompiledContract(
            CompositionContract contract,
            List<SelectionRecord> selections,
            DecisionAudit audit,
            String substrateProfileHash,
            byte[] signature) {
        this(contract, selections, audit, substrateProfileHash, null, signature);
    }

    public CompiledContract {
        if (contract == null) {
            throw new IllegalArgumentException("contract is required");
        }
        selections = selections == null ? List.of() : List.copyOf(selections);
        if (audit == null) {
            throw new IllegalArgumentException("audit is required");
        }
        if (substrateProfileHash != null && !substrateProfileHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("substrateProfileHash must be lowercase SHA-256 hex");
        }
        signature = signature == null ? null : signature.clone();
    }

    @Override
    public byte[] signature() {
        return signature == null ? null : signature.clone();
    }
}
