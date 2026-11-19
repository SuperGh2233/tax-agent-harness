package com.taxagent.harness.runtime;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.taxagent.harness.tool.*;
import com.taxagent.harness.tool.ToolContract.*;
import java.nio.file.Path;
import java.util.*;

/**
 * T34 recall_context (FINAL_ROUND_CONTRACT B7): the original behind the compressed model view, for one task of one
 * trusted user and enterprise. It reads only; every lookup rechecks access through TaskMemory or FileService.
 */
public final class ContextRecall {
    static final int MAX_TEXT = 4000, MAX_JSON = 12_000, DEFAULT_ROWS = 20;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path database;
    private final TaskMemory memory;
    private final PermissionGuard.Session session;
    private final String enterprise, taskId;

    public ContextRecall(Path database, TaskMemory memory, PermissionGuard.Session session, String enterprise, String taskId) {
        this.database = database; this.memory = memory; this.session = session; this.enterprise = enterprise; this.taskId = taskId;
    }

    /** currentTurn: this turn's exchanges so far, which are not stored yet. */
    public RecallResult recall(RecallQuery query, List<LlmClient.Exchange> currentTurn) throws Exception {
        return switch (query.source()) {
            case "TURN" -> turn(query.seq());
            case "TOOL_RESULT" -> toolResult(query, currentTurn);
            default -> file(query);
        };
    }

    private RecallResult turn(int seq) {
        var turn = memory.turns(session, enterprise, taskId).stream().filter(t -> t.seq() == seq).findFirst()
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No such turn in this task"));
        var text = "用户：" + turn.userMessage() + "\n助手：" + (turn.agentText() == null ? "（无回复）" : turn.agentText());
        return new RecallResult("TURN", "#" + seq, clip(text, MAX_TEXT), null, null, null, "第 " + seq + " 轮原文；不是当前数据库事实，业务数据须重新查询。");
    }

    private RecallResult toolResult(RecallQuery query, List<LlmClient.Exchange> currentTurn) throws Exception {
        String json = null;
        for (int i = currentTurn.size() - 1; i >= 0 && json == null; i--)
            if (currentTurn.get(i).call().id().equals(query.callId())) json = currentTurn.get(i).resultJson();
        if (json == null) json = memory.toolResult(session, enterprise, taskId, query.callId()).map(TaskMemory.StoredToolResult::resultJson)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No such tool call in this task"));
        var root = JSON.readTree(json);
        var rows = root.path("data").path("rows");
        if (!rows.isArray()) return new RecallResult("TOOL_RESULT", query.callId(), clip(json, MAX_JSON), null, null, null, null);
        int offset = query.offset() == null ? 0 : query.offset();
        int limit = query.limit() == null ? DEFAULT_ROWS : query.limit();
        var page = JSON.createArrayNode();
        for (int i = offset; i < Math.min(rows.size(), offset + limit); i++) page.add(rows.get(i));
        var copy = (ObjectNode) root.deepCopy();
        ((ObjectNode) copy.get("data")).set("rows", page);
        int next = offset + page.size();
        return new RecallResult("TOOL_RESULT", query.callId(), JSON.writeValueAsString(copy), rows.size(), offset, page.size(),
                next < rows.size() ? "还有 " + (rows.size() - next) + " 行，可用 offset=" + next + " 继续取回；合计字段始终是全部行的程序计算结果。" : "已到最后一行。");
    }

    private RecallResult file(RecallQuery query) {
        int neighbours = query.limit() == null ? 1 : query.limit();
        var detail = new FileService(database).segment(session, enterprise, query.fileId(), query.location(), neighbours);
        var text = new StringBuilder();
        for (var chunk : detail.chunks()) text.append(chunk.location()).append("：").append(chunk.text()).append('\n');
        return new RecallResult("FILE", detail.file().name() + " v" + detail.file().version() + " · " + query.location(),
                clip(text.toString().strip(), MAX_TEXT), detail.chunks().size(), null, null, "原文分段，按位置引用。");
    }

    private static String clip(String text, int max) { return text.length() <= max ? text : text.substring(0, max) + "…（已截断）"; }
}
