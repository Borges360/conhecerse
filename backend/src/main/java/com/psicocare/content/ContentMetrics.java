package com.psicocare.content;

import static com.psicocare.shared.Json.*;
import static com.psicocare.shared.Store.*;

import com.psicocare.shared.*;

import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;

@Service
public class ContentMetrics implements ModuleHandler {
    private final Store db;

    public ContentMetrics(Store db) {
        this.db = db;
    }

    public boolean supports(Command c) {
        return c.tag().equals("ContentMetrics");
    }

    public JsonNode execute(Command c) {
        if (c.isGet()) {
            c.actor().require("OWNER", "ADMIN");
            return c.page(
                    db
                            .rows(
                                    "select content_id,day,read_count from app.content_read_totals"
                                        + " where tenant_id=? order by day desc,content_id",
                                    c.actor().tenant())
                            .stream()
                            .map(r -> dto(r, "content_id", "day", "read_count"))
                            .toList());
        }
        var tenant =
                id(
                        db.one(
                                "select id from app.organizations where slug=? and status='ACTIVE'",
                                c.vars().get("orgSlug")),
                        "id");
        db.jdbc.queryForObject(
                "select set_config('app.tenant_id',?,true)", String.class, tenant.toString());
        var id = c.id("contentId");
        db.one(
                "select id from app.content_entries where tenant_id=? and id=? and"
                    + " status='PUBLISHED' and visibility='PUBLIC' and entry_type='BLOG'",
                tenant,
                id);
        db.exec(
                "insert into app.content_read_totals(tenant_id,content_id,day,read_count)"
                    + " values(?,?,(now() at time zone 'America/Sao_Paulo')::date,1) on"
                    + " conflict(tenant_id,content_id,day) do update set"
                    + " read_count=app.content_read_totals.read_count+1",
                tenant,
                id);
        return object("accepted", true);
    }
}
