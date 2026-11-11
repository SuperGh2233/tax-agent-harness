package com.taxagent.harness.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/**
 * Deterministic, server-side execution plan. It is derived from persisted task state and real tool
 * history only: model prose cannot advance a step, and the plan is never write authority.
 */
public final class TaskPlan {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> QUERY_TOOLS =
            Set.of("query_invoices", "query_bank_entries", "check_invoice_purpose", "query_rules");

    private TaskPlan() {}

    public static TaskMemory.Plan derive(TaskMemory.Status status, List<String> pending,
                                         List<TaskMemory.Summary> recentTools,
                                         List<LlmClient.Exchange> history) {
        var tools = new HashSet<String>();
        for (var exchange : history) tools.add(exchange.call().name());
        for (var summary : recentTools) tools.add(summary.tool());
        boolean queried = tools.stream().anyMatch(QUERY_TOOLS::contains);
        boolean hasPending = pending != null && !pending.isEmpty();
        boolean completed = status == TaskMemory.Status.COMPLETED;
        boolean waiting = status == TaskMemory.Status.WAITING_USER;
        boolean verified = history.stream().anyMatch(TaskPlan::verifiedWrite);
        boolean writeAttempted = tools.contains("update_invoice");
        return new TaskMemory.Plan(List.of(
                step("scope", "确认企业与任务范围", "done", "任务已绑定当前企业"),
                step("facts", "查询当前事实", queried ? "done" : (completed || waiting) ? "done" : "current",
                        queried ? "已调用发票、银行或规则工具" : "尚未查询当前数据库"),
                step("rules", "核对规则与用途缺口", queried ? "done" : "current",
                        queried ? "以工具结果为准，不采用模型心算" : "等待查询规则/用途"),
                step("input", "补充或更正明确事实", hasPending ? "waiting" : "done",
                        hasPending ? "待补：" + String.join("、", pending) : "没有待补字段"),
                step("write", "保存并回读核验",
                        verified ? "done" : hasPending ? "optional" : writeAttempted ? "current" : "optional",
                        verified ? "verified=true，数据库回读一致"
                                : writeAttempted ? "已发起写入但未核验，禁止重复保存" : "只读、尚未授权保存"),
                step("close", "结项证据门", completed ? "done" : waiting ? "waiting" : "current",
                        completed ? "任务完成且证据门通过"
                                : waiting ? "等待用户补充，不能提前结项" : "执行中，禁止用模型文字结项")));
    }

    private static TaskMemory.PlanStep step(String key, String title, String state, String detail) {
        return new TaskMemory.PlanStep(key, title, state, detail);
    }

    private static boolean verifiedWrite(LlmClient.Exchange exchange) {
        if (!"update_invoice".equals(exchange.call().name())) return false;
        try { return JSON.readTree(exchange.resultJson()).at("/data/verified").asBoolean(false); }
        catch (Exception ignored) { return false; }
    }
}