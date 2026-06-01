package com.unfurl.fabric.workflow;

public final class WorkflowAnalysisException extends RuntimeException {
    public WorkflowAnalysisException(String message) {
        super(message);
    }

    public WorkflowAnalysisException(String message, Throwable cause) {
        super(message, cause);
    }
}
