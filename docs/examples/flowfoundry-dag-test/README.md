# Flowfoundry DAG Smoke Fixture

This fixture is the source-driven smoke use case for validating Flow, Foundry, and recursive DCP
projection after the integrated environment exists.

The test models a Flowfoundry export assembly assistant:

- Flow owns the workflow DAG that sequences request collection, Foundry authoring, needs validation, intent application, compilation, recursive DCP inspection, and deployment resolution.
- Foundry owns the agent DAG that classifies the request, retrieves runbook context, proposes needs, proposes composition intents, verifies recursive closure, and prepares deployment resolution.
- Fabric only receives the generated DCP claim map through `dynamic-dcp/project`; it must not fabricate Flow workflow nodes or Foundry phase/tool/RAG nodes from selected catalog entries.

This fixture must not gate the Flowfoundry environment assembly. The assembly creates the integrated
Flow/Foundry runtime environment; a concrete workload DAG can be installed later into that deployed
environment. Use these files during runtime verification or post-deploy smoke testing:

```bash
mvn -q -pl unfurl-foundry-substrate/foundry-substrate-offers exec:java \
  -Dexec.mainClass=com.unfurl.foundry.substrate.offers.RecursiveProjectionRequestCli \
  -Dexec.args="--workflow unfurl-fabric/docs/examples/flowfoundry-dag-test/flowfoundry-export.workflow.yaml --agent unfurl-fabric/docs/examples/flowfoundry-dag-test/flowfoundry-export-agent.agent.yaml --output unfurl-fabric/target/flowfoundry-run/step-10-dag-test-projection-request.json"
```

Expected recursive projection levels include `WORKFLOW`, `NODE`, `AGENT`, `PHASE`, `PROMPT`, `MODEL`, `RAG`, and `TOOL`.
