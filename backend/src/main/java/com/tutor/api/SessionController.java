package com.tutor.api;

import com.tutor.common.web.Result;
import com.tutor.common.context.UserContext;
import com.tutor.api.dto.SessionVO;
import com.tutor.conversation.ConversationService;
import com.tutor.conversation.MessageService;
import com.tutor.learning.StateQueryService;
import com.tutor.learning.StateVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 会话 REST（契约 1.3）：list / create / messages / state / delete
 */
@RestController
@RequestMapping("/api/session")
@RequiredArgsConstructor
public class SessionController {

    private final ConversationService conversationService;
    private final MessageService messageService;
    private final StateQueryService stateQueryService;

    /** 会话列表（按 updatedAt 倒序） */
    @GetMapping("/list")
    public Result<List<SessionVO>> list() {
        return Result.ok(conversationService.listByUser(UserContext.getUser())
                .stream().map(SessionVO::of).toList());
    }

    /** 新建会话 */
    @PostMapping
    public Result<SessionVO> create() {
        return Result.ok(SessionVO.of(conversationService.create(UserContext.getUser())));
    }

    /** 历史消息（含各类型卡片，content 与实时一致） */
    @GetMapping("/{id}/messages")
    public Result<List<SessionVO.MessageVO>> messages(@PathVariable("id") String id) {
        conversationService.requireOwned(id, UserContext.getUser());
        return Result.ok(messageService.listForApi(id).stream()
                .map(m -> new SessionVO.MessageVO(
                        (String) m.get("id"), (String) m.get("type"), (String) m.get("role"),
                        m.get("content"), (long) m.get("ts")))
                .toList());
    }

    /** 状态机进度 + 学情摘要（右侧面板数据源，前端按卡片驱动拉取） */
    @GetMapping("/{id}/state")
    public Result<StateVO> state(@PathVariable("id") String id) {
        conversationService.requireOwned(id, UserContext.getUser());
        return Result.ok(stateQueryService.query(id));
    }

    /** 删除会话（可选功能） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") String id) {
        conversationService.requireOwned(id, UserContext.getUser());
        conversationService.delete(id);
        return Result.ok();
    }
}
