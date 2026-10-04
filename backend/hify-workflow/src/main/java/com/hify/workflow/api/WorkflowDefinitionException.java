package com.hify.workflow.api;

import com.hify.common.BizException;
import com.hify.common.ErrorCode;

/** Invalid persisted/draft DSL, distinct from a model/provider failure in Chat. */
public final class WorkflowDefinitionException extends BizException {
    public WorkflowDefinitionException(String message) {this(ErrorCode.PARAM_ERROR,message);}
    public WorkflowDefinitionException(ErrorCode code,String message) {super(code,message);}
}
