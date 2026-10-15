package com.psicocare.communications;

import static com.psicocare.shared.Json.*;
import static com.psicocare.shared.Store.*;

import com.psicocare.shared.*;

import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;

import java.util.*;

@Service
public class DeliveryModule implements ModuleHandler {
    private final Store db;
    private final Events events;

    public DeliveryModule(Store db, Events events) {
        this.db = db;
        this.events = events;
    }

    public boolean supports(Command c) {
        return c.tag().equals("Delivery");
    }

    public JsonNode execute(Command c) {
        var a = c.actor();
        a.require("OWNER", "ADMIN");
        if (c.path().endsWith("/dlq"))
            return c.page(
                    db
                            .rows(
                                    "select id,job_type,status,attempts,run_after from app.jobs"
                                        + " where tenant_id=? and status='DLQ' and job_type in"
                                        + " ('INVITE','NEWSLETTER_SEND','NEWSLETTER_CONFIRM','DAILY_REPORT_EMAIL')"
                                        + " order by run_after",
                                    a.tenant())
                            .stream()
                            .map(r -> dto(r, "id", "job_type", "status", "attempts", "run_after"))
                            .toList());
        if (c.vars().containsKey("jobId")) {
            a.recentMfa();
            var id = c.id("jobId");
            Problem.require(
                    text(c.body(), "decision").equals("APPROVE"), 422, "RETRY_APPROVAL_REQUIRED");
            Problem.require(
                    db.exec(
                                    "update app.jobs set"
                                        + " status='PENDING',attempts=0,run_after=now(),locked_until=null"
                                        + " where tenant_id=? and id=? and status='DLQ' and"
                                        + " job_type in"
                                        + " ('INVITE','NEWSLETTER_SEND','NEWSLETTER_CONFIRM','DAILY_REPORT_EMAIL')",
                                    a.tenant(),
                                    id)
                            == 1,
                    409,
                    "INVALID_TRANSITION");
            events.audit(a, "CommunicationJobRetryApproved", "job", id);
            return object("id", id, "version", 1);
        }
        if (c.isGet())
            return c.page(
                    db
                            .rows(
                                    "select"
                                        + " id,channel,status,sent_at,delivered_at,failure_code,attempts"
                                        + " from app.notifications where tenant_id=? union all"
                                        + " select s.request_id as"
                                        + " id,s.channel,s.delivery_state::app.notification_status"
                                        + " as status,s.created_at as sent_at,null::timestamptz as"
                                        + " delivered_at,null::text as failure_code,1 as attempts"
                                        + " from app.communication_sends s where s.tenant_id=? and"
                                        + " not exists(select 1 from app.notifications n where"
                                        + " n.tenant_id=s.tenant_id and"
                                        + " n.request_reference=s.request_id)",
                                    a.tenant(),
                                    a.tenant())
                            .stream()
                            .map(
                                    r ->
                                            dto(
                                                    r,
                                                    "id",
                                                    "channel",
                                                    "status",
                                                    "sent_at",
                                                    "delivered_at",
                                                    "failure_code",
                                                    "attempts"))
                            .toList());
        a.recentMfa();
        var id = c.id("notificationId");
        var n =
                db.one(
                        "select * from app.notifications where tenant_id=? and id=? for update",
                        a.tenant(),
                        id);
        Problem.require(
                str(n, "status").equals("FAILED")
                        && !str(n, "failure_code").equals("DELIVERY_UNCERTAIN"),
                409,
                "RETRY_REQUIRES_CONFIRMED_FAILURE");
        Problem.require(
                text(c.body(), "decision").equals("APPROVE"), 422, "RETRY_APPROVAL_REQUIRED");
        db.exec(
                "update app.notifications set"
                    + " status='QUEUED',attempts=0,request_reference=?,due_at=now(),failure_code=null,locked_until=null"
                    + " where tenant_id=? and id=?",
                UUID.randomUUID(),
                a.tenant(),
                id);
        events.audit(a, "NotificationRetryApproved", "notification", id);
        return object("id", id, "version", 1);
    }
}
