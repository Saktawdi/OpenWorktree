package gate.web.controller;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.ticket.Ticket;
import gate.ports.store.TicketRepository;
import gate.web.service.RepoViewReader;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工单 → 仓库视图 (web console): the same read-only branch/commit graph as the project repo view,
 * but rooted at the ticket's own workspace.
 *
 * <p>That directory is whatever the ticket executes in: a normal ticket's isolated clone, and for
 * the quick-mode super ticket the project workspace itself (its {@code clone_path} IS the
 * workspace — no clone). Reading it here means the workbench can show "what has this ticket's
 * workspace actually committed" without the human leaving the ticket for the project page.
 *
 * <p>Git 读取与泳道算法全部来自 {@link RepoViewReader}（与项目仓库视图同一份实现），本类只负责
 * 「工单号 → 仓库路径」这一层解析，以及按这张工单补充的身份字段。刻意不带 {@code auth}/tree：
 * 工作台只需要提交历史图，文件树与「同步工作区」是项目页的能力（权威库同步不是工单级动作）。
 */
public final class TicketRepoController implements WebController {

    private final TicketRepository tickets;
    private final RepoViewReader reader;

    public TicketRepoController(TicketRepository tickets, RepoViewReader reader) {
        this.tickets = tickets;
        this.reader = reader;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/tickets/{no}/repo", this::repoView);
        app.get("/api/tickets/{no}/commit/<sha>", this::commitDetail);
    }

    /** GET /api/tickets/{no}/repo — 工单工作区的分支 + 提交图（泳道号同项目视图）。 */
    public void repoView(Context ctx) {
        Ticket t = requireTicket(ctx.pathParam("no"));
        Path ws = clonePath(t);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticket_no", t.ticketNo());
        body.put("repo_path", ws.toString());

        RepoViewReader.RepoGraph graph = reader.readGraph(ws, t.targetRef());
        body.put("head", graph.head());

        List<Map<String, Object>> commitRows = new ArrayList<>();
        for (RepoViewReader.Commit c : graph.commits()) {
            commitRows.add(RepoViewController.commitRow(c));
        }
        body.put("commits", commitRows);
        body.put("truncated", graph.truncated());

        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    /** GET /api/tickets/{no}/commit/<sha> — 同一提交详情（与项目页共用一份契约）。 */
    public void commitDetail(Context ctx) {
        Ticket t = requireTicket(ctx.pathParam("no"));
        Path ws = clonePath(t);
        RepoViewReader.CommitDetail d = reader.readCommit(ws, ctx.pathParam("sha"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticket_no", t.ticketNo());
        body.putAll(RepoViewController.commitDetailBody(d));

        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    private Path clonePath(Ticket t) {
        String clone = t.clonePath();
        if (clone == null || clone.isBlank()) {
            throw new GateException(GateErrorCode.USAGE,
                    "ticket has no workspace directory: " + t.ticketNo());
        }
        return Path.of(clone);
    }

    private Ticket requireTicket(String no) {
        return tickets.find(no).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such ticket: " + no));
    }
}
